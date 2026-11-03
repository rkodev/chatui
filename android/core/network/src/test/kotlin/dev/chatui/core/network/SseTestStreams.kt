package dev.chatui.core.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponseBody
import okio.BufferedSink

/**
 * A MockWebServer body that writes one SSE event, flushes it, and then blocks
 * until the test releases it. Used to prove that cancellation unblocks a read
 * instead of waiting for the server to finish.
 */
internal class HangingSseBody(
    private val firstChunk: String,
    private val release: CountDownLatch,
) : MockResponseBody {
    override val contentLength: Long = -1L

    override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8(firstChunk)
        sink.flush()
        release.await(10, TimeUnit.SECONDS)
    }
}

/** Read a checked-in fixture from the test classpath. */
internal fun readSseTestResource(path: String): String {
    val normalized = path.removePrefix("/")
    val stream = SseTransportTest::class.java.classLoader.getResourceAsStream(normalized)
        ?: error("Test resource '$normalized' was not found")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
