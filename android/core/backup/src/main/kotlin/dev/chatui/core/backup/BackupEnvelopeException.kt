package dev.chatui.core.backup

/**
 * Stable failure codes shared with the web and Rust clients.
 *
 * The wire values are frozen in `docs/operations/M5/M5-decisions.md`
 * (`DEC-M5-15`) and are asserted by the cross-client vectors.
 */
enum class BackupEnvelopeErrorCode(val wireValue: String) {
    AUTHENTICATION_FAILED("authentication-failed"),
    UNSUPPORTED_VERSION("unsupported-version"),
    UNSUPPORTED_ALG("unsupported-alg"),
    UNSUPPORTED_KDF("unsupported-kdf"),
    MALFORMED_ENVELOPE("malformed-envelope"),
    INVALID_HEADER("invalid-header"),
    DECOMPRESSION_FAILED("decompression-failed"),
}

/** Typed failure raised by the backup envelope codec. */
class BackupEnvelopeException(
    val code: BackupEnvelopeErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
