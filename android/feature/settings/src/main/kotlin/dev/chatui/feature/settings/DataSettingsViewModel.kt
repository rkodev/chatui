package dev.chatui.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Confirmation phrase required before clear-all runs. */
const val CLEAR_ALL_CONFIRMATION: String = "DELETE ALL DATA"

enum class DataFileAction { EXPORT, IMPORT_MERGE, IMPORT_REPLACE }

enum class DataFilePhase { IDLE, PREPARING, PICKING, WORKING, SUCCEEDED, CANCELLED, FAILED }

data class DataFileOperation(
    val action: DataFileAction = DataFileAction.EXPORT,
    val phase: DataFilePhase = DataFilePhase.IDLE,
    val error: String? = null,
) {
    val active: Boolean
        get() = phase in setOf(DataFilePhase.PREPARING, DataFilePhase.PICKING, DataFilePhase.WORKING)

    val description: String?
        get() = when (phase) {
            DataFilePhase.IDLE, DataFilePhase.FAILED -> null
            DataFilePhase.PREPARING -> "Preparing export..."
            DataFilePhase.PICKING -> if (action == DataFileAction.EXPORT) {
                "Choose where to save the export."
            } else {
                "Choose an archive to import."
            }
            DataFilePhase.WORKING -> if (action == DataFileAction.EXPORT) "Writing export..." else "Importing archive..."
            DataFilePhase.SUCCEEDED -> when (action) {
                DataFileAction.EXPORT -> "Export saved."
                DataFileAction.IMPORT_MERGE -> "Import merged with local data."
                DataFileAction.IMPORT_REPLACE -> "Local data replaced by the imported archive."
            }
            DataFilePhase.CANCELLED -> if (action == DataFileAction.EXPORT) {
                "Export cancelled. No file was written."
            } else {
                "Import cancelled. Local data was not changed."
            }
        }
}

/** One immutable state for the data settings screen. */
data class DataSettingsUiState(
    val usage: StorageUsage? = null,
    val keys: List<ProviderKeySummary> = emptyList(),
    val loading: Boolean = true,
    val clearing: Boolean = false,
    val removingKeyId: String? = null,
    val confirmKeyId: String? = null,
    val confirmationPhrase: String = "",
    val error: String? = null,
    val status: String? = null,
    val fileOperation: DataFileOperation = DataFileOperation(),
) {
    val busy: Boolean
        get() = fileOperation.active || clearing || removingKeyId != null

    val canClear: Boolean
        get() = confirmationPhrase == CLEAR_ALL_CONFIRMATION && !busy
}

/**
 * State holder for storage usage, clear-all, and per-key deletion.
 *
 * The destructive clear action stays disabled until the user types the exact
 * confirmation phrase. Per-key deletion uses a two-step confirmation so a
 * single tap cannot remove a stored key.
 */
class DataSettingsViewModel(
    private val service: DataControls,
) : ViewModel() {
    private val _state = MutableStateFlow(DataSettingsUiState())
    val state: StateFlow<DataSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val usage = service.getUsage()
                val keys = service.listProviderKeys()
                _state.update {
                    it.copy(usage = usage, keys = keys, loading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Could not load data usage.",
                    )
                }
            }
        }
    }

    fun onConfirmationPhraseChanged(value: String) {
        _state.update { it.copy(confirmationPhrase = value) }
    }

    fun onClearAll() {
        if (!_state.value.canClear) return

        viewModelScope.launch {
            _state.update { it.copy(clearing = true, error = null, status = null) }
            try {
                val result = service.clearAll()
                _state.update {
                    it.copy(
                        clearing = false,
                        confirmationPhrase = "",
                        status = "Removed ${result.conversations} conversation(s), " +
                            "${result.messages} message(s), ${result.attachments} " +
                            "attachment(s), and ${result.secrets} stored key(s).",
                    )
                }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        clearing = false,
                        error = error.message ?: "Could not clear data.",
                    )
                }
            }
        }
    }

    fun onRequestRemoveKey(providerId: String) {
        if (_state.value.busy) return
        _state.update {
            it.copy(confirmKeyId = providerId, error = null, status = null)
        }
    }

    fun onCancelRemoveKey() {
        _state.update { it.copy(confirmKeyId = null) }
    }

    fun onConfirmRemoveKey(providerId: String) {
        if (_state.value.busy || _state.value.confirmKeyId != providerId) return

        viewModelScope.launch {
            _state.update {
                it.copy(removingKeyId = providerId, error = null, status = null)
            }
            try {
                service.removeProviderKey(providerId)
                _state.update {
                    it.copy(
                        removingKeyId = null,
                        confirmKeyId = null,
                        status = "Key removed. Add a new key before sending with this provider.",
                    )
                }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        removingKeyId = null,
                        error = error.message ?: "Could not remove the key.",
                    )
                }
            }
        }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    fun onStartFileOperation() {
        _state.update { it.copy(error = null, status = null) }
    }

    companion object {
        fun factory(service: DataControls): ViewModelProvider.Factory = viewModelFactory {
            initializer { DataSettingsViewModel(service) }
        }
    }
}
