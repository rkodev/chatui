package dev.chatui.core.providers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BaseUrlSecurityTest {
    @Test
    fun `https is secure and has no cleartext warning`() {
        val result = assessBaseUrlSecurity("https://api.example.com/v1")

        assertThat(result).isInstanceOf(BaseUrlSecurity.SecureHttps::class.java)
        assertThat((result as BaseUrlSecurity.SecureHttps).url)
            .isEqualTo("https://api.example.com/v1")
    }

    @Test
    fun `approved local http hosts produce a warning`() {
        val allowed = listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:1234/v1",
            "http://[::1]:8080/v1",
            "http://10.0.2.2:11434/v1",
            "http://192.168.1.20:8080/v1",
            "http://ollama.local:11434/v1",
        )

        allowed.forEach { url ->
            val result = assessBaseUrlSecurity(url)

            assertThat(result).isInstanceOf(BaseUrlSecurity.AllowedLocalCleartext::class.java)
            assertThat((result as BaseUrlSecurity.AllowedLocalCleartext).warning)
                .isEqualTo(CLEARTEXT_LOCAL_WARNING)
        }
    }

    @Test
    fun `public cleartext is refused with actionable copy`() {
        val refused = listOf(
            "http://api.example.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
        )

        refused.forEach { url ->
            val result = assessBaseUrlSecurity(url)

            assertThat(result).isInstanceOf(BaseUrlSecurity.RefusedCleartext::class.java)
            assertThat((result as BaseUrlSecurity.RefusedCleartext).error)
                .isEqualTo(CLEARTEXT_REFUSED_ERROR)
        }
    }

    @Test
    fun `invalid and non-http schemes are refused`() {
        val refused = listOf(
            "",
            "not a url",
            "ftp://localhost/v1",
            "http://",
        )

        refused.forEach { url ->
            assertThat(assessBaseUrlSecurity(url))
                .isInstanceOf(BaseUrlSecurity.RefusedCleartext::class.java)
        }
    }

    @Test
    fun `warning and error copy contain no secrets`() {
        assertThat(CLEARTEXT_LOCAL_WARNING).doesNotContain("sk-")
        assertThat(CLEARTEXT_REFUSED_ERROR).doesNotContain("sk-")
        assertThat(CLEARTEXT_LOCAL_WARNING).doesNotContain("Bearer")
        assertThat(CLEARTEXT_REFUSED_ERROR).doesNotContain("Bearer")
    }
}
