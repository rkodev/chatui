package dev.chatui.testing

import dev.chatui.core.model.ChatError
import dev.chatui.core.model.ChatErrorCode
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.Conversation
import dev.chatui.core.model.Density
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.InputModality
import dev.chatui.core.model.Message
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.core.model.Model
import dev.chatui.core.model.ModelCapability
import dev.chatui.core.model.ModelPricing
import dev.chatui.core.model.Settings
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.model.Theme
import dev.chatui.core.model.Usage
import dev.chatui.feature.conversations.export.ChatExport
import dev.chatui.feature.conversations.export.EXPORT_APP_VERSION
import dev.chatui.feature.conversations.export.EXPORT_FORMAT
import dev.chatui.feature.conversations.export.EXPORT_FORMAT_VERSION
import dev.chatui.feature.conversations.export.ExportAttachment
import dev.chatui.feature.conversations.export.ExportJson
import dev.chatui.feature.providers.ModelPickerTarget
import dev.chatui.feature.providers.ModelPickerUiState
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** UX-00.2's versioned, secret-free input shared by host and device tests. */
object BaselineFixtures {
    const val ID = "android-minimalist-ux-00.2-v1"
    const val EMPTY_CHAT = "019a5520-0000-7000-8000-000000000001"
    const val CONTENT_CHAT = "019a5520-0000-7000-8000-000000000002"
    const val ERROR_CHAT = "019a5520-0000-7000-8000-000000000003"
    const val IMAGE_CHAT = "019a5520-0000-7000-8000-000000000004"
    const val ARCHIVED_CHAT = "019a5520-0000-7000-8000-000000000005"
    const val LONG_CHAT = "019a5520-0000-7000-8000-000000000006"
    const val LONG_MESSAGE_COUNT = 10_000
    const val FIRST_EVENT_DELAY_MS = 250L
    const val EVENT_INTERVAL_MS = 32L

    private val historyStart = TEST_NOW.minusSeconds(365L * 86_400)

    val provider = testProvider(id = "baseline-provider").copy(
        presetId = "custom",
        label = "Offline fixture provider with a deliberately long label",
        baseUrl = "https://baseline.invalid/v1",
        secretId = null,
    )

    val models: List<Model> = listOf(
        testModel("baseline-text", provider.id, "fixture/text-free").copy(
            label = "Fixture Text Free",
            pinned = true,
            pricing = ModelPricing(inputPerMTok = 0.0, outputPerMTok = 0.0),
        ),
        testModel("baseline-vision", provider.id, "fixture/vision").copy(
            label = "Fixture Vision with a deliberately long model name",
            capabilities = listOf(ModelCapability.TEXT, ModelCapability.VISION),
            inputModalities = listOf(InputModality.TEXT, InputModality.IMAGE),
            pricing = ModelPricing(inputPerMTok = 0.5, outputPerMTok = 1.0),
        ),
        testModel("baseline-hidden", provider.id, "fixture/hidden").copy(
            label = "Fixture Hidden Model",
            hidden = true,
        ),
    )

    val recoverableError = ChatError(
        code = ChatErrorCode.NETWORK,
        message = "Synthetic connection interrupted. Your local history is safe; retry this answer.",
        providerId = provider.id,
        status = null,
        retryable = true,
        retryAfterSeconds = null,
        requestId = "fixture-request",
        details = buildJsonObject { put("fixture", "No network request was made.") },
        at = TEST_NOW,
    )

    val markdown: String = """
        ### Local baseline

        ```kotlin
        val count = 3
        println(count)
        ```

        | Item | State |
        | --- | --- |
        | Cedar | Ready |
        | Maple | Waiting |

        All names and content are invented.
    """.trimIndent()

    fun archive(longHistory: Boolean = false, emptyHistory: Boolean = false): ChatExport {
        require(!longHistory || !emptyHistory)
        val messages = when {
            emptyHistory -> emptyList()
            longHistory -> longMessages()
            else -> visualMessages()
        }
        val counts = messages.groupingBy { it.conversationId }.eachCount()
        val latest = messages.associate { it.conversationId to it.createdAt }
        val conversations = if (emptyHistory) emptyList() else listOf(
            conversation(
                CONTENT_CHAT,
                "Synthetic field notes with a deliberately long title that wraps on small windows",
            ).copy(pinned = true),
            conversation(EMPTY_CHAT, "Empty synthetic chat"),
            conversation(ERROR_CHAT, "Recoverable synthetic error"),
            conversation(IMAGE_CHAT, "Local supported images"),
            conversation(ARCHIVED_CHAT, "Archived synthetic conversation").copy(archived = true),
            conversation(LONG_CHAT, "10,000 synthetic messages"),
        ).map {
            it.copy(
                messageCount = counts[it.id] ?: 0,
                lastMessageAt = latest[it.id] ?: historyStart,
                updatedAt = latest[it.id] ?: historyStart,
            )
        }

        return ChatExport(
            format = EXPORT_FORMAT,
            formatVersion = EXPORT_FORMAT_VERSION,
            exportedAt = TEST_NOW.toString(),
            appVersion = EXPORT_APP_VERSION,
            conversations = conversations,
            messages = messages,
            attachments = imageAttachments(messages),
            providers = listOf(provider),
            models = models,
            settings = settings(),
        )
    }

    fun exportText(archive: ChatExport = archive()): String =
        ExportJson.encodeToString(ChatExport.serializer(), archive) + "\n"

    fun pickerState(includeHidden: Boolean = false): ModelPickerUiState = ModelPickerUiState(
        target = ModelPickerTarget.Manage(provider.id),
        models = models,
        providers = listOf(provider),
        selectedModelId = models.first().id,
        includeHidden = includeHidden,
        loading = false,
    )

    fun streamEvents(fail: Boolean = false): List<StreamEvent> = buildList {
        add(StreamEvent.Start("fixture-response"))
        add(StreamEvent.ReasoningDelta("Compare only the invented local rows."))
        markdown.chunked(12).forEach { add(StreamEvent.TextDelta(it)) }
        add(StreamEvent.UsageEvent(Usage(8, 12, 20, 0.0, false)))
        add(
            if (fail) StreamEvent.ErrorEvent(recoverableError)
            else StreamEvent.Finish(FinishReason.STOP),
        )
    }

    suspend fun playStream(adapter: FakeProviderAdapter, fail: Boolean = false) {
        delay(FIRST_EVENT_DELAY_MS)
        streamEvents(fail).forEachIndexed { index, event ->
            if (index > 0) delay(EVENT_INTERVAL_MS)
            adapter.events.send(event)
        }
        adapter.events.close()
    }

    fun streamRecipe(): String = buildJsonObject {
        put("fixture", ID)
        put("firstEventDelayMs", FIRST_EVENT_DELAY_MS)
        put("eventIntervalMs", EVENT_INTERVAL_MS)
        put("events", ExportJson.encodeToJsonElement(ListSerializer(StreamEvent.serializer()), streamEvents()))
        put(
            "recoverableFailureEvents",
            ExportJson.encodeToJsonElement(ListSerializer(StreamEvent.serializer()), streamEvents(fail = true)),
        )
    }.toString() + "\n"

    private fun conversation(id: String, title: String): Conversation =
        testConversation(id, provider.id, models.first().id).copy(
            title = title,
            modelLabel = models.first().label,
            // Keep relative-date labels stable without changing a reference device's clock.
            createdAt = historyStart,
            updatedAt = historyStart,
            lastMessageAt = historyStart,
        )

    private fun message(
        conversationId: String,
        sequence: Long,
        parts: List<ContentPart>,
    ): Message = testMessage(
        id = "baseline-$conversationId-$sequence",
        conversationId = conversationId,
        sequence = sequence,
        role = if (sequence % 2L == 1L) MessageRole.USER else MessageRole.ASSISTANT,
    ).copy(
        parts = parts,
        providerId = provider.id,
        modelId = models.first().id,
        modelLabel = models.first().label,
        createdAt = historyStart.plusSeconds(sequence),
        updatedAt = historyStart.plusSeconds(sequence),
    )

    private fun visualMessages(): List<Message> = listOf(
        message(CONTENT_CHAT, 1, listOf(ContentPart.Text("Summarize these invented field notes."))),
        message(
            CONTENT_CHAT,
            2,
            listOf(
                ContentPart.Reasoning("Compare the two synthetic rows before answering."),
                ContentPart.Text(markdown),
            ),
        ),
        message(ERROR_CHAT, 1, listOf(ContentPart.Text("Show a recoverable fixture error."))),
        message(ERROR_CHAT, 2, listOf(ContentPart.Text("A preserved synthetic partial answer."))).copy(
            status = MessageStatus.ERROR,
            finishReason = FinishReason.ERROR,
            error = recoverableError,
        ),
        message(
            IMAGE_CHAT,
            1,
            listOf(ContentPart.Text("Four locally stored synthetic swatches.")) +
                BaselineImages.all.indices.map { imagePart(it, "visual") },
        ),
        message(IMAGE_CHAT, 2, listOf(ContentPart.Text("PNG, JPEG, WebP, and GIF stay available offline."))),
        message(ARCHIVED_CHAT, 1, listOf(ContentPart.Text("An invented archived note."))),
        message(ARCHIVED_CHAT, 2, listOf(ContentPart.Text("Archived content remains readable."))),
    )

    private fun longMessages(): List<Message> = (1..LONG_MESSAGE_COUNT).map { number ->
        val parts = buildList {
            add(ContentPart.Text(if (number % 50 == 0) markdown else "Synthetic local message $number."))
            if (number % 50 == 0) add(ContentPart.Reasoning("Synthetic reasoning for row $number."))
            if (number % 250 == 1) {
                add(imagePart((number / 250) % BaselineImages.all.size, "long-$number"))
            }
        }
        message(LONG_CHAT, number.toLong(), parts)
    }

    private fun imagePart(index: Int, suffix: String): ContentPart.Image = ContentPart.Image(
        attachmentId = "baseline-image-$index-$suffix",
        mimeType = BaselineImages.all[index].mimeType,
        alt = "Synthetic four-color swatch $index",
    )

    private fun imageAttachments(messages: List<Message>): List<ExportAttachment> =
        messages.flatMap { it.parts }.filterIsInstance<ContentPart.Image>()
            .distinctBy { it.attachmentId }
            .map { part ->
                val index = part.attachmentId.removePrefix("baseline-image-").substringBefore('-').toInt()
                val image = BaselineImages.all[index]
                ExportAttachment(
                    id = part.attachmentId,
                    name = image.name,
                    mimeType = image.mimeType,
                    sha256 = image.sha256,
                    size = image.bytes.size.toLong(),
                    dataBase64 = image.dataBase64,
                )
            }

    private fun settings(): Settings = Settings(
        theme = Theme.LIGHT,
        density = Density.COMFORTABLE,
        chatFontSize = dev.chatui.core.model.ChatFontSize.MD,
        sendKey = dev.chatui.core.model.SendKey.ENTER,
        defaultProviderId = provider.id,
        defaultModelId = models.first().id,
        defaultSystemPrompt = "",
        defaultParams = testChatParams(),
        rememberKeysOnWeb = false,
        showTokenUsage = true,
        loadRemoteImages = false,
        lastActiveConversationId = null,
        backup = null,
        onboardingCompleted = true,
    )
}
