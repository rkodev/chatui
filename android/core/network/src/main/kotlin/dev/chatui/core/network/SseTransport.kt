package dev.chatui.core.network

import java.io.IOException
import java.io.Reader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer

/**
 * Default maximum number of characters retained for an incomplete SSE event.
 *
 * Complete events are drained as soon as their terminating blank line arrives,
 * so this limit only applies to data that has not yet formed a complete event.
 * It protects against a provider that never sends a blank line or that sends an
 * unbounded `data:` line.
 */
const val DEFAULT_MAX_SSE_BUFFER_CHARS: Int = 1024 * 1024

/** Error bodies are diagnostic only; never buffer an unbounded provider response. */
private const val DEFAULT_MAX_ERROR_BODY_CHARS: Int = 64 * 1024

/** Base class for normalized SSE transport failures. */
sealed class SseTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** An incomplete event or line grew past the configured buffer limit. */
class SseBufferOverflowException(
    val maxBufferChars: Int,
) : SseTransportException("SSE buffer exceeded the $maxBufferChars character limit")

/**
 * The server returned a non-success HTTP status.
 *
 * The raw body and headers are exposed so an adapter can map provider error
 * envelopes. This transport never parses provider JSON.
 */
class SseHttpException(
    val code: Int,
    val headers: Headers,
    val body: String,
) : SseTransportException("SSE request failed with HTTP $code")

/** The response could not be opened or read. */
class SseReadException(
    message: String,
    cause: Throwable,
) : SseTransportException(message, cause)

/**
 * Provider-agnostic Server-Sent Events transport built on OkHttp.
 *
 * The transport only understands SSE framing. Every `data:` value is emitted as
 * a raw string; adapters are responsible for interpreting JSON, `[DONE]`
 * sentinels, and provider-specific event names.
 *
 * The returned flow is cold: each collection executes a new HTTP call. When the
 * collecting coroutine is cancelled, the OkHttp call is cancelled and the
 * response body is closed, so a blocked read does not hang.
 */
class SseTransport(
    private val client: OkHttpClient,
    private val maxBufferChars: Int = DEFAULT_MAX_SSE_BUFFER_CHARS,
) {
    init {
        require(maxBufferChars > 0) { "maxBufferChars must be positive" }
    }

    fun stream(request: Request): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        val completed = AtomicBoolean(false)

        val readerJob = launch(Dispatchers.IO) {
            try {
                val response = try {
                    call.execute()
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw SseReadException("Failed to open the SSE stream", error)
                }

                response.use {
                    if (!it.isSuccessful) {
                        throw SseHttpException(
                            code = it.code,
                            headers = it.headers,
                            body = readBounded(it.body, DEFAULT_MAX_ERROR_BODY_CHARS),
                        )
                    }

                    try {
                        SseParser(maxBufferChars).read(it.body.charStream()) { data ->
                            send(data)
                        }
                    } catch (error: SseTransportException) {
                        throw error
                    } catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw SseReadException("Failed to read the SSE stream", error)
                    }
                }

                completed.set(true)
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }

        awaitClose {
            if (!completed.get()) {
                call.cancel()
            }
            readerJob.cancel()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * SSE framing parser.
 *
 * Line endings are normalized before event splitting, so CRLF, LF, and lone CR
 * all work even when a CRLF pair is split across reads. Events are separated by
 * a blank line. Only `data:` fields are collected; comments and unknown fields
 * are ignored. Multiple `data:` lines are joined with a single newline.
 *
 * The final event is flushed when the stream ends without a trailing blank
 * line. Empty data values are not emitted, so keep-alive comments never produce
 * blank messages.
 */
internal class SseParser(
    private val maxBufferChars: Int = DEFAULT_MAX_SSE_BUFFER_CHARS,
) {
    init {
        require(maxBufferChars > 0) { "maxBufferChars must be positive" }
    }

    suspend fun read(reader: Reader, onData: suspend (String) -> Unit) {
        val lineReader = BoundedLineReader(reader, maxBufferChars)
        val dataLines = mutableListOf<String>()
        var dataLength = 0

        while (true) {
            val line = lineReader.readLine() ?: break

            if (line.isEmpty()) {
                emit(dataLines, onData)
                dataLines.clear()
                dataLength = 0
                continue
            }

            if (line.startsWith(":")) {
                continue
            }

            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            if (field != "data") {
                continue
            }

            var value = if (colon == -1) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) {
                value = value.substring(1)
            }

            val added = value.length + if (dataLines.isEmpty()) 0 else 1
            if (dataLength + added > maxBufferChars) {
                throw SseBufferOverflowException(maxBufferChars)
            }
            dataLength += added
            dataLines += value
        }

        emit(dataLines, onData)
    }

    private suspend fun emit(dataLines: List<String>, onData: suspend (String) -> Unit) {
        if (dataLines.isEmpty()) {
            return
        }

        val data = dataLines.joinToString("\n")
        if (data.isEmpty()) {
            return
        }

        onData(data)
    }
}

/**
 * Reads lines with CRLF, LF, and lone-CR normalization while bounding the
 * number of characters retained for a single line.
 */
internal class BoundedLineReader(
    private val reader: Reader,
    private val maxLineChars: Int,
) {
    private var pending: Int = NO_PENDING

    fun readLine(): String? {
        val builder = StringBuilder()

        while (true) {
            val c = readChar()
            if (c == -1) {
                return if (builder.isEmpty()) null else builder.toString()
            }

            when (c) {
                '\n'.code -> return builder.toString()
                '\r'.code -> {
                    val next = readChar()
                    if (next != '\n'.code && next != -1) {
                        pending = next
                    }
                    return builder.toString()
                }
                else -> {
                    builder.append(c.toChar())
                    if (builder.length > maxLineChars) {
                        throw SseBufferOverflowException(maxLineChars)
                    }
                }
            }
        }
    }

    private fun readChar(): Int {
        if (pending != NO_PENDING) {
            val c = pending
            pending = NO_PENDING
            return c
        }
        return reader.read()
    }

    private companion object {
        const val NO_PENDING = -2
    }
}

private fun readBounded(body: ResponseBody, maxChars: Int): String {
    val source = body.source()
    val buffer = Buffer()
    val builder = StringBuilder()

    while (builder.length < maxChars) {
        val remaining = (maxChars - builder.length).toLong()
        val read = source.read(buffer, minOf(8192L, remaining))
        if (read == -1L) {
            break
        }
        builder.append(buffer.readByteArray().toString(Charsets.UTF_8))
    }

    return builder.toString()
}
