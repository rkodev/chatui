package dev.chatui

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.chatui.core.model.ContentPart
import dev.chatui.core.model.FinishReason
import dev.chatui.core.model.StreamEvent
import dev.chatui.core.network.NormalizedChatRequest
import dev.chatui.feature.conversations.importer.decodeImportArchive
import dev.chatui.testing.BaselineFixtures
import dev.chatui.testing.BaselineHarness
import dev.chatui.testing.BaselineImages
import dev.chatui.testing.FakeProviderAdapter
import dev.chatui.testing.baselineSha256
import dev.chatui.testing.testChatContext
import dev.chatui.testing.testChatParams
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BaselineFixturesTest {
    @Test
    fun `fixture archives are deterministic and accepted by the real importer`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val visual = BaselineFixtures.exportText()
        val longHistory = BaselineFixtures.exportText(BaselineFixtures.archive(longHistory = true))

        assertThat(BaselineFixtures.exportText()).isEqualTo(visual)
        assertThat(BaselineFixtures.exportText(BaselineFixtures.archive(longHistory = true)))
            .isEqualTo(longHistory)
        assertThat(decodeImportArchive(longHistory).messages).hasSize(10_000)

        BaselineHarness.create(context).use { harness ->
            assertThat(harness.conversations.observeArchivedConversations().first().map { it.id })
                .containsExactly(BaselineFixtures.ARCHIVED_CHAT)
            assertThat(harness.messages.allForConversation(BaselineFixtures.CONTENT_CHAT))
                .containsExactlyElementsIn(
                    harness.archive.messages.filter { it.conversationId == BaselineFixtures.CONTENT_CHAT },
                ).inOrder()
            val imageMessage = harness.archive.messages.first { it.conversationId == BaselineFixtures.IMAGE_CHAT }
            val attachments = harness.dependencies.attachmentStore!!.forMessage(imageMessage.id)
            assertThat(attachments).hasSize(BaselineImages.all.size)
            attachments.forEach { attachment ->
                val image = BaselineImages.all.single { it.name == attachment.name }
                val file = harness.dependencies.attachmentStore!!.fileFor(attachment.id)!!
                assertThat(baselineSha256(file.readBytes())).isEqualTo(image.sha256)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)!!
                try {
                    assertThat(bitmap.width).isEqualTo(image.width)
                    assertThat(bitmap.height).isEqualTo(image.height)
                } finally {
                    bitmap.recycle()
                }
            }
        }

        System.getenv("CHATUI_BASELINE_DIR")?.let { output ->
            val directory = File(output)
            require(directory.isAbsolute) { "CHATUI_BASELINE_DIR must be an absolute artifact path." }
            directory.mkdirs()
            File(directory, "fixtures-visual.json").writeText(visual)
            File(directory, "fixtures-10000.json").writeText(longHistory)
            File(directory, "stream.json").writeText(BaselineFixtures.streamRecipe())
            val images = File(directory, "images").apply { mkdirs() }
            BaselineImages.all.forEach { File(images, it.name).writeBytes(it.bytes) }
        }
    }

    @Test
    fun `ten thousand imported messages page without gaps and retain local image references`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BaselineHarness.create(context, longHistory = true, onDisk = true).use { harness ->
            val conversationId = BaselineFixtures.LONG_CHAT
            assertThat(harness.messages.count(conversationId)).isEqualTo(10_000)
            val latest = harness.messages.latest(conversationId, limit = 37)
            val older = harness.messages.older(conversationId, latest.first().sequence, limit = 37)
            assertThat(older + latest)
                .containsExactlyElementsIn(harness.archive.messages.takeLast(74)).inOrder()
            val first = harness.messages.older(conversationId, beforeSequence = 38, limit = 37)
            assertThat(first).containsExactlyElementsIn(harness.archive.messages.take(37)).inOrder()

            val image = first.flatMap { it.parts }.filterIsInstance<ContentPart.Image>().single()
            assertThat(harness.dependencies.attachmentStore!!.getDataUrl(image.attachmentId))
                .startsWith("data:image/png;base64,")
            assertThat(harness.conversations.getConversation(conversationId)!!.messageCount).isEqualTo(10_000)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `local stream recipe preserves deltas cadence and recoverable failure`() = runTest {
        for (fail in listOf(false, true)) {
            val adapter = FakeProviderAdapter()
            val startedAt = currentTime
            val collected = async {
                adapter.streamChat(
                    context = testChatContext(provider = BaselineFixtures.provider).providerContext,
                    request = NormalizedChatRequest(
                        model = BaselineFixtures.models.first().remoteId,
                        system = null,
                        messages = emptyList(),
                        params = testChatParams(),
                    ),
                ).toList()
            }
            launch { BaselineFixtures.playStream(adapter, fail) }
            val events = collected.await()
            assertThat(events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
                .isEqualTo(BaselineFixtures.markdown)
            assertThat(events.filterIsInstance<StreamEvent.ReasoningDelta>()).isNotEmpty()
            assertThat(events.last()).isEqualTo(
                if (fail) StreamEvent.ErrorEvent(BaselineFixtures.recoverableError)
                else StreamEvent.Finish(FinishReason.STOP),
            )
            assertThat(currentTime - startedAt).isEqualTo(
                BaselineFixtures.FIRST_EVENT_DELAY_MS +
                    BaselineFixtures.EVENT_INTERVAL_MS * (events.size - 1),
            )
        }
    }
}
