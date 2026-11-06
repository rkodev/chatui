package dev.chatui.feature.conversations.export

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportDocumentWriterTest {
    @Test
    fun `writes artifact content to the selected document`() = runBlocking {
        val output = ByteArrayOutputStream()
        val writer = ExportDocumentWriter { output }
        val artifact = ExportArtifact(
            filename = "chatui-export.json",
            mimeType = "application/json",
            content = """{"format":"chatui.export"}""",
        )

        writer.write(Uri.parse("content://test/export"), artifact)

        assertThat(output.toString(Charsets.UTF_8.name())).isEqualTo(artifact.content)
    }

    @Test
    fun `fails when the document cannot be opened`() {
        val writer = ExportDocumentWriter { null }

        assertThrows(IOException::class.java) {
            runBlocking {
                writer.write(
                    Uri.parse("content://test/export"),
                    ExportArtifact("chatui-export.json", "application/json", "{}"),
                )
            }
        }
    }

    @Test
    fun `plaintext warning names the risk`() {
        assertThat(EXPORT_PLAINTEXT_WARNING).contains("plaintext")
        assertThat(EXPORT_PLAINTEXT_WARNING).contains("Anyone with the file can read them")
    }
}
