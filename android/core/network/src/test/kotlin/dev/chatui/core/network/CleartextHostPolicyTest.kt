package dev.chatui.core.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.InetAddress
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class CleartextHostPolicyTest {
    @Test
    fun `allows loopback and emulator literals`() {
        val allowed = listOf(
            "localhost",
            "LOCALHOST",
            "localhost.",
            "127.0.0.1",
            "127.1.2.3",
            "::1",
            "[::1]",
            "10.0.2.2",
        )

        allowed.forEach { host ->
            assertThat(CleartextHostPolicy.isAllowedCleartextHost(host)).isTrue()
        }
    }

    @Test
    fun `allows RFC1918 IPv4 ranges`() {
        val allowed = listOf(
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.1",
            "192.168.255.255",
        )

        allowed.forEach { host ->
            assertThat(CleartextHostPolicy.isAllowedCleartextHost(host)).isTrue()
        }
    }

    @Test
    fun `allows IPv6 unique-local and link-local ranges`() {
        val allowed = listOf(
            "fc00::1",
            "fd12:3456:789a::1",
            "fe80::1",
            "febf::1",
        )

        allowed.forEach { host ->
            assertThat(CleartextHostPolicy.isAllowedCleartextHost(host)).isTrue()
        }
    }

    @Test
    fun `allows mDNS local names`() {
        assertThat(CleartextHostPolicy.isAllowedCleartextHost("printer.local")).isTrue()
        assertThat(CleartextHostPolicy.isAllowedCleartextHost("OLLAMA.LOCAL")).isTrue()
    }

    @Test
    fun `refuses public and out-of-range hosts`() {
        val denied = listOf(
            "",
            "example.com",
            "localhost.evil.com",
            "evil.local.example.com",
            "8.8.8.8",
            "1.1.1.1",
            "100.64.0.1",
            "169.254.1.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.169.0.1",
            "2001:db8::1",
            "fec0::1",
        )

        denied.forEach { host ->
            assertThat(CleartextHostPolicy.isAllowedCleartextHost(host)).isFalse()
        }
    }

    @Test
    fun `https is always allowed and non-http schemes are refused`() {
        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest("https", "example.com"),
        ).isTrue()
        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest("http", "example.com"),
        ).isFalse()
        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest("ftp", "localhost"),
        ).isFalse()
    }

    @Test
    fun `local names are checked against their resolved addresses`() {
        val public = listOf(InetAddress.getByName("93.184.216.34"))
        val private = listOf(InetAddress.getByName("192.168.1.20"))

        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest(
                scheme = "http",
                host = "printer.local",
                resolver = { public },
            ),
        ).isFalse()

        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest(
                scheme = "http",
                host = "printer.local",
                resolver = { private },
            ),
        ).isTrue()
    }

    @Test
    fun `localhost is checked against its resolved addresses`() {
        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest(
                scheme = "http",
                host = "localhost",
                resolver = { listOf(InetAddress.getByName("93.184.216.34")) },
            ),
        ).isFalse()

        assertThat(
            CleartextHostPolicy.isAllowedCleartextRequest(
                scheme = "http",
                host = "localhost",
                resolver = { listOf(InetAddress.getLoopbackAddress()) },
            ),
        ).isTrue()
    }

    @Test
    fun `literal addresses are not resolved`() {
        val result = CleartextHostPolicy.isAllowedCleartextRequest(
            scheme = "http",
            host = "10.0.2.2",
            resolver = { error("literal addresses must not be resolved") },
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `interceptor refuses public cleartext before the request is sent`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                CleartextPolicyInterceptor {
                    listOf(InetAddress.getByName("93.184.216.34"))
                },
            )
            .build()

        val request = Request.Builder().url("http://example.com/").build()

        val error = try {
            client.newCall(request).execute()
            null
        } catch (thrown: IOException) {
            thrown
        }

        assertThat(error).isInstanceOf(CleartextNotAllowedException::class.java)
    }

    @Test
    fun `interceptor allows loopback cleartext`() {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        server.start()

        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(
                    CleartextPolicyInterceptor {
                        listOf(InetAddress.getLoopbackAddress())
                    },
                )
                .build()

            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertThat(it.code).isEqualTo(200)
            }
        } finally {
            server.close()
        }
    }
}
