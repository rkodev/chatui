package dev.chatui.core.datastore.settings

import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ChatParams
import dev.chatui.core.model.Density
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import kotlinx.serialization.json.JsonObject

/**
 * Non-secret settings persisted by Android.
 *
 * This mirrors the shared `Settings` fields that apply to Android. The
 * web-only `rememberKeysOnWeb` flag and the future `backup` configuration are
 * deliberately absent: backup configuration lives in Room and secrets never
 * enter DataStore.
 */
data class AndroidSettings(
    val theme: Theme = Theme.SYSTEM,
    val density: Density = Density.COMFORTABLE,
    val chatFontSize: ChatFontSize = ChatFontSize.MD,
    val sendKey: SendKey = SendKey.ENTER,
    val defaultProviderId: String? = null,
    val defaultModelId: String? = null,
    val defaultSystemPrompt: String = "",
    val defaultParams: ChatParams = DEFAULT_CHAT_PARAMS,
    val showTokenUsage: Boolean = false,
    val loadRemoteImages: Boolean = true,
    val lastActiveConversationId: String? = null,
    val onboardingCompleted: Boolean = false,
) {
    companion object {
        /**
         * Default chat parameters used until the user changes them.
         *
         * Streaming is on by default because every M0 provider contract is
         * streaming-first. No provider-specific options are set.
         */
        val DEFAULT_CHAT_PARAMS: ChatParams = ChatParams(
            temperature = null,
            topP = null,
            maxOutputTokens = null,
            stop = emptyList(),
            reasoningEffort = null,
            streaming = true,
            extra = JsonObject(emptyMap()),
        )
    }
}
