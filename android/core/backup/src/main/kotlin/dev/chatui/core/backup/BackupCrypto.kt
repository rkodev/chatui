package dev.chatui.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * JDK crypto primitives for the passphrase-derived backup envelope.
 *
 * This deliberately does not use Tink. The Tink keyset in `core:datastore` is
 * device-bound and must never be reused for backups that need to be restored on
 * another device.
 */
object BackupCrypto {
    const val PBKDF2_ITERATIONS: Int = 600_000
    const val SALT_BYTES: Int = 16
    const val KEY_BYTES: Int = 32
    const val IV_BYTES: Int = 12
    const val TAG_BITS: Int = 128
    const val TAG_BYTES: Int = 16

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"

    private val secureRandom = SecureRandom()

    /** Derives a 32-byte AES key with PBKDF2-HMAC-SHA256. */
    fun deriveKey(
        passphrase: String,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): ByteArray {
        if (salt.size != SALT_BYTES) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.INVALID_HEADER,
                "Salt must be $SALT_BYTES bytes",
            )
        }
        if (iterations <= 0) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.INVALID_HEADER,
                "PBKDF2 iterations must be positive",
            )
        }

        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } catch (exception: GeneralSecurityException) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.INVALID_HEADER,
                "Unable to derive the backup key",
                exception,
            )
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypts with AES-256-GCM and returns `ciphertext || tag`. */
    fun encrypt(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        if (iv.size != IV_BYTES) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.MALFORMED_ENVELOPE,
                "Envelope IV must be $IV_BYTES bytes",
            )
        }
        return try {
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(plaintext)
        } catch (exception: GeneralSecurityException) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.AUTHENTICATION_FAILED,
                "Unable to encrypt the backup object",
                exception,
            )
        }
    }

    /** Decrypts `ciphertext || tag` with AES-256-GCM. */
    fun decrypt(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        if (iv.size != IV_BYTES) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.MALFORMED_ENVELOPE,
                "Envelope IV must be $IV_BYTES bytes",
            )
        }
        if (ciphertext.size < TAG_BYTES) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.MALFORMED_ENVELOPE,
                "Envelope ciphertext must include a $TAG_BYTES-byte tag",
            )
        }
        return try {
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, iv),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (exception: GeneralSecurityException) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.AUTHENTICATION_FAILED,
                "Envelope authentication failed",
                exception,
            )
        }
    }

    /** Returns a fresh cryptographically random byte array. */
    fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /** Gzip-compresses conversation payloads before encryption. */
    fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { stream -> stream.write(data) }
        return output.toByteArray()
    }

    /** Decompresses a gzip conversation payload after decryption. */
    fun gunzip(data: ByteArray): ByteArray {
        return try {
            GZIPInputStream(ByteArrayInputStream(data)).use { stream -> stream.readBytes() }
        } catch (exception: Exception) {
            throw BackupEnvelopeException(
                BackupEnvelopeErrorCode.DECOMPRESSION_FAILED,
                "Conversation payload is not valid gzip",
                exception,
            )
        }
    }
}
