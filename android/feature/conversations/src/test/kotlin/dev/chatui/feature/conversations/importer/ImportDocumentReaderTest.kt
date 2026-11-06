package dev.chatui.feature.conversations.importer

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportDocumentReaderTest {
    private val uri = Uri.parse("content://test/import")

    @Test
    fun `reads bytes and text from the selected document`() = runBlocking {
        val reader = ImportDocumentReader(
            openInputStream = { ByteArrayInputStream("hello".toByteArray()) },
        )

        assertThat(reader.readBytes(uri)).isEqualTo("hello".toByteArray())
        assertThat(reader.readText(uri)).isEqualTo("hello")
    }

    @Test
    fun `rejects documents larger than the limit`() {
        val reader = ImportDocumentReader(
            openInputStream = { ByteArrayInputStream("hello".toByteArray()) },
            maxBytes = 4,
        )

        val error = assertThrows(ImportValidationError::class.java) {
            runBlocking { reader.readBytes(uri) }
        }

        assertThat(error.code).isEqualTo(ImportErrorCode.TOO_LARGE)
    }

    @Test
    fun `fails when the document cannot be opened`() {
        val reader = ImportDocumentReader(openInputStream = { null })

        assertThrows(IOException::class.java) {
            runBlocking { reader.readBytes(uri) }
        }
    }

    @Test
    fun `replace confirmation names the destructive action`() {
        assertThat(IMPORT_REPLACE_CONFIRMATION).contains("Replace all local")
        assertThat(IMPORT_REPLACE_CONFIRMATION).contains("cannot be undone")
    }
}
