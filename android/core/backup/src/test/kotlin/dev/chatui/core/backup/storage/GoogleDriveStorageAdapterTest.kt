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

class GoogleDriveStorageAdapterTest {
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
    fun `testConnection sends a Bearer token and lists files`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"files":[{"id":"file-1"}]}""")
                .build(),
        )

        adapter().testConnection()

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/drive/v3/files")
        assertThat(request.url.queryParameter("pageSize")).isEqualTo("1")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer test-token")
    }

    @Test
    fun `getObject finds the file and returns encrypted bytes`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x01)
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-1"))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(Buffer().write(encrypted))
                .build(),
        )

        val result = adapter().getObject("manifest.enc")

        assertThat(result.key).isEqualTo("manifest.enc")
        assertThat(result.bytes).isEqualTo(encrypted)
        assertThat(result.etag).isEqualTo("md5-1")

        val findRequest = server.takeRequest()
        assertThat(findRequest.url.queryParameter("q")).contains("name = 'manifest.enc'")
        val mediaRequest = server.takeRequest()
        assertThat(mediaRequest.url.encodedPath).isEqualTo("/drive/v3/files/file-1")
        assertThat(mediaRequest.url.queryParameter("alt")).isEqualTo("media")
    }

    @Test
    fun `putObject creates a new file with multipart upload`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x03)
        server.enqueue(emptyFind())
        server.enqueue(createResponse("file-1", "manifest.enc", "md5-new"))
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-new"))

        val result = adapter().putObject("manifest.enc", encrypted)

        assertThat(result.etag).isEqualTo("md5-new")

        server.takeRequest()
        val createRequest = server.takeRequest()
        assertThat(createRequest.method).isEqualTo("POST")
        assertThat(createRequest.url.encodedPath).isEqualTo("/upload/drive/v3/files")
        assertThat(createRequest.url.queryParameter("uploadType")).isEqualTo("multipart")
        val body = createRequest.body!!.toByteArray()
        assertThat(body.decodeToString()).contains("\"name\":\"manifest.enc\"")
        assertThat(body.containsSubArray(encrypted)).isTrue()

        val headRequest = server.takeRequest()
        assertThat(headRequest.url.queryParameter("q")).contains("manifest.enc")
    }

    @Test
    fun `putObject updates an existing file`(): Unit = runBlocking {
        val encrypted = byteArrayOf(0x43, 0x48, 0x41, 0x54, 0x55, 0x49, 0x31, 0x0a, 0x04)
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-old"))
        server.enqueue(createResponse("file-1", "manifest.enc", "md5-new"))
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-new"))

        val result = adapter().putObject(
            key = "manifest.enc",
            encryptedBytes = encrypted,
            options = PutObjectOptions(ifMatch = "md5-old"),
        )

        assertThat(result.etag).isEqualTo("md5-new")

        server.takeRequest()
        val updateRequest = server.takeRequest()
        assertThat(updateRequest.method).isEqualTo("PATCH")
        assertThat(updateRequest.url.encodedPath)
            .isEqualTo("/upload/drive/v3/files/file-1")
        assertThat(updateRequest.url.queryParameter("uploadType")).isEqualTo("media")
        assertThat(updateRequest.body!!.toByteArray()).isEqualTo(encrypted)
    }

    @Test
    fun `conditional write conflict returns a typed conflict error`(): Unit = runBlocking {
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-old"))

        val error = try {
            adapter().putObject(
                key = "manifest.enc",
                encryptedBytes = byteArrayOf(0x01),
                options = PutObjectOptions(ifMatch = "stale"),
            )
            null
        } catch (failure: StorageError) {
            failure
        }

        assertThat(error).isNotNull()
        assertThat(error!!.code).isEqualTo(StorageErrorCode.CONFLICT)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `create-only write conflicts when the file already exists`(): Unit = runBlocking {
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-old"))

        val error = try {
            adapter().putObject(
                key = "manifest.enc",
                encryptedBytes = byteArrayOf(0x01),
                options = PutObjectOptions(ifNoneMatch = "*"),
            )
            null
        } catch (failure: StorageError) {
            failure
        }

        assertThat(error).isNotNull()
        assertThat(error!!.code).isEqualTo(StorageErrorCode.CONFLICT)
    }

    @Test
    fun `headObject returns metadata without a body`(): Unit = runBlocking {
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-1"))

        val result = adapter().headObject("manifest.enc")

        assertThat(result.bytes).isEmpty()
        assertThat(result.etag).isEqualTo("md5-1")
        assertThat(server.takeRequest().method).isEqualTo("GET")
    }

    @Test
    fun `deleteObject finds and deletes the file`(): Unit = runBlocking {
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-1"))
        server.enqueue(MockResponse.Builder().code(204).build())

        adapter().deleteObject("manifest.enc")

        server.takeRequest()
        val deleteRequest = server.takeRequest()
        assertThat(deleteRequest.method).isEqualTo("DELETE")
        assertThat(deleteRequest.url.encodedPath).isEqualTo("/drive/v3/files/file-1")
    }

    @Test
    fun `listObjects parses files and filters by prefix`(): Unit = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"files":[""" +
                        """{"id":"1","name":"chatui/backups/manifest.enc",""" +
                        """"md5Checksum":"md5-1","modifiedTime":"2026-01-01T00:00:00Z"},""" +
                        """{"id":"2","name":"chatui/backups/conversations/abc.enc",""" +
                        """"md5Checksum":"md5-2"}""" +
                        """],"nextPageToken":"next"}""",
                )
                .build(),
        )

        val result = adapter(prefix = "chatui/backups").listObjects(
            ListObjectsOptions(prefix = "conversations/", maxKeys = 1),
        )

        assertThat(result.objects.map { it.key })
            .containsExactly("conversations/abc.enc")
        assertThat(result.objects[0].etag).isEqualTo("md5-2")
        assertThat(result.nextContinuationToken).isEqualTo("next")

        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/drive/v3/files")
        assertThat(request.url.queryParameter("q")).contains("'folder-1' in parents")
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
            adapter(
                endpoint = "http://127.0.0.1:1/drive/v3",
                uploadEndpoint = "http://127.0.0.1:1/upload/drive/v3",
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
        server.enqueue(emptyFind())
        server.enqueue(createResponse("file-1", "manifest.enc", "md5-new"))
        server.enqueue(findResponse("file-1", "manifest.enc", "md5-new"))

        adapter().putObject("manifest.enc", encrypted)

        server.takeRequest()
        val createRequest = server.takeRequest()
        assertThat(createRequest.body!!.toByteArray().containsSubArray(encrypted)).isTrue()
    }

    private fun adapter(
        prefix: String? = null,
        retryDelayMs: Long = 0,
        endpoint: String = server.url("/drive/v3").toString().trimEnd('/'),
        uploadEndpoint: String = server.url("/upload/drive/v3").toString().trimEnd('/'),
    ): GoogleDriveStorageAdapter = GoogleDriveStorageAdapter(
        GoogleDriveStorageConfig(
            accessToken = "test-token",
            folderId = "folder-1",
            prefix = prefix,
            endpoint = endpoint,
            uploadEndpoint = uploadEndpoint,
            client = client,
            retryDelayMs = retryDelayMs,
        ),
    )

    private fun findResponse(id: String, name: String, md5: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """{"files":[{"id":"$id","name":"$name","md5Checksum":"$md5",""" +
                    """"modifiedTime":"2026-01-01T00:00:00Z"}]}""",
            )
            .build()

    private fun emptyFind(): MockResponse =
        MockResponse.Builder().code(200).body("""{"files":[]}""").build()

    private fun createResponse(id: String, name: String, md5: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """{"id":"$id","name":"$name","md5Checksum":"$md5",""" +
                    """"modifiedTime":"2026-01-01T00:00:00Z"}""",
            )
            .build()
}

private fun ByteArray.containsSubArray(other: ByteArray): Boolean {
    if (other.isEmpty()) return true
    if (other.size > size) return false
    for (index in 0..size - other.size) {
        if (other.indices.all { this[index + it] == other[it] }) {
            return true
        }
    }
    return false
}
