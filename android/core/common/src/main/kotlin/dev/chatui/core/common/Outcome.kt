package dev.chatui.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * Platform-independent result type.
 *
 * This deliberately does not reuse the domain `ChatError`; provider adapters
 * normalize their own errors into `ChatError`, while this type covers generic
 * operations such as storage, parsing, and configuration.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>

    data class Failure(val error: AppError) : Outcome<Nothing>
}

/** Generic error categories for [Outcome]. */
enum class AppErrorCode {
    UNKNOWN,
    INVALID_ARGUMENT,
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    IO,
    SERIALIZATION,
    STORAGE,
    NETWORK,
}

/** Generic operation error. Provider-specific errors belong in the domain model. */
data class AppError(
    val code: AppErrorCode,
    val message: String,
    val cause: Throwable? = null,
    val retryable: Boolean = false,
)

/** Run [block], converting any thrown exception into [Outcome.Failure]. */
inline fun <T> outcomeOf(block: () -> T): Outcome<T> =
    try {
        Outcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Outcome.Failure(
            AppError(
                code = AppErrorCode.UNKNOWN,
                message = throwable.message ?: throwable::class.simpleName ?: "Unknown error",
                cause = throwable,
            ),
        )
    }

/** Transform a successful value, preserving failures. */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Success -> Outcome.Success(transform(value))
        is Outcome.Failure -> this
    }

/** Return the success value, or `null` for a failure. */
fun <T> Outcome<T>.getOrNull(): T? = (this as? Outcome.Success)?.value

/** Return the failure, or `null` for a success. */
fun <T> Outcome<T>.errorOrNull(): AppError? = (this as? Outcome.Failure)?.error
