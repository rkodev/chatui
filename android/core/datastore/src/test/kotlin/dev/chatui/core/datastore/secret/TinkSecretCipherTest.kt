package dev.chatui.core.datastore.secret

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TinkSecretCipherTest {
    @Test
    fun `encrypt and decrypt round trip`() {
        val cipher = inMemorySecretCipher()
        val plaintext = "sk-test-123"

        assertThat(cipher.decrypt(cipher.encrypt(plaintext))).isEqualTo(plaintext)
    }

    @Test
    fun `ciphertext differs from plaintext`() {
        val cipher = inMemorySecretCipher()
        val plaintext = "sk-test-123"

        val ciphertext = cipher.encrypt(plaintext)

        assertThat(ciphertext).isNotEqualTo(plaintext)
        assertThat(ciphertext).doesNotContain(plaintext)
    }

    @Test
    fun `wrong keyset fails cleanly`() {
        val plaintext = "sk-test-123"
        val ciphertext = inMemorySecretCipher().encrypt(plaintext)

        val error = runCatching { inMemorySecretCipher().decrypt(ciphertext) }.exceptionOrNull()

        assertThat(error).isInstanceOf(SecretCipherException::class.java)
        assertThat(error?.message).doesNotContain(plaintext)
    }

    @Test
    fun `invalid base64 fails cleanly`() {
        val error = runCatching { inMemorySecretCipher().decrypt("not-base64") }.exceptionOrNull()

        assertThat(error).isInstanceOf(SecretCipherException::class.java)
    }
}
