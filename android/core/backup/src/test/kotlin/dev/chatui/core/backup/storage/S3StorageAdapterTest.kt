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

class S3StorageAdapterTest {
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
    fun `testConnection sends a signed HEAD to the bucket`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())

        adapter().testConnection()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("HEAD")
        assertThat(request.url.encodedPath).isEqualTo("/test-bucket")
        assertThat(request.headers["Authorization"]).startsWith("AWS4-HMAC-SHA256 ")
        assertThat(request.headers["x-amz-content-sha256"])
            .isEqualTo(SigV4Signer.sha256Hex(ByteArray(0)))
    }

    @Test
    fun `getObject returns encrypted bytes and captures the ETag`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x01, 0x02)
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
            .isEqualTo("/test-bucket/manifest.enc")
    }

    @Test
    fun `putObject sends If-Match and returns the ETag`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x03)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
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
                .code(200)
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
    fun `deleteObject sends a signed DELETE`(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())

        adapter().deleteObject("manifest.enc")

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.url.encodedPath).isEqualTo("/test-bucket/manifest.enc")
        assertThat(request.headers["Authorization"]).startsWith("AWS4-HMAC-SHA256 ")
    }

    @Test
    fun `listObjects parses keys, ETags, and continuation tokens`(): Unit = runBlocking {
        val xml = """
            <ListBucketResult>
              <Contents>
                <Key>chatui/backups/manifest.enc</Key>
                <ETag>"etag-manifest"</ETag>
                <LastModified>2026-01-01T00:00:00.000Z</LastModified>
              </Contents>
              <Contents>
                <Key>chatui/backups/conversations/abc.enc</Key>
                <ETag>"etag-conversation"</ETag>
              </Contents>
              <NextContinuationToken>next-page</NextContinuationToken>
            </ListBucketResult>
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
        assertThat(request.url.encodedPath).isEqualTo("/test-bucket")
        assertThat(request.url.queryParameter("list-type")).isEqualTo("2")
        assertThat(request.url.queryParameter("prefix"))
            .isEqualTo("chatui/backups/conversations/")
        assertThat(request.url.queryParameter("max-keys")).isEqualTo("10")
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
        server.enqueue(MockResponse.Builder().code(200).build())
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
    fun `builds path-style and virtual-hosted URLs`() {
        val pathStyle = adapterForUrl(forcePathStyle = true)
        assertThat(pathStyle.bucketUrl().toString())
            .isEqualTo("https://s3.us-east-1.amazonaws.com/my-bucket")
        assertThat(pathStyle.objectUrl("manifest.enc").toString())
            .isEqualTo("https://s3.us-east-1.amazonaws.com/my-bucket/manifest.enc")

        val virtualHosted = adapterForUrl(forcePathStyle = false)
        assertThat(virtualHosted.bucketUrl().toString())
            .isEqualTo("https://my-bucket.s3.us-east-1.amazonaws.com/")
        assertThat(virtualHosted.objectUrl("manifest.enc").toString())
            .isEqualTo("https://my-bucket.s3.us-east-1.amazonaws.com/manifest.enc")
    }

    @Test
    fun `applies the prefix and encodes object key segments`() {
        val prefixed = adapterForUrl(forcePathStyle = true, prefix = "chatui/backups")

        assertThat(prefixed.objectUrl("conversations/a b.enc").toString())
            .isEqualTo(
                "https://s3.us-east-1.amazonaws.com/my-bucket/" +
                    "chatui/backups/conversations/a%20b.enc",
            )
    }

    @Test
    fun `putObject sends only the provided encrypted bytes`(): Unit = runBlocking {
        val encrypted = byteArrayOf(
            0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("ETag", "\"etag-bytes\"")
                .build(),
        )

        adapter().putObject("manifest.enc", encrypted)

        val request = server.takeRequest()
        assertThat(request.body!!.toByteArray()).isEqualTo(encrypted)
        assertThat(request.headers["Content-Type"]).isEqualTo("application/octet-stream")
    }

    private fun adapter(
        prefix: String? = null,
        retryDelayMs: Long = 0,
        endpoint: String = server.url("/").toString(),
    ): S3StorageAdapter = S3StorageAdapter(
        S3StorageConfig(
            endpoint = endpoint,
            bucket = "test-bucket",
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            prefix = prefix,
            forcePathStyle = true,
            client = client,
            now = { Instant.parse("2013-05-24T00:00:00Z") },
            retryDelayMs = retryDelayMs,
        ),
    )

    private fun adapterForUrl(
        forcePathStyle: Boolean,
        prefix: String? = null,
    ): S3StorageAdapter = S3StorageAdapter(
        S3StorageConfig(
            endpoint = "https://s3.us-east-1.amazonaws.com",
            bucket = "my-bucket",
            region = "us-east-1",
            accessKeyId = "key",
            secretAccessKey = "secret",
            prefix = prefix,
            forcePathStyle = forcePathStyle,
            client = client,
        ),
    )
}
