package dev.chatui

import android.content.Context
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.feature.chat.ChatDependencies
import dev.chatui.feature.chat.InMemoryChatDrafts
import dev.chatui.testing.FakeChatContextResolver
import dev.chatui.testing.FakeChatConversationSource
import dev.chatui.testing.FakeChatMessageStore
import dev.chatui.testing.FakeProviderAdapter
import dev.chatui.testing.TEST_NOW
import dev.chatui.testing.testConversation
import java.time.Clock
import java.time.ZoneOffset

/**
 * Fake dependency bundle for the configuration-change lifecycle test.
 *
 * The test keeps the adapter and message store references so it can inspect
 * the same stream and rows after the activity is recreated.
 */
data class ConfigChangeHarness(
    val conversationId: String,
    val dependencies: ChatDependencies,
    val adapter: FakeProviderAdapter,
    val store: FakeChatMessageStore,
)

/** Build a fresh harness with a controlled stream and in-memory message store. */
fun createConfigChangeHarness(
    context: Context,
    conversationId: String = "conversation-config-change",
): ConfigChangeHarness {
    val adapter = FakeProviderAdapter()
    val store = FakeChatMessageStore()
    val conversation = testConversation(id = conversationId)

    return ConfigChangeHarness(
        conversationId = conversationId,
        dependencies = ChatDependencies(
            conversationSource = FakeChatConversationSource(conversation),
            messageStore = store,
            contextResolver = FakeChatContextResolver(),
            adapter = adapter,
            settings = SettingsDataStore.create(context),
            drafts = InMemoryChatDrafts(),
            clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
        ),
        adapter = adapter,
        store = store,
    )
}
