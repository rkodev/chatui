package dev.chatui.shell

import dev.chatui.feature.settings.SettingsSection

/** Settings section routes nested under the settings destination. */
object SettingsDestination {
    const val APPEARANCE: String = "settings/appearance"
    const val CHAT_DEFAULTS: String = "settings/chat"
    const val DATA: String = "settings/data"
    const val BACKUP: String = "settings/backup"
    const val ABOUT: String = "settings/about"

    /** Route for a settings section, or the root settings route. */
    fun routeFor(section: SettingsSection): String = when (section) {
        SettingsSection.ROOT -> ShellDestination.Settings.route
        SettingsSection.APPEARANCE -> APPEARANCE
        SettingsSection.CHAT_DEFAULTS -> CHAT_DEFAULTS
        SettingsSection.DATA -> DATA
        SettingsSection.BACKUP -> BACKUP
        SettingsSection.ABOUT -> ABOUT
    }
}
