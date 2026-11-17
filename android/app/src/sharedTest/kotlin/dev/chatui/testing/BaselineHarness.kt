package dev.chatui.testing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentService
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.datastore.settings.SettingsDataStore
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.Theme
import dev.chatui.core.model.Density as UiDensity
import dev.chatui.feature.chat.ChatContextResult
import dev.chatui.feature.chat.ChatDependencies
import dev.chatui.feature.chat.ChatEntryPoint
import dev.chatui.feature.chat.ChatUnavailableReason
import dev.chatui.feature.chat.InMemoryChatDrafts
import dev.chatui.feature.chat.RepositoryChatAttachmentStore
import dev.chatui.feature.chat.RepositoryChatConversationSource
import dev.chatui.feature.chat.RepositoryChatMessageStore
import dev.chatui.feature.conversations.ConversationService
import dev.chatui.feature.conversations.DefaultConversationService
import dev.chatui.feature.conversations.export.ChatExport
import dev.chatui.feature.conversations.importer.DataStoreImportSettingsStore
import dev.chatui.feature.conversations.importer.ImportService
import dev.chatui.feature.conversations.importer.toAndroidSettings
import dev.chatui.shell.ChatUiApp
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Real Room/import/paging/attachment services with fake provider I/O and preferences. */
class BaselineHarness private constructor(
    val archive: ChatExport,
    val settings: AndroidSettings,
    val conversations: ConversationService,
    val messages: MessageRepository,
    val dependencies: ChatDependencies,
    val adapter: FakeProviderAdapter,
    val root: File,
    private val database: ChatUiDatabase,
) : AutoCloseable {
    override fun close() {
        database.close()
        root.deleteRecursively()
    }

    companion object {
        suspend fun create(
            context: Context,
            longHistory: Boolean = false,
            emptyHistory: Boolean = false,
            startConversationId: String? = null,
            missingConfiguration: Boolean = false,
            onDisk: Boolean = false,
        ): BaselineHarness {
            val root = Files.createTempDirectory(context.cacheDir.toPath(), "ux-baseline-").toFile()
            val database = if (onDisk) {
                ChatUiDatabase.builder(context, File(root, "baseline.db").absolutePath).build()
            } else {
                ChatUiDatabase.inMemory(context)
            }
            try {
                val archive = BaselineFixtures.archive(longHistory, emptyHistory)
                val settings = archive.settings.toAndroidSettings()
                    .copy(lastActiveConversationId = startConversationId)
                val settingsStore = SettingsDataStore(BaselinePreferences())
                settingsStore.save(settings)
                val storage = AttachmentStorage(root)
                ImportService(
                    database = database,
                    attachmentStorage = storage,
                    settingsStore = DataStoreImportSettingsStore(settingsStore),
                    now = { TEST_NOW.toString() },
                ).importText(BaselineFixtures.exportText(archive))

                val clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC)
                val conversations = ConversationRepository(database.conversationDao(), clock)
                val messages = MessageRepository(database.messageDao(), clock)
                val adapter = FakeProviderAdapter()
                val attachmentStore = RepositoryChatAttachmentStore(
                    AttachmentService(
                        repository = AttachmentRepository(database.attachmentDao()),
                        messages = messages,
                        storage = storage,
                        clock = clock,
                        newId = { "baseline-pending-image" },
                    ),
                )
                val contextResult = if (missingConfiguration) {
                    ChatContextResult.Unavailable(
                        reason = ChatUnavailableReason.NO_KEY,
                        message = "No synthetic key is configured. Add a key to continue.",
                        error = BaselineFixtures.recoverableError.copy(
                            code = ChatErrorCode.AUTH,
                            message = "No synthetic key is configured.",
                            retryable = false,
                        ),
                    )
                } else {
                    ChatContextResult.Ready(
                        testChatContext(
                            provider = BaselineFixtures.provider,
                            modelRemoteId = BaselineFixtures.models.first().remoteId,
                        ),
                    )
                }
                return BaselineHarness(
                    archive = archive,
                    settings = settings,
                    conversations = DefaultConversationService(
                        conversations = conversations,
                        messages = messages,
                        providers = ProviderRepository(database.providerDao(), clock),
                        models = ModelRepository(database.modelDao()),
                        settings = settingsStore,
                        clock = clock,
                    ),
                    messages = messages,
                    dependencies = ChatDependencies(
                        conversationSource = RepositoryChatConversationSource(conversations),
                        messageStore = RepositoryChatMessageStore(messages),
                        contextResolver = FakeChatContextResolver(contextResult),
                        adapter = adapter,
                        settings = settingsStore,
                        drafts = InMemoryChatDrafts(),
                        clock = clock,
                        attachmentStore = attachmentStore,
                        attachmentRoot = root,
                    ),
                    adapter = adapter,
                    root = root,
                    database = database,
                )
            } catch (error: Throwable) {
                database.close()
                root.deleteRecursively()
                throw error
            }
        }
    }
}

/**
 * Supply the existing Hilt entry point without replacing any production UI or
 * launching the real application's provider/startup services.
 */
@Composable
fun BaselineShell(
    harness: BaselineHarness,
    theme: Theme = Theme.LIGHT,
    fontScale: Float = 1f,
    densityMode: UiDensity = harness.settings.density,
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val context = LocalContext.current
    val application = remember(context, harness) {
        BaselineApplication(context.applicationContext, harness.dependencies)
    }
    val injectedContext = remember(context, application) {
        object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = application
        }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalContext provides injectedContext,
        LocalDensity provides Density(density.density, fontScale),
    ) {
        ChatUiApp(
            settings = harness.settings.copy(theme = theme, density = densityMode),
            conversationService = harness.conversations,
            chatDrafts = harness.dependencies.drafts,
            windowInsets = windowInsets,
        )
    }
}

private class BaselineApplication(
    context: Context,
    dependencies: ChatDependencies,
) : Application(), GeneratedComponentManager<GeneratedComponent> {
    init {
        attachBaseContext(context)
    }

    private val component = object : GeneratedComponent, ChatEntryPoint {
        override fun chatDependencies(): ChatDependencies = dependencies
    }

    override fun getApplicationContext(): Context = this

    // The route's existing no-manager fallback keeps fake streams independent of host networking.
    override fun getSystemService(name: String): Any? =
        if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)

    override fun generatedComponent(): GeneratedComponent = component
}

private class BaselinePreferences : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(data.value).also { data.value = it } }
}
