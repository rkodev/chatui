package dev.chatui.feature.conversations.importer

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags exposed by import UI helpers. */
object ImportTestTags {
    const val ReplaceConfirmation: String = "import-replace-confirmation"
}

/**
 * Reads a `chatui.export` document from a Storage Access Framework URI.
 *
 * The stream opener is injectable so the reader can be tested without an
 * Android content provider. Production uses [ContentResolver]. The reader
 * enforces the archive size limit while streaming so a hostile document cannot
 * force an unbounded allocation.
 */
class ImportDocumentReader(
    private val openInputStream: (Uri) -> InputStream?,
    private val maxBytes: Int = MAX_IMPORT_BYTES,
) {
    constructor(contentResolver: ContentResolver) : this({ uri ->
        contentResolver.openInputStream(uri)
    })

    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val stream = openInputStream(uri)
            ?: throw IOException("Could not open the selected document for reading.")

        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0

            while (true) {
                val read = input.read(buffer)
                if (read < 0) break

                total += read
                if (total > maxBytes) {
                    throw ImportValidationError(
                        ImportErrorCode.TOO_LARGE,
                        "The archive is larger than the $maxBytes byte import limit.",
                    )
                }

                output.write(buffer, 0, read)
            }

            output.toByteArray()
        }
    }

    suspend fun readText(uri: Uri): String = readBytes(uri).toString(Charsets.UTF_8)
}

/** Starts the SAF open-document flow for an import. */
fun interface ImportDocumentLauncher {
    fun launch()
}

/**
 * Remember a SAF open-document launcher.
 *
 * The selected document is read on the IO dispatcher and passed to
 * [onDocument]. Errors are reported through [onError] so the caller can show a
 * validation message without crashing the screen.
 */
@Composable
fun rememberImportDocumentLauncher(
    onDocument: suspend (Uri) -> Unit,
    onError: (Throwable) -> Unit = {},
): ImportDocumentLauncher {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    onDocument(uri)
                } catch (error: Throwable) {
                    onError(error)
                }
            }
        }
    }

    return ImportDocumentLauncher {
        launcher.launch(arrayOf("application/json", "text/plain"))
    }
}

/** Confirmation shown before an explicit replace import. */
@Composable
fun ImportReplaceConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ImportTestTags.ReplaceConfirmation),
        title = { Text("Replace local data?") },
        text = { Text(IMPORT_REPLACE_CONFIRMATION) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Replace") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
