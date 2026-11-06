package dev.chatui.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chatui.core.model.BrowserCors
import dev.chatui.core.model.Provider
import dev.chatui.core.model.ProviderAuthType
import dev.chatui.core.model.ProviderKind
import dev.chatui.core.network.CleartextPolicyInterceptor
import dev.chatui.core.network.OllamaAdapter
import dev.chatui.core.network.ProviderContext
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device evidence for the M2 local-provider deferral.
 *
 * The emulator reaches the host loopback through `10.0.2.2`. The test is
 * skipped when no local Ollama server is listening, so it never claims a pass
 * without a server. It uses the production cleartext policy interceptor, so a
 * successful discovery proves both the policy and the local network path.
 */
@RunWith(AndroidJUnit4::class)
class LocalProviderCleartextTest {
    @Test
    fun localOllamaPresetReachesHostServer() = runBlocking {
        assumeTrue(
            "No local Ollama server on 10.0.2.2:11434",
            canConnect(HOST, PORT),
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(CleartextPolicyInterceptor())
            .build()
        val adapter = OllamaAdapter(client)
        val now = Instant.now()
        val provider = Provider(
            id = "local-ollama",
            presetId = "ollama",
            label = "Local Ollama",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://$HOST:$PORT/v1",
            authType = ProviderAuthType.NONE,
            secretId = null,
            headers = emptyMap(),
            enabled = true,
            browserCors = BrowserCors.UNKNOWN,
            metadata = null,
            createdAt = now,
            updatedAt = now,
        )

        val models = adapter.listModels(ProviderContext(provider, apiKey = null))

        assertNotNull(models)
    }

    private fun canConnect(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 1_000)
        }
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val HOST = "10.0.2.2"
        const val PORT = 11434
    }
}
