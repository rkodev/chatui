package dev.chatui.core.backup.storage

import com.google.common.truth.Truth.assertThat
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class GcsStorageAdapterTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var keyPair: KeyPair

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `testConnection exchanges a signed JWT for a token and calls the bucket`(): Unit =
        runBlocking {
            enqueueToken()
            server.enqueue(MockResponse.Builder().code(200).body("""{"name":"test-bucket"}""").build())

            adapter().testConnection()

            val tokenRequest = server.takeRequest()
            assertThat(tokenRequest.method).isEqualTo("POST")
            assertThat(tokenRequest.url.encodedPath).isEqualTo("/token")
            val tokenBody = tokenRequest.body!!.toByteArray().decodeToString()
            assertThat(tokenBody).contains(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer",
            )
            val assertion = Regex("assertion=([^&]+)").find(tokenBody)?.groupValues?.get(1)
            assertThat(assertion).isNotNull()
            assertThat(assertion!!.split('.')).hasSize(3)

            val storageRequest = server.takeRequest()
            assertThat(storageRequest.method).isEqualTo("GET")
            assertThat(storageRequest.url.encodedPath)
                .isEqualTo("/storage/v1/b/test-bucket")
            assertThat(storageRequest.headers["Authorization"]).isEqualTo("Bearer test-token")
        }

    @Test
    fun `getObject returns encrypted bytes and captures the generation`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x01)
        enqueueToken()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("x-goog-generation", "42")
                .addHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .body(Buffer().write(encrypted))
                .build(),
        )

        val result = adapter().getObject("manifest.enc")

        assertThat(result.key).isEqualTo("manifest.enc")
        assertThat(result.bytes).isEqualTo(encrypted)
        assertThat(result.etag).isEqualTo("42")
        assertThat(result.lastModified).isEqualTo("Wed, 21 Oct 2015 07:28:00 GMT")

        server.takeRequest()
        val mediaRequest = server.takeRequest()
        assertThat(mediaRequest.url.encodedPath)
            .isEqualTo("/storage/v1/b/test-bucket/o/manifest.enc")
        assertThat(mediaRequest.url.queryParameter("alt")).isEqualTo("media")
    }

    @Test
    fun `putObject sends ifGenerationMatch and returns the generation`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x03)
        enqueueToken()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"generation":"42","updated":"2026-01-01T00:00:00Z",""" +
                        """"name":"manifest.enc"}""",
                )
                .build(),
        )

        val result = adapter().putObject(
            key = "manifest.enc",
            encryptedBytes = encrypted,
            options = PutObjectOptions(ifMatch = "41"),
        )

        server.takeRequest()
        val uploadRequest = server.takeRequest()
        assertThat(uploadRequest.method).isEqualTo("POST")
        assertThat(uploadRequest.url.encodedPath)
            .isEqualTo("/upload/storage/v1/b/test-bucket/o")
        assertThat(uploadRequest.url.queryParameter("uploadType")).isEqualTo("media")
        assertThat(uploadRequest.url.queryParameter("name")).isEqualTo("manifest.enc")
        assertThat(uploadRequest.url.queryParameter("ifGenerationMatch")).isEqualTo("41")
        assertThat(uploadRequest.body!!.toByteArray()).isEqualTo(encrypted)
        assertThat(result.etag).isEqualTo("42")
    }

    @Test
    fun `putObject sends ifGenerationMatch zero for create-only writes`(): Unit = runBlocking {
        enqueueToken()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"generation":"1","updated":"2026-01-01T00:00:00Z"}""")
                .build(),
        )

        adapter().putObject(
            key = "manifest.enc",
            encryptedBytes = byteArrayOf(0x01, 0x02),
            options = PutObjectOptions(ifNoneMatch = "*"),
        )

        server.takeRequest()
        val uploadRequest = server.takeRequest()
        assertThat(uploadRequest.url.queryParameter("ifGenerationMatch")).isEqualTo("0")
        assertThat(uploadRequest.url.queryParameter("ifGenerationNotMatch")).isNull()
    }

    @Test
    fun `conditional write conflict returns a typed conflict error`(): Unit = runBlocking {
        enqueueToken()
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
                options = PutObjectOptions(ifMatch = "41"),
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
        enqueueToken()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"generation":"42","updated":"2026-01-01T00:00:00Z"}""")
                .build(),
        )

        val result = adapter().headObject("manifest.enc")

        assertThat(result.bytes).isEmpty()
        assertThat(result.etag).isEqualTo("42")
        server.takeRequest()
        assertThat(server.takeRequest().method).isEqualTo("GET")
    }

    @Test
    fun `deleteObject sends a DELETE`(): Unit = runBlocking {
        enqueueToken()
        server.enqueue(MockResponse.Builder().code(204).build())

        adapter().deleteObject("manifest.enc")

        server.takeRequest()
        val deleteRequest = server.takeRequest()
        assertThat(deleteRequest.method).isEqualTo("DELETE")
        assertThat(deleteRequest.url.encodedPath)
            .isEqualTo("/storage/v1/b/test-bucket/o/manifest.enc")
    }

    @Test
    fun `listObjects parses names, generations, and next page tokens`(): Unit = runBlocking {
        enqueueToken()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"items":[""" +
                        """{"name":"chatui/backups/manifest.enc","generation":"1",""" +
                        """"updated":"2026-01-01T00:00:00Z"},""" +
                        """{"name":"chatui/backups/conversations/abc.enc","generation":"2"}""" +
                        """],"nextPageToken":"next"}""",
                )
                .build(),
        )

        val result = adapter(prefix = "chatui/backups").listObjects(
            ListObjectsOptions(prefix = "conversations/", maxKeys = 10),
        )

        assertThat(result.objects.map { it.key })
            .containsExactly("manifest.enc", "conversations/abc.enc")
            .inOrder()
        assertThat(result.objects[0].etag).isEqualTo("1")
        assertThat(result.objects[0].lastModified).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(result.nextContinuationToken).isEqualTo("next")

        server.takeRequest()
        val listRequest = server.takeRequest()
        assertThat(listRequest.url.encodedPath).isEqualTo("/storage/v1/b/test-bucket/o")
        assertThat(listRequest.url.queryParameter("prefix"))
            .isEqualTo("chatui/backups/conversations/")
        assertThat(listRequest.url.queryParameter("maxResults")).isEqualTo("10")
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
            enqueueToken()
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
            adapter(
                endpoint = "http://127.0.0.1:1",
                tokenEndpoint = "http://127.0.0.1:1/token",
            ).testConnection()
            null
        } catch (failure: StorageError) {
            failure
        }

        assertThat(error).isNotNull()
        assertThat(error!!.code).isEqualTo(StorageErrorCode.NETWORK)
    }

    @Test
    fun `putObject sends only the provided encrypted bytes`(): Unit = runBlocking {
        val encrypted = byteArrayOf(
            0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a,
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
        )
        enqueueToken()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"generation":"1","updated":"2026-01-01T00:00:00Z"}""")
                .build(),
        )

        adapter().putObject("manifest.enc", encrypted)

        server.takeRequest()
        val uploadRequest = server.takeRequest()
        assertThat(uploadRequest.body!!.toByteArray()).isEqualTo(encrypted)
    }

    @Test
    fun `builds object and media URLs`() {
        val adapter = adapterForUrl()
        assertThat(adapter.metadataUrl("conversations/a b.enc").toString())
            .isEqualTo(
                "https://storage.googleapis.com/storage/v1/b/test-bucket/o/" +
                    "conversations%2Fa%20b.enc",
            )
        assertThat(adapter.mediaUrl("manifest.enc").toString())
            .isEqualTo(
                "https://storage.googleapis.com/storage/v1/b/test-bucket/o/" +
                    "manifest.enc?alt=media",
            )
    }

    private fun enqueueToken() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"access_token":"test-token","expires_in":3600}""")
                .build(),
        )
    }

    private fun adapter(
        prefix: String? = null,
        retryDelayMs: Long = 0,
        endpoint: String = server.url("/").toString().trimEnd('/'),
        tokenEndpoint: String = server.url("/token").toString(),
    ): GcsStorageAdapter = GcsStorageAdapter(
        GcsStorageConfig(
            serviceAccountJson = serviceAccountJson(),
            bucket = "test-bucket",
            prefix = prefix,
            endpoint = endpoint,
            tokenEndpoint = tokenEndpoint,
            client = client,
            now = { Instant.parse("2026-01-01T00:00:00Z") },
            retryDelayMs = retryDelayMs,
        ),
    )

    private fun adapterForUrl(): GcsStorageAdapter = GcsStorageAdapter(
        GcsStorageConfig(
            serviceAccountJson = serviceAccountJson(),
            bucket = "test-bucket",
            client = client,
        ),
    )

    private fun serviceAccountJson(): String {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray())
                .encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val escaped = pem.replace("\\", "\\\\").replace("\n", "\\n")
        return """{"client_email":"test@example.iam.gserviceaccount.com",""" +
            """"private_key":"$escaped",""" +
            """"token_uri":"https://oauth2.googleapis.com/token"}"""
    }
}
