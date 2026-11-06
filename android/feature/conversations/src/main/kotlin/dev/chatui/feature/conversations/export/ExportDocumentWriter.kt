package dev.chatui.feature.conversations.export

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags exposed by export UI helpers. */
object ExportTestTags {
    const val PlaintextWarning: String = "export-plaintext-warning"
}

/**
 * Writes an export artifact to a Storage Access Framework document.
 *
 * The stream opener is injectable so the writer can be tested without an
 * Android content provider. Production uses [ContentResolver].
 */
class ExportDocumentWriter(
    private val openOutputStream: (Uri) -> OutputStream?,
) {
    constructor(contentResolver: ContentResolver) : this({ uri ->
        contentResolver.openOutputStream(uri, "wt")
    })

    suspend fun write(uri: Uri, artifact: ExportArtifact) = withContext(Dispatchers.IO) {
        val stream = openOutputStream(uri)
            ?: throw IOException("Could not open the selected document for writing.")
        stream.use { it.write(artifact.content.toByteArray(Charsets.UTF_8)) }
    }
}

/** Starts the SAF create-document flow for a prepared artifact. */
fun interface ExportDocumentLauncher {
    fun launch()
}

/**
 * Remember a SAF create-document launcher.
 *
 * [artifact] is evaluated only after the user taps the export action. The
 * returned launcher opens the system document picker with the artifact's
 * filename and writes the content to the chosen URI.
 */
@Composable
fun rememberExportDocumentLauncher(
    artifact: suspend () -> ExportArtifact,
    onError: (Throwable) -> Unit = {},
): ExportDocumentLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writer = remember(context) { ExportDocumentWriter(context.contentResolver) }
    var pending by remember { mutableStateOf<ExportArtifact?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val current = pending
        pending = null
        if (uri != null && current != null) {
            scope.launch {
                try {
                    writer.write(uri, current)
                } catch (error: Throwable) {
                    onError(error)
                }
            }
        }
    }

    return ExportDocumentLauncher {
        scope.launch {
            try {
                val next = artifact()
                pending = next
                launcher.launch(next.filename)
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }
}

/** Plaintext warning shown before an export is written. */
@Composable
fun ExportPlaintextWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(ExportTestTags.PlaintextWarning),
        title = { Text("Export is plaintext") },
        text = { Text(EXPORT_PLAINTEXT_WARNING) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
