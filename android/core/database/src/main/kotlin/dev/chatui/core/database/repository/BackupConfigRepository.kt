package dev.chatui.core.database.repository

import dev.chatui.core.database.dao.BackupConfigDao
import dev.chatui.core.model.BackupConfig

/**
 * Read access to the future backup configuration.
 *
 * Export includes the durable backup shape so a later import can restore it.
 * Credentials are referenced by `credentialSecretId`; secret material never
 * enters this repository or an export.
 */
class BackupConfigRepository(
    private val dao: BackupConfigDao,
) {
    suspend fun get(): BackupConfig? =
        dao.getById(BackupConfigDao.DEFAULT_ID)?.toDomain()
}
