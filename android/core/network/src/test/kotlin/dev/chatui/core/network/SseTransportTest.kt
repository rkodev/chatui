package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test

class SseTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun transport(maxBufferChars: Int = DEFAULT_MAX_SSE_BUFFER_CHARS): SseTransport =
        SseTransport(client, maxBufferChars)

    private fun request(path: String = "/stream"): Request =
        Request.Builder().url(server.url(path)).build()

    private fun enqueue(body: String, chunkSize: Int = 0) {
        val builder = MockResponse.Builder()
            .addHeader("Content-Type", "text/event-stream")

        if (chunkSize > 0) {
            builder.chunkedBody(body, chunkSize)
        } else {
            builder.body(body)
        }

        server.enqueue(builder.build())
    }

    private suspend fun collect(
        body: String,
        chunkSize: Int = 0,
        maxBufferChars: Int = DEFAULT_MAX_SSE_BUFFER_CHARS,
    ): List<String> {
        enqueue(body, chunkSize)
        return transport(maxBufferChars).stream(request()).toList()
    }

    @Test
    fun `keep-alives produce no data events`() = runTest {
        val events = collect(
            ": OPENROUTER PROCESSING\n\n" +
                "data: {\"a\":1}\n\n" +
                ": OPENROUTER PROCESSING\n\n" +
                "data: {\"b\":2}\n\n",
        )

        assertThat(events).containsExactly("{\"a\":1}", "{\"b\":2}").inOrder()
    }

    @Test
    fun `multi-line data is concatenated with a single newline`() = runTest {
        val events = collect("data: line one\ndata: line two\ndata:line three\n\n")

        assertThat(events).containsExactly("line one\nline two\nline three")
    }

    @Test
    fun `CRLF and lone CR line endings are normalized`() = runTest {
        val events = collect("data: one\r\n\r\ndata: two\r\rdata: three\n\n")

        assertThat(events).containsExactly("one", "two", "three").inOrder()
    }

    @Test
    fun `split chunks do not lose or duplicate data`() = runTest {
        val body = "data: {\"text\":\"hello\"}\n\ndata: {\"text\":\" world\"}\n\n"

        assertThat(collect(body, chunkSize = 1))
            .containsExactly("{\"text\":\"hello\"}", "{\"text\":\" world\"}")
            .inOrder()
        assertThat(collect(body, chunkSize = 7))
            .containsExactly("{\"text\":\"hello\"}", "{\"text\":\" world\"}")
            .inOrder()
    }

    @Test
    fun `a CRLF pair split across chunks is handled`() = runTest {
        val events = collect("data: one\r\n\r\ndata: two\r\n\r\n", chunkSize = 1)

        assertThat(events).containsExactly("one", "two").inOrder()
    }

    @Test
    fun `the final buffer is flushed when the stream ends without a blank line`() = runTest {
        assertThat(collect("data: final")).containsExactly("final")
        assertThat(collect("data: final\n")).containsExactly("final")
        assertThat(collect("data: one\ndata: two")).containsExactly("one\ntwo")
    }

    @Test
    fun `malformed data is passed through without parsing`() = runTest {
        assertThat(collect("data: {not valid json}\n\n"))
            .containsExactly("{not valid json}")
        assertThat(collect("data: {\"broken\"")).containsExactly("{\"broken\"")
    }

    @Test
    fun `comments unknown fields and empty data values are ignored`() = runTest {
        val events = collect(
            ": OPENROUTER PROCESSING\n\n" +
                "event: message\nid: 1\nretry: 100\ndata: payload\n\n" +
                "data:\n\n" +
                "data: \n\n",
        )

        assertThat(events).containsExactly("payload")
    }

    @Test
    fun `buffer size is bounded for an incomplete event`() = runBlocking {
        enqueue("data: ${"x".repeat(100)}")

        val error = try {
            transport(maxBufferChars = 50).stream(request()).toList()
            throw AssertionError("Expected SseBufferOverflowException")
        } catch (error: SseBufferOverflowException) {
            error
        }

        assertThat(error.maxBufferChars).isEqualTo(50)
        Unit
    }

    @Test
    fun `buffer size is bounded across multiple data lines`() = runBlocking {
        enqueue("data: ${"x".repeat(30)}\ndata: ${"x".repeat(30)}\n\n")

        val error = try {
            transport(maxBufferChars = 50).stream(request()).toList()
            throw AssertionError("Expected SseBufferOverflowException")
        } catch (error: SseBufferOverflowException) {
            error
        }

        assertThat(error.maxBufferChars).isEqualTo(50)
        Unit
    }

    @Test
    fun `complete events are drained so many small events do not overflow`() = runTest {
        val body = (0 until 100).joinToString("") { "data: $it\n\n" }

        val events = collect(body, maxBufferChars = 32)

        assertThat(events).hasSize(100)
        assertThat(events.first()).isEqualTo("0")
        assertThat(events.last()).isEqualTo("99")
    }

    @Test
    fun `HTTP errors carry status headers and the raw body`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("Retry-After", "2")
                .body("{\"error\":{\"message\":\"slow down\"}}")
                .build(),
        )

        val error = try {
            transport().stream(request()).toList()
            throw AssertionError("Expected SseHttpException")
        } catch (error: SseHttpException) {
            error
        }

        assertThat(error.code).isEqualTo(429)
        assertThat(error.headers["Retry-After"]).isEqualTo("2")
        assertThat(error.body).contains("slow down")
        Unit
    }

    @Test
    fun `cancellation cancels the OkHttp call and does not hang`() = runBlocking {
        val release = CountDownLatch(1)
        val canceled = AtomicBoolean(false)
        val cancelClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        canceled.set(true)
                    }
                },
            )
            .build()

        try {
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "text/event-stream")
                    .body(HangingSseBody("data: one\n\n", release))
                    .build(),
            )

            val events = CopyOnWriteArrayList<String>()
            val job = launch(Dispatchers.IO) {
                SseTransport(cancelClient).stream(request()).collect { events += it }
            }

            withTimeout(5_000) {
                while (events.isEmpty()) {
                    delay(10)
                }
            }

            withTimeout(5_000) { job.cancelAndJoin() }

            assertThat(canceled.get()).isTrue()
            assertThat(events).containsExactly("one")
            Unit
        } finally {
            release.countDown()
            cancelClient.dispatcher.executorService.shutdown()
            cancelClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `OpenRouter keep-alive fixture emits only data payloads`() = runTest {
        val fixture = readSseTestResource("/contracts/fixtures/streams/openrouter-keepalive.sse")

        val events = collect(fixture)

        assertThat(events).hasSize(5)
        assertThat(events[0]).contains("\"content\":\"Keep\"")
        assertThat(events[1]).contains("\"content\":\" alive\"")
        assertThat(events[2]).contains("\"content\":\" comments\"")
        assertThat(events[3]).contains("\"finish_reason\":\"stop\"")
        assertThat(events[4]).isEqualTo("[DONE]")
        assertThat(events.none { it.contains("OPENROUTER PROCESSING") }).isTrue()
    }

    @Test
    fun `OpenRouter keep-alive fixture survives arbitrary chunk boundaries`() = runTest {
        val fixture = readSseTestResource("/contracts/fixtures/streams/openrouter-keepalive.sse")

        val events = collect(fixture, chunkSize = 7)

        assertThat(events).hasSize(5)
        assertThat(events[4]).isEqualTo("[DONE]")
    }

    @Test
    fun `OpenRouter keep-alive fixture handles CRLF line endings`() = runTest {
        val fixture = readSseTestResource("/contracts/fixtures/streams/openrouter-keepalive.sse")
            .replace("\n", "\r\n")

        val events = collect(fixture)

        assertThat(events).hasSize(5)
        assertThat(events[4]).isEqualTo("[DONE]")
    }
}
