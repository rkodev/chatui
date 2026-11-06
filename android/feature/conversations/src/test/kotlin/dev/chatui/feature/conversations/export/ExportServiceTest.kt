package dev.chatui.feature.conversations.export

import com.google.common.truth.Truth.assertThat
import dev.chatui.core.database.export.ExportReaderLike
import dev.chatui.core.database.export.ExportSnapshot
import dev.chatui.core.model.AttachmentKind
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.MessageRole
import dev.chatui.core.model.MessageStatus
import dev.chatui.feature.conversations.TEST_NOW
import dev.chatui.feature.conversations.testAttachment
import dev.chatui.feature.conversations.testConversation
import dev.chatui.feature.conversations.testMessage
import dev.chatui.feature.conversations.testModel
import dev.chatui.feature.conversations.testProvider
import dev.chatui.feature.conversations.testSettings
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportServiceTest {
    private val schema = Json.parseToJsonElement(readContract("schema/chat-export.schema.json")).jsonObject
    private val fixture = readContract("fixtures/expected/chatui-export.json")

    private val provider = testProvider()
    private val model = testModel()
    private val attachment = testAttachment(
        id = "attachment-1",
        messageId = "message-1",
        kind = AttachmentKind.IMAGE,
        mimeType = "image/png",
        name = "pixel.png",
        size = 68,
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        storageKey = "attachments/pixel",
    )

    private val conversationOne = testConversation(
        id = "conversation-1",
        title = "First chat",
        lastMessageAt = TEST_NOW,
    )
    private val conversationTwo = testConversation(
        id = "conversation-2",
        title = "Second chat",
        lastMessageAt = TEST_NOW.plusSeconds(60),
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
        providerId = provider.id,
        modelId = model.id,
        modelLabel = model.label,
        status = MessageStatus.COMPLETE,
    )
    private val messageThree = testMessage(
        id = "message-3",
        conversationId = "conversation-2",
        sequence = 0,
        text = "Other conversation",
    )

    private val fullSnapshot = ExportSnapshot(
        conversations = listOf(conversationOne, conversationTwo),
        messages = listOf(messageOne, messageTwo, messageThree),
        attachments = listOf(attachment),
        providers = listOf(provider),
        models = listOf(model),
        settings = testSettings(),
    )

    private val conversationSnapshot = fullSnapshot.copy(
        conversations = listOf(conversationOne),
        messages = listOf(messageOne, messageTwo),
    )

    private val reader = FakeExportReader(fullSnapshot, conversationSnapshot)

    @Test
    fun `checked-in fixture validates against the schema`() {
        val errors = ExportSchemaValidator.validate(
            Json.parseToJsonElement(fixture),
            schema,
        )

        assertThat(errors).isEmpty()
        validateChatExport(fixture)
    }

    @Test
    fun `full archive validates against the schema`() = runBlocking {
        val service = ExportService(reader = reader, now = { FIXED_NOW })
        val artifact = service.exportAll()

        assertThat(artifact.filename).isEqualTo("chatui-export-2026-11-04T12-00-00-000Z.json")
        assertThat(artifact.mimeType).isEqualTo("application/json")

        val errors = ExportSchemaValidator.validate(
            Json.parseToJsonElement(artifact.content),
            schema,
        )
        assertThat(errors).isEmpty()
    }

    @Test
    fun `per-conversation JSON validates against the schema`() = runBlocking {
        val service = ExportService(reader = reader, now = { FIXED_NOW })
        val artifact = service.exportConversation("conversation-1")

        assertThat(artifact.filename).isEqualTo("chatui-first-chat-conversation-1.json")
        assertThat(artifact.mimeType).isEqualTo("application/json")

        val errors = ExportSchemaValidator.validate(
            Json.parseToJsonElement(artifact.content),
            schema,
        )
        assertThat(errors).isEmpty()
    }

    @Test
    fun `per-conversation Markdown preserves content and attachment references`() = runBlocking {
        val service = ExportService(reader = reader, now = { FIXED_NOW })
        val artifact = service.exportConversation(
            "conversation-1",
            ConversationExportOptions(format = ExportFormat.MARKDOWN),
        )

        assertThat(artifact.filename).isEqualTo("chatui-first-chat-conversation-1.md")
        assertThat(artifact.mimeType).isEqualTo("text/markdown")
        assertThat(artifact.content).contains("# First chat")
        assertThat(artifact.content).contains("Describe this image.")
        assertThat(artifact.content).contains("It is a single pixel.")
        assertThat(artifact.content).contains("attachment-1")
        assertThat(artifact.content).contains("pixel.png")
    }

    @Test
    fun `attachment bytes are omitted by default`() = runBlocking {
        val service = ExportService(reader = reader, now = { FIXED_NOW })
        val artifact = service.exportAll()
        val attachmentJson = Json.parseToJsonElement(artifact.content)
            .jsonObject["attachments"]!!
            .jsonArray
            .single()
            .jsonObject

        assertThat(attachmentJson["id"]!!.jsonPrimitive.content).isEqualTo("attachment-1")
        assertThat(attachmentJson.containsKey("dataBase64")).isFalse()
    }

    @Test
    fun `attachment bytes are included when requested`() = runBlocking {
        val bytes = "hello".toByteArray()
        val service = ExportService(
            reader = reader,
            attachmentBytes = { storageKey ->
                if (storageKey == attachment.storageKey) bytes else null
            },
            now = { FIXED_NOW },
        )
        val artifact = service.exportAll(ExportOptions(includeAttachmentBytes = true))
        val attachmentJson = Json.parseToJsonElement(artifact.content)
            .jsonObject["attachments"]!!
            .jsonArray
            .single()
            .jsonObject

        assertThat(attachmentJson["dataBase64"]!!.jsonPrimitive.content)
            .isEqualTo(Base64.getEncoder().encodeToString(bytes))
    }

    @Test
    fun `missing attachment bytes fail instead of writing a partial archive`() {
        val service = ExportService(
            reader = reader,
            attachmentBytes = { null },
            now = { FIXED_NOW },
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                service.exportAll(ExportOptions(includeAttachmentBytes = true))
            }
        }
    }

    @Test
    fun `secrets are excluded and provider references stay opaque`() = runBlocking {
        val service = ExportService(reader = reader, now = { FIXED_NOW })
        val artifact = service.exportAll()

        assertThat(artifact.content).contains("\"secretId\": \"secret-1\"")
        assertThat(artifact.content).doesNotContain("ciphertext")
        assertThat(artifact.content).doesNotContain("super-secret")
    }

    @Test
    fun `IDs order snapshots and attachment references are preserved`() = runBlocking {
        val service = ExportService(reader = reader, now = { FIXED_NOW })
        val artifact = service.exportAll()
        val root = Json.parseToJsonElement(artifact.content).jsonObject

        val conversationIds = root["conversations"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertThat(conversationIds).containsExactly("conversation-1", "conversation-2").inOrder()

        val messageIds = root["messages"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertThat(messageIds)
            .containsExactly("message-1", "message-2", "message-3")
            .inOrder()

        val assistant = root["messages"]!!.jsonArray[1].jsonObject
        assertThat(assistant["providerId"]!!.jsonPrimitive.content).isEqualTo(provider.id)
        assertThat(assistant["modelId"]!!.jsonPrimitive.content).isEqualTo(model.id)
        assertThat(assistant["modelLabel"]!!.jsonPrimitive.content).isEqualTo(model.label)

        val imagePart = root["messages"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[1].jsonObject
        assertThat(imagePart["attachmentId"]!!.jsonPrimitive.content).isEqualTo("attachment-1")

        val attachmentIds = root["attachments"]!!.jsonArray.map {
            it.jsonObject["id"]!!.jsonPrimitive.content
        }
        assertThat(attachmentIds).containsExactly("attachment-1")
        Unit
    }

    @Test
    fun `invalid archives are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateChatExport("""{"format":"wrong"}""")
        }

        assertThrows(IllegalArgumentException::class.java) {
            validateChatExport(
                fixture.replace(
                    "\"format\": \"chatui.export\"",
                    "\"format\": \"chatui.export\", \"unexpected\": true",
                ),
            )
        }
    }

    private class FakeExportReader(
        private val all: ExportSnapshot,
        private val conversation: ExportSnapshot,
    ) : ExportReaderLike {
        override suspend fun listConversations() = all.conversations

        override suspend fun readAll() = all

        override suspend fun readConversation(conversationId: String): ExportSnapshot? =
            if (conversationId == conversation.conversations.single().id) conversation else null
    }

    private companion object {
        const val FIXED_NOW = "2026-11-04T12:00:00.000Z"

        fun readContract(relativePath: String): String {
            var directory: File? = File(System.getProperty("user.dir") ?: ".")
            while (directory != null) {
                val candidate = File(directory, "contracts/$relativePath")
                if (candidate.isFile) return candidate.readText()
                directory = directory.parentFile
            }
            error("Could not find contracts/$relativePath from ${System.getProperty("user.dir")}")
        }
    }
}
