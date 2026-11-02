package dev.chatui.core.datastore.secret

import android.content.Context
import dev.chatui.core.database.dao.SecretDao
import dev.chatui.core.database.entity.SecretEntity
import java.time.Instant

/**
 * Ciphertext-only secret storage backed by [SecretDao].
 *
 * Plaintext exists only in memory for the duration of a call. The DAO stores
 * only the ciphertext envelope produced by [SecretCipher]; no plaintext is
 * written to Room, DataStore, logs, or URLs.
 */
class SecretStore(
    private val dao: SecretDao,
    private val cipher: SecretCipher,
    private val clock: () -> Instant = Instant::now,
) {
    /** Encrypts [plaintext] and stores it under [id], replacing any existing value. */
    suspend fun put(id: String, plaintext: String) {
        require(id.isNotBlank()) { "Secret id must not be blank" }
        val now = clock().toString()
        val existing = dao.getById(id)
        dao.upsert(
            SecretEntity(
                id = id,
                ciphertext = cipher.encrypt(plaintext),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    /** Returns the decrypted secret, or `null` when [id] is not stored. */
    suspend fun get(id: String): String? {
        val entity = dao.getById(id) ?: return null
        return cipher.decrypt(entity.ciphertext)
    }

    /** Deletes the secret stored under [id]. */
    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    /** Forgets one stored key. */
    suspend fun forgetKey(id: String) {
        delete(id)
    }

    /** Forgets every stored key. */
    suspend fun forgetAll() {
        dao.deleteAll()
    }

    companion object {
        /** Creates a production store using the Android Keystore-backed cipher. */
        fun create(context: Context, dao: SecretDao): SecretStore =
            SecretStore(dao = dao, cipher = TinkSecretCipher.androidKeystore(context))
    }
}
