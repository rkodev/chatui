package dev.chatui

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.datastore.secret.TinkSecretCipher
import dev.chatui.core.datastore.settings.SettingsDataStore
import java.io.File
import org.junit.Test

/**
 * Guards the backup-exclusion contract.
 *
 * The manifest must disable backup and point at both rule files, and the rule
 * files must explicitly exclude the database, DataStore, and secret keyset.
 */
class BackupRulesTest {
    private val mainDir = File("src/main")

    @Test
    fun `manifest disables backup and references both rule files`() {
        val manifest = File(mainDir, "AndroidManifest.xml").readText()

        assertThat(manifest).contains("android:allowBackup=\"false\"")
        assertThat(manifest).contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
        assertThat(manifest).contains("android:fullBackupContent=\"@xml/backup_rules\"")
    }

    @Test
    fun `full backup rules exclude database datastore and secret prefs`() {
        val rules = File(mainDir, "res/xml/backup_rules.xml").readText()

        assertThat(rules).contains("domain=\"database\" path=\"${ChatUiDatabase.NAME}\"")
        assertThat(rules).contains(
            "domain=\"file\" path=\"${SettingsDataStore.DATASTORE_DIRECTORY}/\"",
        )
        assertThat(rules).contains(
            "domain=\"sharedpref\" path=\"${TinkSecretCipher.DEFAULT_PREFERENCES_NAME}.xml\"",
        )
    }

    @Test
    fun `data extraction rules exclude database datastore and secret prefs`() {
        val rules = File(mainDir, "res/xml/data_extraction_rules.xml").readText()

        assertThat(rules).contains("<cloud-backup>")
        assertThat(rules).contains("<device-transfer>")
        assertThat(rules).contains("domain=\"database\" path=\"${ChatUiDatabase.NAME}\"")
        assertThat(rules).contains(
            "domain=\"file\" path=\"${SettingsDataStore.DATASTORE_DIRECTORY}/\"",
        )
        assertThat(rules).contains(
            "domain=\"sharedpref\" path=\"${TinkSecretCipher.DEFAULT_PREFERENCES_NAME}.xml\"",
        )
    }
}
