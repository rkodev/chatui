package dev.chatui.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.chatui.core.backup.sync.DELETE_REMOTE_CONFIRMATION
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A manual backup action that can be in flight. */
enum class BackupAction {
    TEST,
    ENABLE,
    DISABLE,
    BACKUP,
    RESTORE,
    DELETE,
}

/** Result of the last connectivity test. */
data class BackupTestResult(
    val success: Boolean,
    val message: String,
)

/** One immutable state for the backup settings screen. */
data class BackupUiState(
    val loading: Boolean = true,
    val form: BackupFormValues = BackupFormValues(),
    val enabled: Boolean = false,
    val status: BackupStatus? = null,
    val busy: BackupAction? = null,
    val error: String? = null,
    val message: String? = null,
    val testResult: BackupTestResult? = null,
    val deleteConfirmation: String = "",
    val replaceConfirmation: String = "",
    val showDeleteDialog: Boolean = false,
    val showReplaceDialog: Boolean = false,
) {
    val canDeleteRemote: Boolean
        get() = deleteConfirmation == DELETE_REMOTE_CONFIRMATION && busy == null

    val canReplace: Boolean
        get() = replaceConfirmation == RESTORE_REPLACE_CONFIRMATION && busy == null
}

/**
 * State holder for backup configuration, status, and manual actions.
 *
 * The passphrase lives only in [BackupUiState.form] for the lifetime of this
 * ViewModel. It is never loaded from storage and never written back.
 */
class BackupViewModel(
    private val service: BackupSettingsServiceLike,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val snapshot = service.load()
                val status = service.getStatus()
                _state.update { current ->
                    current.copy(
                        loading = false,
                        form = snapshot.toForm(current.form),
                        enabled = snapshot.config?.enabled ?: false,
                        status = status,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "Could not load backup settings.",
                    )
                }
            }
        }
    }

    fun onFormChanged(form: BackupFormValues) {
        _state.update {
            it.copy(form = form, error = null, testResult = null)
        }
    }

    fun onTestConnection() {
        val validation = validateBackupForm(_state.value.form)
        if (validation != null) {
            _state.update { it.copy(error = validation, testResult = null) }
            return
        }
        if (_state.value.busy != null) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = BackupAction.TEST,
                    error = null,
                    message = null,
                    testResult = null,
                )
            }
            try {
                service.testConnection(_state.value.form)
                _state.update {
                    it.copy(
                        busy = null,
                        testResult = BackupTestResult(
                            success = true,
                            message = "Connection succeeded.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val message = error.message ?: "Connection failed."
                _state.update {
                    it.copy(
                        busy = null,
                        error = message,
                        testResult = BackupTestResult(success = false, message = message),
                    )
                }
            }
        }
    }

    fun onEnableChanged(enabled: Boolean) {
        if (enabled) {
            val validation = validateWithPassphrase()
            if (validation != null) {
                _state.update { it.copy(error = validation) }
                return
            }
            runAction(BackupAction.ENABLE) {
                service.setEnabled(enabled = true, values = _state.value.form)
                service.backupNow()
                _state.update { it.copy(enabled = true) }
                "Backup enabled and the first backup completed."
            }
        } else {
            runAction(BackupAction.DISABLE) {
                service.setEnabled(enabled = false, values = _state.value.form)
                _state.update { it.copy(enabled = false) }
                "Backup disabled."
            }
        }
    }

    fun onBackupNow() {
        val validation = validateWithPassphrase()
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        runAction(BackupAction.BACKUP) {
            val result = service.backupNow()
            "Backed up ${result.uploadedConversations.size} conversation(s) and " +
                "${result.uploadedAttachments.size} attachment(s)."
        }
    }

    fun onRestoreMerge() {
        val validation = validateWithPassphrase()
        if (validation != null) {
            _state.update { it.copy(error = validation) }
            return
        }
        runAction(BackupAction.RESTORE) {
            val result = service.restore(RestoreRequest(replace = false))
            "Restored ${result.mergedConversations.size} conversation(s)."
        }
    }

    fun onRequestRestoreReplace() {
        _state.update {
            it.copy(
                showReplaceDialog = true,
                replaceConfirmation = "",
                error = null,
            )
        }
    }

    fun onReplaceConfirmationChanged(value: String) {
        _state.update { it.copy(replaceConfirmation = value) }
    }

    fun onConfirmRestoreReplace() {
        val current = _state.value
        if (!current.canReplace) return

        val validation = validateWithPassphrase()
        if (validation != null) {
            _state.update {
                it.copy(error = validation, showReplaceDialog = false)
            }
            return
        }

        runAction(BackupAction.RESTORE) {
            val result = service.restore(
                RestoreRequest(
                    replace = true,
                    confirmation = current.replaceConfirmation,
                ),
            )
            _state.update {
                it.copy(showReplaceDialog = false, replaceConfirmation = "")
            }
            "Replaced local data with ${result.mergedConversations.size} remote conversation(s)."
        }
    }

    fun onRequestDeleteRemote() {
        _state.update {
            it.copy(
                showDeleteDialog = true,
                deleteConfirmation = "",
                error = null,
            )
        }
    }

    fun onDeleteConfirmationChanged(value: String) {
        _state.update { it.copy(deleteConfirmation = value) }
    }

    fun onConfirmDeleteRemote() {
        val current = _state.value
        if (!current.canDeleteRemote) return

        val validation = validateWithPassphrase()
        if (validation != null) {
            _state.update {
                it.copy(error = validation, showDeleteDialog = false)
            }
            return
        }

        runAction(BackupAction.DELETE) {
            service.deleteRemote(current.deleteConfirmation)
            _state.update {
                it.copy(showDeleteDialog = false, deleteConfirmation = "")
            }
            "Remote backup deleted. Local data was kept."
        }
    }

    fun onDismissDialogs() {
        _state.update {
            it.copy(
                showDeleteDialog = false,
                showReplaceDialog = false,
                deleteConfirmation = "",
                replaceConfirmation = "",
            )
        }
    }

    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            try {
                val status = service.getStatus()
                _state.update { it.copy(status = status) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(error = error.message ?: "Could not refresh backup status.")
                }
            }
        }
    }

    private fun validateWithPassphrase(): String? =
        validateBackupForm(_state.value.form)
            ?: validatePassphrase(_state.value.form.passphrase)

    private fun runAction(action: BackupAction, block: suspend () -> String) {
        if (_state.value.busy != null) return

        viewModelScope.launch {
            _state.update {
                it.copy(busy = action, error = null, message = null)
            }
            try {
                val message = block()
                val status = runCatching { service.getStatus() }.getOrNull()
                _state.update {
                    it.copy(
                        busy = null,
                        message = message,
                        status = status ?: it.status,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        busy = null,
                        error = error.message ?: "Backup action failed.",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(service: BackupSettingsServiceLike): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { BackupViewModel(service) }
            }
    }
}

private fun BackupSettingsSnapshot.toForm(fallback: BackupFormValues): BackupFormValues {
    val config = config ?: return fallback.copy(passphrase = "")
    return BackupFormValues(
        backend = config.backend,
        endpoint = config.endpoint.orEmpty(),
        region = config.region.orEmpty(),
        bucket = config.bucket,
        prefix = config.prefix,
        accessKeyId = credentials?.accessKeyId.orEmpty(),
        secretAccessKey = credentials?.secretAccessKey.orEmpty(),
        passphrase = "",
    )
}
