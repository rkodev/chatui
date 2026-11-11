package dev.chatui.core.backup.storage

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class WebDavStorageAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `testConnection sends Basic auth and PROPFIND`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(207).build())

        adapter().testConnection()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PROPFIND")
        assertThat(request.url.encodedPath).isEqualTo("/dav/")
        assertThat(request.headers["Depth"]).isEqualTo("0")
        assertThat(request.headers["Authorization"]).startsWith("Basic ")
        assertThat(request.body!!.toByteArray().decodeToString()).contains("propfind")
    }

    @Test
    fun `getObject returns encrypted bytes and captures the ETag`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x01)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("ETag", "\"etag-get\"")
                .addHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .body(Buffer().write(encrypted))
                .build(),
        )

        val result = adapter().getObject("manifest.enc")

        assertThat(result.key).isEqualTo("manifest.enc")
        assertThat(result.bytes).isEqualTo(encrypted)
        assertThat(result.etag).isEqualTo("\"etag-get\"")
        assertThat(result.lastModified).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/dav/manifest.enc")
    }

    @Test
    fun `putObject sends If-Match and returns the ETag`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x03)
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("ETag", "\"etag-put\"")
                .build(),
        )

        val result = adapter().putObject(
            key = "manifest.enc",
            encryptedBytes = encrypted,
            options = PutObjectOptions(ifMatch = "\"etag-old\""),
        )

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.headers["If-Match"]).isEqualTo("\"etag-old\"")
        assertThat(request.headers["If-None-Match"]).isNull()
        assertThat(request.headers["Content-Type"]).isEqualTo("application/octet-stream")
        assertThat(request.body!!.toByteArray()).isEqualTo(encrypted)
        assertThat(result.etag).isEqualTo("\"etag-put\"")
    }

    @Test
    fun `putObject sends If-None-Match for create-only writes`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("ETag", "\"etag-create\"")
                .build(),
        )

        adapter().putObject(
            key = "manifest.enc",
            encryptedBytes = byteArrayOf(0x01, 0x02),
            options = PutObjectOptions(ifNoneMatch = "*"),
        )

        val request = server.takeRequest()
        assertThat(request.headers["If-None-Match"]).isEqualTo("*")
        assertThat(request.headers["If-Match"]).isNull()
    }

    @Test
    fun `conditional write conflict returns a typed conflict error`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(412)
                .body("precondition failed")
                .build(),
        )

        val error = try {
            adapter().putObject(
                key = "manifest.enc",
                encryptedBytes = byteArrayOf(0x01),
                options = PutObjectOptions(ifMatch = "\"stale\""),
            )
            null
        } catch (failure: StorageError) {
            failure
        }

        assertThat(error).isNotNull()
        assertThat(error!!.code).isEqualTo(StorageErrorCode.CONFLICT)
        assertThat(error.status).isEqualTo(412)
    }

    @Test
    fun `headObject returns metadata without a body`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("ETag", "\"etag-head\"")
                .build(),
        )

        val result = adapter().headObject("manifest.enc")

        assertThat(result.bytes).isEmpty()
        assertThat(result.etag).isEqualTo("\"etag-head\"")
        assertThat(server.takeRequest().method).isEqualTo("HEAD")
    }

    @Test
    fun `deleteObject sends a DELETE`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        adapter().deleteObject("manifest.enc")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.url.encodedPath).isEqualTo("/dav/manifest.enc")
    }

    @Test
    fun `listObjects parses PROPFIND responses`(): Unit = runBlocking {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/chatui/backups/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/chatui/backups/manifest.enc</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-manifest"</d:getetag>
                    <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
                    <d:resourcetype/>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/chatui/backups/conversations/abc.enc</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getetag>"etag-conversation"</d:getetag>
                    <d:resourcetype/>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(207).body(xml).build())

        val result = adapter(prefix = "chatui/backups").listObjects(
            ListObjectsOptions(prefix = "conversations/"),
        )

        assertThat(result.objects.map { it.key })
            .containsExactly("conversations/abc.enc")
        assertThat(result.objects[0].etag).isEqualTo("\"etag-conversation\"")
        assertThat(result.nextContinuationToken).isNull()

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/dav/chatui/backups/")
        assertThat(request.headers["Depth"]).isEqualTo("1")
    }

    @Test
    fun `testConnection maps HTTP statuses to typed errors`(): Unit = runBlocking {
        val cases = listOf(
            401 to StorageErrorCode.AUTH,
            403 to StorageErrorCode.PERMISSION,
            404 to StorageErrorCode.NOT_FOUND,
            500 to StorageErrorCode.SERVER,
        )

        for ((status, expectedCode) in cases) {
            server.enqueue(MockResponse.Builder().code(status).build())

            val error = try {
                adapter().testConnection()
                null
            } catch (failure: StorageError) {
                failure
            }

            assertThat(error).isNotNull()
            assertThat(error!!.code).isEqualTo(expectedCode)
            assertThat(error.status).isEqualTo(status)
        }
    }

    @Test
    fun `testConnection maps network failures to a typed error`(): Unit = runBlocking {
        val error = try {
            adapter(baseUrl = "http://127.0.0.1:1/dav").testConnection()
            null
        } catch (failure: StorageError) {
            failure
        }

        assertThat(error).isNotNull()
        assertThat(error!!.code).isEqualTo(StorageErrorCode.NETWORK)
    }

    @Test
    fun `putObject re-reads when the provider omits an ETag`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x01, 0x02, 0x03)
        server.enqueue(MockResponse.Builder().code(201).build())
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("ETag", "\"etag-late\"")
                .build(),
        )

        val result = adapter(retryDelayMs = 0).putObject("manifest.enc", encrypted)

        assertThat(result.etag).isEqualTo("\"etag-late\"")
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(server.takeRequest().method).isEqualTo("PUT")
        assertThat(server.takeRequest().method).isEqualTo("HEAD")
        assertThat(server.takeRequest().method).isEqualTo("HEAD")
    }

    @Test
    fun `putObject sends only the provided encrypted bytes`(): Unit = runBlocking {
        val encrypted = byteArrayOf(
            0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
        )
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader("ETag", "\"etag-bytes\"")
                .build(),
        )

        adapter().putObject("manifest.enc", encrypted)

        val request = server.takeRequest()
        assertThat(request.body!!.toByteArray()).isEqualTo(encrypted)
        assertThat(request.headers["Content-Type"]).isEqualTo("application/octet-stream")
    }

    @Test
    fun `builds collection and object URLs`() {
        val adapter = adapterForUrl()
        assertThat(adapter.collectionUrl().toString())
            .isEqualTo("https://dav.example.com/dav/")
        assertThat(adapter.objectUrl("conversations/a b.enc").toString())
            .isEqualTo("https://dav.example.com/dav/conversations/a%20b.enc")
    }

    private fun adapter(
        prefix: String? = null,
        retryDelayMs: Long = 0,
        baseUrl: String = server.url("/dav").toString().trimEnd('/'),
    ): WebDavStorageAdapter = WebDavStorageAdapter(
        WebDavStorageConfig(
            baseUrl = baseUrl,
            username = "user",
            password = "pass",
            prefix = prefix,
            client = client,
            retryDelayMs = retryDelayMs,
        ),
    )

    private fun adapterForUrl(): WebDavStorageAdapter = WebDavStorageAdapter(
        WebDavStorageConfig(
            baseUrl = "https://dav.example.com/dav",
            username = "user",
            password = "pass",
            client = client,
        ),
    )
}
