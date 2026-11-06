package dev.chatui.feature.conversations.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.ChatUiDatabase
import dev.chatui.core.database.attachment.AttachmentStorage
import dev.chatui.core.database.export.ExportReader
import dev.chatui.core.database.export.ExportSettingsSource
import dev.chatui.core.database.import.ImportMode
import dev.chatui.core.database.repository.AttachmentRepository
import dev.chatui.core.database.repository.ConversationRepository
import dev.chatui.core.database.repository.MessageRepository
import dev.chatui.core.database.repository.ModelRepository
import dev.chatui.core.database.repository.ProviderRepository
import dev.chatui.core.datastore.settings.AndroidSettings
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ChatFontSize
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Density
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.SendKey
import dev.chatui.core.model.Theme
import dev.chatui.feature.conversations.TEST_NOW
import dev.chatui.feature.conversations.export.AttachmentStorageByteReader
import dev.chatui.feature.conversations.export.ChatExport
import dev.chatui.feature.conversations.export.EXPORT_APP_VERSION
import dev.chatui.feature.conversations.export.EXPORT_FORMAT
import dev.chatui.feature.conversations.export.EXPORT_FORMAT_VERSION
import dev.chatui.feature.conversations.export.ExportAttachment
import dev.chatui.feature.conversations.export.ExportOptions
import dev.chatui.feature.conversations.export.ExportService
import dev.chatui.feature.conversations.export.encodeArchive
import dev.chatui.feature.conversations.testAttachment
import dev.chatui.feature.conversations.testConversation
import dev.chatui.feature.conversations.testMessage
import dev.chatui.feature.conversations.testModel
import dev.chatui.feature.conversations.testProvider
import dev.chatui.feature.conversations.testSettings
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: ChatUiDatabase
    private lateinit var storage: AttachmentStorage
    private lateinit var settingsStore: FakeImportSettingsStore
    private lateinit var service: ImportService

    private val now = "2026-11-04T12:00:00.000Z"
    private val bytes = "hello".toByteArray()
    private val sha256 = sha256Hex(bytes)

    private val provider = testProvider(id = "provider-1")
    private val model = testModel(
        id = "model-1",
        providerId = "provider-1",
        remoteId = "remote-1",
    )
    private val conversation = testConversation(
        id = "conversation-1",
        title = "Imported chat",
    ).copy(
        providerId = "provider-1",
        modelId = "model-1",
        messageCount = 2,
    )
    private val messageOne = testMessage(
        id = "message-1",
        conversationId = "conversation-1",
        sequence = 0,
        text = "Describe this image.",
    ).copy(
        parts = listOf(
            ContentPart.Text("Describe this image."),
            ContentPart.Image("attachment-1", "image/png", "A single pixel."),
        ),
    )
    private val messageTwo = testMessage(
        id = "message-2",
        conversationId = "conversation-1",
        sequence = 1,
        role = MessageRole.ASSISTANT,
        text = "It is a single pixel.",
    ).copy(
        providerId = "provider-1",
        modelId = "model-1",
        modelLabel = "Model One",
    )
    private val attachment = testAttachment(
        id = "attachment-1",
        messageId = "message-1",
        kind = AttachmentKind.IMAGE,
        mimeType = "image/png",
        name = "pixel.png",
        size = bytes.size.toLong(),
        sha256 = sha256,
        storageKey = AttachmentStorage.storageKeyFor(sha256),
    )
    private val settings = testSettings(
        defaultProviderId = "provider-1",
        defaultModelId = "model-1",
        lastActiveConversationId = "conversation-1",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ChatUiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = AttachmentStorage(temporaryFolder.newFolder("attachments-root"))
        settingsStore = FakeImportSettingsStore()
        service = ImportService(
            database = db,
            attachmentStorage = storage,
            settingsStore = settingsStore,
            now = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `valid archive imports and round trips`() = runBlocking {
        val sourceDb = newDatabase()
        val sourceStorage = AttachmentStorage(temporaryFolder.newFolder("source-attachments"))
        try {
            seed(sourceDb, sourceStorage)
            val artifact = export(sourceDb, sourceStorage)

            val result = service.importText(artifact)

            assertThat(result.mode).isEqualTo(ImportMode.MERGE)
            assertThat(result.conversations).isEqualTo(1)
            assertThat(result.messages).isEqualTo(2)
            assertThat(result.attachments).isEqualTo(1)
            assertThat(result.providers).isEqualTo(1)
            assertThat(result.models).isEqualTo(1)
            assertThat(result.attachmentBytes).isEqualTo(1)

            assertThat(db.providerDao().getById("provider-1")).isNotNull()
            assertThat(db.modelDao().getById("model-1")).isNotNull()
            assertThat(db.conversationDao().getById("conversation-1")).isNotNull()
            assertThat(db.messageDao().getById("message-1")).isNotNull()
            assertThat(db.messageDao().getById("message-2")).isNotNull()
            assertThat(db.attachmentDao().getById("attachment-1")).isNotNull()
            assertThat(storage.read(AttachmentStorage.storageKeyFor(sha256))).isEqualTo(bytes)
        } finally {
            sourceDb.close()
        }
    }

    @Test
    fun `merge is default and idempotent`() = runBlocking {
        seedLocalData()
        val archive = archiveJson()

        service.importText(archive)
        val firstCounts = counts()

        service.importText(archive)

        assertThat(counts()).isEqualTo(firstCounts)
        assertThat(db.conversationDao().getById("local-conversation")).isNotNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNotNull()
    }

    @Test
    fun `replace is explicit and clears local data`() = runBlocking {
        seedLocalData()
        val oldBytes = "old".toByteArray()
        val oldSha = sha256Hex(oldBytes)
        val oldStorageKey = AttachmentStorage.storageKeyFor(oldSha)
        storage.put(oldBytes, oldSha)
        settingsStore.settings = AndroidSettings(
            theme = Theme.DARK,
            density = Density.COMPACT,
            chatFontSize = ChatFontSize.LG,
            sendKey = SendKey.MOD_ENTER,
            defaultProviderId = "local-provider",
            defaultModelId = "local-model",
            lastActiveConversationId = "local-conversation",
            onboardingCompleted = false,
        )

        service.importText(archiveJson(), ImportOptions(mode = ImportMode.REPLACE))

        assertThat(db.conversationDao().getById("local-conversation")).isNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNotNull()
        assertThat(db.attachmentDao().getById("local-attachment")).isNull()
        assertThat(db.attachmentDao().getById("attachment-1")).isNotNull()
        assertThat(storage.read(oldStorageKey)).isNull()

        assertThat(settingsStore.settings.theme).isEqualTo(Theme.SYSTEM)
        assertThat(settingsStore.settings.density).isEqualTo(Density.COMFORTABLE)
        assertThat(settingsStore.settings.chatFontSize).isEqualTo(ChatFontSize.MD)
        assertThat(settingsStore.settings.sendKey).isEqualTo(SendKey.ENTER)
        assertThat(settingsStore.settings.defaultProviderId).isEqualTo("provider-1")
        assertThat(settingsStore.settings.defaultModelId).isEqualTo("model-1")
        assertThat(settingsStore.settings.lastActiveConversationId).isEqualTo("conversation-1")
        assertThat(settingsStore.settings.onboardingCompleted).isTrue()
    }

    @Test
    fun `merge leaves local settings untouched`() = runBlocking {
        val local = AndroidSettings(
            theme = Theme.DARK,
            defaultProviderId = "local-provider",
            defaultModelId = "local-model",
            lastActiveConversationId = "local-conversation",
        )
        settingsStore.settings = local

        service.importText(archiveJson())

        assertThat(settingsStore.settings).isEqualTo(local)
    }

    @Test
    fun `invalid JSON does not mutate data`() = runBlocking {
        seedLocalData()

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText("{") }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.INVALID_JSON)
        assertLocalDataUnchanged()
    }

    @Test
    fun `invalid schema does not mutate data`() = runBlocking {
        seedLocalData()

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText("""{"format":"wrong"}""") }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.SCHEMA)
        assertLocalDataUnchanged()
    }

    @Test
    fun `oversized archive is rejected before parsing`() = runBlocking {
        seedLocalData()
        val smallService = ImportService(
            database = db,
            attachmentStorage = storage,
            settingsStore = settingsStore,
            now = { now },
            maxBytes = 16,
        )

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { smallService.importText("x".repeat(17)) }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.TOO_LARGE)
        assertLocalDataUnchanged()
    }

    @Test
    fun `oversized attachment is rejected`() = runBlocking {
        seedLocalData()
        val smallService = ImportService(
            database = db,
            attachmentStorage = storage,
            settingsStore = settingsStore,
            now = { now },
            maxAttachmentBytes = 4,
        )

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { smallService.importText(archiveJson()) }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.TOO_LARGE)
        assertLocalDataUnchanged()
    }

    @Test
    fun `duplicate ids are rejected`() = runBlocking {
        seedLocalData()
        val duplicate = archive().copy(
            conversations = listOf(conversation, conversation),
        )

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText(encodeArchive(duplicate)) }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.DUPLICATE_ID)
        assertLocalDataUnchanged()
    }

    @Test
    fun `unsafe attachment names are rejected`() = runBlocking {
        seedLocalData()
        val unsafe = archive().copy(
            attachments = listOf(
                archive().attachments.single().copy(name = "../evil.png"),
            ),
        )

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText(encodeArchive(unsafe)) }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.UNSAFE_ATTACHMENT_NAME)
        assertLocalDataUnchanged()
    }

    @Test
    fun `dangling references are rejected`() = runBlocking {
        seedLocalData()
        val dangling = archive().copy(
            conversations = listOf(conversation.copy(providerId = "missing-provider")),
        )

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText(encodeArchive(dangling)) }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.INVALID_REFERENCE)
        assertLocalDataUnchanged()
    }

    @Test
    fun `invalid attachment bytes are rejected`() = runBlocking {
        seedLocalData()
        val invalidBase64 = archive().copy(
            attachments = listOf(
                archive().attachments.single().copy(dataBase64 = "not-base64"),
            ),
        )

        val base64Error = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText(encodeArchive(invalidBase64)) }
        }
        assertThat(base64Error.code).isEqualTo(ImportErrorCode.INVALID_ATTACHMENT)

        val wrongSize = archive().copy(
            attachments = listOf(
                archive().attachments.single().copy(size = 999),
            ),
        )
        val sizeError = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText(encodeArchive(wrongSize)) }
        }
        assertThat(sizeError.code).isEqualTo(ImportErrorCode.INVALID_ATTACHMENT)

        val wrongHash = archive().copy(
            attachments = listOf(
                archive().attachments.single().copy(sha256 = "a".repeat(64)),
            ),
        )
        val hashError = assertThrows(ImportValidationError::class.java) {
            runBlocking { service.importText(encodeArchive(wrongHash)) }
        }
        assertThat(hashError.code).isEqualTo(ImportErrorCode.INVALID_ATTACHMENT)

        assertLocalDataUnchanged()
    }

    @Test
    fun `model ids are remapped to an existing local model`() = runBlocking {
        val localProvider = testProvider(id = "provider-1")
        val localModel = testModel(
            id = "local-model",
            providerId = "provider-1",
            remoteId = "remote-1",
        )
        ProviderRepository(db.providerDao()).upsert(localProvider)
        ModelRepository(db.modelDao()).upsert(localModel)

        service.importText(archiveJson())

        assertThat(db.modelDao().all().map { it.id }).containsExactly("local-model")
        assertThat(db.conversationDao().getById("conversation-1")!!.modelId)
            .isEqualTo("local-model")
        assertThat(db.messageDao().getById("message-2")!!.modelId)
            .isEqualTo("local-model")
    }

    @Test
    fun `attachment metadata and bytes are restored`() = runBlocking {
        service.importText(archiveJson())

        val stored = db.attachmentDao().getById("attachment-1")!!
        assertThat(stored.messageId).isEqualTo("message-1")
        assertThat(stored.kind).isEqualTo("image")
        assertThat(stored.mimeType).isEqualTo("image/png")
        assertThat(stored.name).isEqualTo("pixel.png")
        assertThat(stored.size).isEqualTo(bytes.size.toLong())
        assertThat(stored.sha256).isEqualTo(sha256)
        assertThat(stored.storageKey).isEqualTo(AttachmentStorage.storageKeyFor(sha256))
        assertThat(stored.createdAt).isEqualTo(TEST_NOW.toString())
        assertThat(storage.read(stored.storageKey)).isEqualTo(bytes)
    }

    @Test
    fun `ids order snapshots and attachment references are preserved`() = runBlocking {
        service.importText(archiveJson())

        val storedConversation = db.conversationDao().getById("conversation-1")!!
        assertThat(storedConversation.title).isEqualTo("Imported chat")
        assertThat(storedConversation.providerId).isEqualTo("provider-1")
        assertThat(storedConversation.modelId).isEqualTo("model-1")
        assertThat(storedConversation.messageCount).isEqualTo(2)

        val messages = db.messageDao().allForConversation("conversation-1")
        assertThat(messages.map { it.id }).containsExactly("message-1", "message-2").inOrder()
        assertThat(messages.map { it.sequence }).containsExactly(0L, 1L).inOrder()

        val assistant = db.messageDao().getById("message-2")!!
        assertThat(assistant.providerId).isEqualTo("provider-1")
        assertThat(assistant.modelId).isEqualTo("model-1")
        assertThat(assistant.modelLabel).isEqualTo("Model One")

        val user = db.messageDao().getById("message-1")!!
        assertThat(user.partsJson).contains("attachment-1")
    }

    private suspend fun seedLocalData() {
        ProviderRepository(db.providerDao()).upsert(
            testProvider(id = "local-provider"),
        )
        ModelRepository(db.modelDao()).upsert(
            testModel(
                id = "local-model",
                providerId = "local-provider",
                remoteId = "local-remote",
            ),
        )
        ConversationRepository(db.conversationDao()).create(
            testConversation(id = "local-conversation").copy(
                providerId = "local-provider",
                modelId = "local-model",
            ),
        )
        MessageRepository(db.messageDao()).appendMessage(
            testMessage(
                id = "local-message",
                conversationId = "local-conversation",
                sequence = 0,
            ),
        )
        AttachmentRepository(db.attachmentDao()).upsert(
            testAttachment(
                id = "local-attachment",
                messageId = "local-message",
                sha256 = "b".repeat(64),
                storageKey = AttachmentStorage.storageKeyFor("b".repeat(64)),
            ),
        )
    }

    private suspend fun assertLocalDataUnchanged() {
        assertThat(db.conversationDao().getById("local-conversation")).isNotNull()
        assertThat(db.messageDao().getById("local-message")).isNotNull()
        assertThat(db.attachmentDao().getById("local-attachment")).isNotNull()
        assertThat(db.providerDao().getById("local-provider")).isNotNull()
        assertThat(db.modelDao().getById("local-model")).isNotNull()
        assertThat(db.conversationDao().getById("conversation-1")).isNull()
    }

    private suspend fun counts(): Map<String, Int> = mapOf(
        "conversations" to db.conversationDao().all().size,
        "messages" to db.messageDao().all().size,
        "attachments" to db.attachmentDao().all().size,
        "providers" to db.providerDao().observeAll().first().size,
        "models" to db.modelDao().all().size,
    )

    private suspend fun seed(database: ChatUiDatabase, attachmentStorage: AttachmentStorage) {
        ProviderRepository(database.providerDao()).upsert(provider)
        ModelRepository(database.modelDao()).upsert(model)
        ConversationRepository(database.conversationDao()).create(conversation)
        MessageRepository(database.messageDao()).appendMessage(messageOne)
        MessageRepository(database.messageDao()).appendMessage(messageTwo)
        AttachmentRepository(database.attachmentDao()).upsert(attachment)
        attachmentStorage.put(bytes, sha256)
    }

    private suspend fun export(
        database: ChatUiDatabase,
        attachmentStorage: AttachmentStorage,
    ): String {
        val reader = ExportReader(
            conversations = ConversationRepository(database.conversationDao()),
            messages = MessageRepository(database.messageDao()),
            attachments = AttachmentRepository(database.attachmentDao()),
            providers = ProviderRepository(database.providerDao()),
            models = ModelRepository(database.modelDao()),
            settings = ExportSettingsSource { settings },
        )
        val exportService = ExportService(
            reader = reader,
            attachmentBytes = AttachmentStorageByteReader(attachmentStorage),
            now = { now },
        )

        return exportService.exportAll(
            ExportOptions(includeAttachmentBytes = true),
        ).content
    }

    private fun archive(): ChatExport = ChatExport(
        format = EXPORT_FORMAT,
        formatVersion = EXPORT_FORMAT_VERSION,
        exportedAt = now,
        appVersion = EXPORT_APP_VERSION,
        conversations = listOf(conversation),
        messages = listOf(messageOne, messageTwo),
        attachments = listOf(
            ExportAttachment(
                id = attachment.id,
                name = attachment.name,
                mimeType = attachment.mimeType,
                sha256 = attachment.sha256,
                size = attachment.size,
                dataBase64 = Base64.getEncoder().encodeToString(bytes),
            ),
        ),
        providers = listOf(provider),
        models = listOf(model),
        settings = settings,
    )

    private fun archiveJson(): String = encodeArchive(archive())

    private fun newDatabase(): ChatUiDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, ChatUiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private class FakeImportSettingsStore(
        var settings: AndroidSettings = AndroidSettings(),
    ) : ImportSettingsStore {
        override suspend fun current(): AndroidSettings = settings

        override suspend fun save(settings: AndroidSettings) {
            this.settings = settings
        }
    }

    private companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
