package dev.chatui.core.backup.storage

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AzureBlobStorageAdapterTest {
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
    fun `testConnection sends a SharedKey HEAD to the container`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())

        adapter().testConnection()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("HEAD")
        assertThat(request.url.encodedPath).isEqualTo("/test-container")
        assertThat(request.url.queryParameter("restype")).isEqualTo("container")
        assertThat(request.headers["Authorization"]).startsWith("SharedKey testaccount:")
        assertThat(request.headers["x-ms-date"]).isNotEmpty()
        assertThat(request.headers["x-ms-version"]).isEqualTo("2021-12-02")
    }

    @Test
    fun `testConnection uses a SAS token without an Authorization header`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())

        adapter(accountKey = null, sasToken = "sv=2021-12-02&sig=abc").testConnection()

        val request = server.takeRequest()
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.url.queryParameter("sv")).isEqualTo("2021-12-02")
        assertThat(request.url.queryParameter("sig")).isEqualTo("abc")
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
        assertThat(server.takeRequest().url.encodedPath)
            .isEqualTo("/test-container/manifest.enc")
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
        assertThat(request.headers["x-ms-blob-type"]).isEqualTo("BlockBlob")
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
        server.enqueue(MockResponse.Builder().code(202).build())

        adapter().deleteObject("manifest.enc")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.url.encodedPath).isEqualTo("/test-container/manifest.enc")
        assertThat(request.headers["Authorization"]).startsWith("SharedKey testaccount:")
    }

    @Test
    fun `listObjects parses names, ETags, and next marker`(): Unit = runBlocking {
        val xml = """
            <EnumerationResults>
              <Blobs>
                <Blob>
                  <Name>chatui/backups/manifest.enc</Name>
                  <Properties>
                    <Last-Modified>2026-01-01T00:00:00.000Z</Last-Modified>
                    <Etag>"etag-manifest"</Etag>
                  </Properties>
                </Blob>
                <Blob>
                  <Name>chatui/backups/conversations/abc.enc</Name>
                  <Properties>
                    <Etag>"etag-conversation"</Etag>
                  </Properties>
                </Blob>
              </Blobs>
              <NextMarker>next-page</NextMarker>
            </EnumerationResults>
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(xml).build())

        val result = adapter(prefix = "chatui/backups").listObjects(
            ListObjectsOptions(prefix = "conversations/", maxKeys = 10),
        )

        assertThat(result.objects.map { it.key })
            .containsExactly("manifest.enc", "conversations/abc.enc")
            .inOrder()
        assertThat(result.objects[0].etag).isEqualTo("\"etag-manifest\"")
        assertThat(result.objects[0].lastModified).isEqualTo("2026-01-01T00:00:00.000Z")
        assertThat(result.nextContinuationToken).isEqualTo("next-page")

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/test-container")
        assertThat(request.url.queryParameter("restype")).isEqualTo("container")
        assertThat(request.url.queryParameter("comp")).isEqualTo("list")
        assertThat(request.url.queryParameter("prefix"))
            .isEqualTo("chatui/backups/conversations/")
        assertThat(request.url.queryParameter("maxresults")).isEqualTo("10")
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
            adapter(endpoint = "http://127.0.0.1:1/").testConnection()
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
    fun `builds container and object URLs`() {
        val adapter = adapterForUrl()
        assertThat(adapter.containerUrl().toString())
            .isEqualTo("https://testaccount.blob.core.windows.net/test-container")
        assertThat(adapter.objectUrl("conversations/a b.enc").toString())
            .isEqualTo(
                "https://testaccount.blob.core.windows.net/test-container/" +
                    "conversations/a%20b.enc",
            )
    }

    private fun adapter(
        accountKey: String? = "dGVzdC1rZXk=",
        sasToken: String? = null,
        prefix: String? = null,
        retryDelayMs: Long = 0,
        endpoint: String = server.url("/").toString(),
    ): AzureBlobStorageAdapter = AzureBlobStorageAdapter(
        AzureBlobStorageConfig(
            accountName = "testaccount",
            accountKey = accountKey,
            sasToken = sasToken,
            container = "test-container",
            endpoint = endpoint,
            prefix = prefix,
            client = client,
            now = { Instant.parse("2026-01-01T00:00:00Z") },
            retryDelayMs = retryDelayMs,
        ),
    )

    private fun adapterForUrl(): AzureBlobStorageAdapter = AzureBlobStorageAdapter(
        AzureBlobStorageConfig(
            accountName = "testaccount",
            accountKey = "dGVzdC1rZXk=",
            container = "test-container",
            client = client,
        ),
    )
}
