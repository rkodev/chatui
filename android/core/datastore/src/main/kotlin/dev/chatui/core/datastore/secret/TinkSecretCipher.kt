package dev.chatui.core.datastore.secret

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Tink AEAD AES-256-GCM implementation of [SecretCipher].
 *
 * Production callers use [androidKeystore], which stores the keyset in
 * SharedPreferences encrypted by an Android Keystore master key. The keyset is
 * never exported. Tests use [fromKeysetHandle] with an in-memory keyset.
 */
class TinkSecretCipher private constructor(
    private val aead: Aead,
) : SecretCipher {
    override fun encrypt(plaintext: String): String {
        return try {
            val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
            Base64.getEncoder().encodeToString(ciphertext)
        } catch (exception: GeneralSecurityException) {
            throw SecretCipherException("Unable to encrypt secret", exception)
        }
    }

    override fun decrypt(ciphertext: String): String {
        return try {
            val bytes = Base64.getDecoder().decode(ciphertext)
            String(aead.decrypt(bytes, ASSOCIATED_DATA), Charsets.UTF_8)
        } catch (exception: GeneralSecurityException) {
            throw SecretCipherException("Unable to decrypt secret", exception)
        } catch (exception: IllegalArgumentException) {
            throw SecretCipherException("Secret ciphertext is not valid base64", exception)
        }
    }

    companion object {
        /** SharedPreferences file that holds the encrypted keyset. */
        const val DEFAULT_PREFERENCES_NAME: String = "chatui_secret_keyset_prefs"

        /** Logical keyset name inside [DEFAULT_PREFERENCES_NAME]. */
        const val DEFAULT_KEYSET_NAME: String = "chatui_secret_keyset"

        /** Android Keystore master-key URI. */
        const val DEFAULT_MASTER_KEY_URI: String = "android-keystore://chatui_secret_master_key"

        private val ASSOCIATED_DATA: ByteArray = "chatui-secret-v1".toByteArray(Charsets.UTF_8)

        /**
         * Builds a cipher from an existing keyset handle.
         *
         * Tests pass a Tink in-memory keyset generated with
         * `KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)`.
         */
        fun fromKeysetHandle(keysetHandle: KeysetHandle): TinkSecretCipher {
            registerAead()
            return try {
                TinkSecretCipher(
                    keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java),
                )
            } catch (exception: GeneralSecurityException) {
                throw SecretCipherException("Unable to load Tink AEAD primitive", exception)
            }
        }

        /**
         * Production backend: a Tink keyset protected by an Android Keystore
         * master key. The keyset is persisted only as Keystore-encrypted
         * SharedPreferences and is never exported.
         */
        fun androidKeystore(
            context: Context,
            keysetName: String = DEFAULT_KEYSET_NAME,
            preferencesName: String = DEFAULT_PREFERENCES_NAME,
            masterKeyUri: String = DEFAULT_MASTER_KEY_URI,
        ): TinkSecretCipher {
            registerAead()
            return try {
                val keysetHandle = AndroidKeysetManager.Builder()
                    .withSharedPref(context, keysetName, preferencesName)
                    .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                    .withMasterKeyUri(masterKeyUri)
                    .build()
                    .keysetHandle
                fromKeysetHandle(keysetHandle)
            } catch (exception: GeneralSecurityException) {
                throw SecretCipherException("Unable to open Android Keystore keyset", exception)
            } catch (exception: IOException) {
                throw SecretCipherException("Unable to read Android Keystore keyset", exception)
            }
        }

        private fun registerAead() {
            try {
                AeadConfig.register()
            } catch (exception: GeneralSecurityException) {
                throw SecretCipherException("Unable to register Tink AEAD", exception)
            }
        }
    }
}
