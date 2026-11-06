package dev.chatui.intent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An external intent action that the shell can consume once. */
sealed interface ExternalIntentAction {
    /** Stable identity used to ignore repeated delivery of the same intent. */
    val key: String

    /** Plain text shared from another application. */
    data class ShareText(val text: String) : ExternalIntentAction {
        override val key: String = "share:${text.hashCode()}"
    }
}

/**
 * Application-scoped channel for intents that arrive before the shell exists.
 *
 * The activity submits parsed actions here; the shell collects [pending] after
 * its navigation controller is ready and consumes the action exactly once.
 * Keeping this outside Compose state lets a cold-start intent survive activity
 * recreation until the shell is able to handle it.
 */
@Singleton
class ExternalIntentRouter @Inject constructor() {
    private val _pending = MutableStateFlow<ExternalIntentAction?>(null)

    /** The action waiting to be handled, or `null` when nothing is pending. */
    val pending: StateFlow<ExternalIntentAction?> = _pending.asStateFlow()

    private var lastConsumedKey: String? = null

    /**
     * Publishes [action] unless it is already pending or was the last action
     * consumed. This makes repeated delivery of the same intent harmless.
     */
    fun submit(action: ExternalIntentAction) {
        if (action.key == lastConsumedKey) return
        if (_pending.value?.key == action.key) return

        _pending.value = action
    }

    /** Clears [action] if it is still the pending action. */
    fun consume(action: ExternalIntentAction) {
        if (_pending.value?.key != action.key) return

        _pending.value = null
        lastConsumedKey = action.key
    }
}
