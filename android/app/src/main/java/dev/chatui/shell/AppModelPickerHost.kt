package dev.chatui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.EntryPointAccessors
import dev.chatui.feature.providers.ModelPickerSheet
import dev.chatui.feature.providers.ModelPickerTarget
import dev.chatui.feature.providers.ModelPickerViewModel
import dev.chatui.feature.providers.ProviderManagementEntryPoint
import dev.chatui.feature.providers.ProviderManagementService

/** App-owned entry point shared by chat, conversation settings, and defaults. */
val LocalAppModelPicker = compositionLocalOf<AppModelPickerState?> { null }

class AppModelPickerState : ViewModel() {
    internal class Request(
        val target: ModelPickerTarget,
        val refresh: () -> Unit,
    ) : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    internal var request by mutableStateOf<Request?>(null)
        private set

    fun openConversation(conversationId: String, onClosed: () -> Unit = {}) {
        open(ModelPickerTarget.Conversation(conversationId), onClosed)
    }

    fun openDefaults(onClosed: () -> Unit = {}) {
        open(ModelPickerTarget.Defaults, onClosed)
    }

    private fun open(target: ModelPickerTarget, refresh: () -> Unit) {
        if (request?.target == target) return
        val previous = request
        request = Request(target, refresh)
        previous?.viewModelStore?.clear()
    }

    internal fun close(expected: Request): Boolean {
        if (request !== expected) return false
        request = null
        expected.viewModelStore.clear()
        // Re-read persistence even after cancellation or a failed post-save reload.
        expected.refresh()
        return true
    }

    override fun onCleared() {
        request?.viewModelStore?.clear()
        request = null
    }
}

@Composable
internal fun AppModelPickerHost(
    state: AppModelPickerState,
    onClosed: (ModelPickerTarget) -> Unit = {},
    service: ProviderManagementService? = null,
) {
    val request = state.request ?: return
    val context = LocalContext.current
    val resolvedService = service ?: remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ProviderManagementEntryPoint::class.java,
        ).providerManagementService()
    }
    val picker: ModelPickerViewModel = viewModel(
        viewModelStoreOwner = request,
        factory = ModelPickerViewModel.factory(resolvedService, request.target),
    )
    val pickerState by picker.state.collectAsStateWithLifecycle()
    val close = {
        if (state.close(request)) onClosed(request.target)
    }

    LaunchedEffect(request, pickerState.selectionVersion) {
        if (pickerState.selectionVersion > 0) close()
    }

    ModelPickerSheet(
        state = pickerState,
        title = when (request.target) {
            is ModelPickerTarget.Conversation -> "Model for this conversation"
            ModelPickerTarget.Defaults -> "Default model for new chats"
            is ModelPickerTarget.Manage -> "Choose a model"
        },
        onDismiss = close,
        onQueryChanged = picker::onQueryChanged,
        onSelect = picker::onSelect,
        onPin = picker::onPin,
        onHide = picker::onHide,
        onManualRemoteIdChanged = picker::onManualRemoteIdChanged,
        onManualLabelChanged = picker::onManualLabelChanged,
        onAddManual = picker::onAddManual,
        onRefresh = picker::onRefresh,
        onRetry = picker::onRetry,
    )
}
