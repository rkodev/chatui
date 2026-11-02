package dev.chatui.core.datastore.secret

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates

/**
 * Test backend: a Tink AES-256-GCM keyset that exists only in memory.
 *
 * No Android Keystore or device is required. Each call creates a fresh keyset,
 * so two calls produce ciphers that cannot decrypt each other's ciphertext.
 */
internal fun inMemorySecretCipher(): TinkSecretCipher {
    AeadConfig.register()
    return TinkSecretCipher.fromKeysetHandle(
        KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM),
    )
}

/** Test backend that simulates a missing or unusable keyset. */
internal class FailingSecretCipher : SecretCipher {
    override fun encrypt(plaintext: String): String =
        throw SecretCipherException("Secret keyset is unavailable")

    override fun decrypt(ciphertext: String): String =
        throw SecretCipherException("Secret keyset is unavailable")
}
