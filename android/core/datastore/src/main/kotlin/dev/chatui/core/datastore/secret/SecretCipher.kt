package dev.chatui.core.datastore.secret

/**
 * Encrypts and decrypts secret values.
 *
 * Implementations must never log plaintext, include plaintext in exception
 * messages, or expose key material. Production uses Tink with an Android
 * Keystore-backed keyset; tests inject an in-memory Tink keyset.
 */
interface SecretCipher {
    /** Encrypts [plaintext] and returns a self-contained ciphertext envelope. */
    fun encrypt(plaintext: String): String

    /** Decrypts a ciphertext envelope produced by [encrypt]. */
    fun decrypt(ciphertext: String): String
}

/**
 * Raised when a secret cannot be encrypted or decrypted.
 *
 * The message never contains plaintext or key material so it is safe to log.
 */
class SecretCipherException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
