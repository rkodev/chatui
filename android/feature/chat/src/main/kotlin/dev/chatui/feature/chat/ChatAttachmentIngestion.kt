package dev.chatui.feature.chat

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.chatui.core.database.attachment.AttachmentInput
import dev.chatui.core.database.attachment.MAX_IMAGE_BYTES
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One composition/conversation owns these reads and their original callback. */
internal class ChatAttachmentIngestion(
    private val scope: CoroutineScope,
    private val read: (Uri) -> AttachmentInput,
    private val onInput: suspend (AttachmentInput) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val activeUris = mutableSetOf<Uri>()
    private val jobs = mutableSetOf<Job>()
    private val readMutex = Mutex()
    private var closed = false
    private var pending by mutableIntStateOf(0)
    val processing: Boolean get() = pending > 0
    var error by mutableStateOf<String?>(null)
        private set

    fun submit(uris: List<Uri>, onFinished: () -> Unit = {}) {
        if (closed) {
            onFinished()
            return
        }
        val selected = uris.distinct().filter { activeUris.add(it) }
        if (selected.isEmpty()) {
            onFinished()
            return
        }
        pending += selected.size
        error = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            readMutex.withLock {
                selected.forEach { uri ->
                    try {
                        val input = runInterruptible(dispatcher) { read(uri) }
                        ensureActive()
                        onInput(input)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SecurityException) {
                        error = "Attachment access was denied. Choose it again to grant access."
                    } catch (_: Exception) {
                        error = "Could not read the attachment. Choose it again."
                    } finally {
                        if (activeUris.remove(uri)) pending--
                    }
                }
            }
        }
        jobs += job
        job.invokeOnCompletion {
            jobs -= job
            selected.forEach { uri -> if (activeUris.remove(uri)) pending-- }
            onFinished()
        }
        job.start()
    }

    fun dismissError() {
        error = null
    }

    fun close() {
        closed = true
        jobs.toList().forEach { it.cancel() }
    }
}

internal fun attachmentUris(clip: ClipData?): List<Uri> =
    if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }

/** Metadata and bounded byte reads must both run on the ingestion dispatcher. */
internal fun readAttachmentInput(context: Context, uri: Uri): AttachmentInput {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri).orEmpty()
    var name = uri.lastPathSegment ?: "attachment"
    var declaredSize = -1L
    try {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it) ?: name
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                    declaredSize = cursor.getLong(it)
                }
            }
        }
    } catch (_: Exception) {
        // Providers may omit metadata; validation still uses the actual bytes.
    }
    val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MAX_IMAGE_BYTES + 1) }
        ?: throw IOException("The attachment could not be opened.")
    return AttachmentInput(
        name = name,
        mimeType = mimeType,
        size = if (declaredSize > 0) declaredSize else bytes.size.toLong(),
        bytes = bytes,
    )
}

internal fun readAtMost(input: InputStream, limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (total < limit) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (read < 0) break
        if (read == 0) {
            val byte = input.read()
            if (byte < 0) break
            output.write(byte)
            total++
        } else {
            output.write(buffer, 0, read)
            total += read
        }
    }
    return output.toByteArray()
}
