package dev.chatui.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chatui.core.designsystem.BrandMark
import dev.chatui.core.designsystem.EmptyState
import dev.chatui.core.designsystem.ErrorState
import dev.chatui.core.designsystem.LocalChatUiColors
import dev.chatui.core.designsystem.LocalDimens
import dev.chatui.core.designsystem.LocalReducedMotion
import dev.chatui.core.designsystem.MotionSpecs
import dev.chatui.core.designsystem.SecureScreen
import dev.chatui.core.designsystem.SkeletonList
import dev.chatui.core.designsystem.Typography
import dev.chatui.core.designsystem.rememberHaptics

/** Test tags exposed by the onboarding screen. */
object OnboardingTestTags {
    const val Screen = "onboarding-screen"
    const val Welcome = "onboarding-welcome"
    const val StepIndicator = "onboarding-step-indicator"
    const val ProviderPicker = "onboarding-provider-picker"
    const val ContinueButton = "onboarding-continue"
    const val KeyEntry = "onboarding-key-entry"
    const val ApiKeyField = "onboarding-api-key"
    const val ShowKeyToggle = "onboarding-show-key"
    const val PasteKey = "onboarding-paste-key"
    const val HelpLink = "onboarding-help"
    const val LabelField = "onboarding-label"
    const val ConnectButton = "onboarding-connect"
    const val TestingStatus = "onboarding-testing"
    const val ConnectedStatus = "onboarding-connected"
    const val Notice = "onboarding-notice"
    const val ErrorCard = "onboarding-error"
    const val ErrorDetailsToggle = "onboarding-error-details-toggle"
    const val ErrorDetails = "onboarding-error-details"
    const val Catalog = "onboarding-catalog"
    const val CatalogLoading = "onboarding-catalog-loading"
    const val CatalogError = "onboarding-catalog-error"
    const val CatalogEmpty = "onboarding-catalog-empty"
    const val CatalogList = "onboarding-catalog-list"
    const val CatalogDisclosure = "onboarding-catalog-disclosure"
    const val RetryCatalog = "onboarding-catalog-retry"
    const val Completion = "onboarding-completion"
    const val StartChattingButton = "onboarding-start-chatting"
    const val BackButton = "onboarding-back"

    fun providerOption(id: String): String = "onboarding-provider-$id"
}

/**
 * Stateless onboarding screen.
 *
 * The welcome step explains BYOK/on-device storage and shows the provider
 * picker with OpenRouter preselected. The key-entry step shows the form, the
 * public catalog, and a completion card once validation succeeds.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRetryCatalog: () -> Unit,
    onStartChatting: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.step == OnboardingStep.KEY_ENTRY) {
        SecureScreen {
            OnboardingContent(
                state = state,
                onApiKeyChanged = onApiKeyChanged,
                onLabelChanged = onLabelChanged,
                onProviderSelected = onProviderSelected,
                onContinue = onContinue,
                onBack = onBack,
                onConnect = onConnect,
                onRetryCatalog = onRetryCatalog,
                onStartChatting = onStartChatting,
                modifier = modifier,
            )
        }
    } else {
        OnboardingContent(
            state = state,
            onApiKeyChanged = onApiKeyChanged,
            onLabelChanged = onLabelChanged,
            onProviderSelected = onProviderSelected,
            onContinue = onContinue,
            onBack = onBack,
            onConnect = onConnect,
            onRetryCatalog = onRetryCatalog,
            onStartChatting = onStartChatting,
            modifier = modifier,
        )
    }
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onProviderSelected: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRetryCatalog: () -> Unit,
    onStartChatting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(OnboardingTestTags.Screen),
        contentPadding = PaddingValues(dimens.padding),
        verticalArrangement = Arrangement.spacedBy(dimens.gap),
    ) {
        item { StepIndicator(state.step) }

        when (state.step) {
            OnboardingStep.WELCOME -> {
                item { WelcomeHero() }
                item { ProviderPicker(state, onProviderSelected) }
                item {
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = dimens.touchTargetMin)
                            .testTag(OnboardingTestTags.ContinueButton),
                    ) {
                        Text("Get started")
                    }
                }
            }

            OnboardingStep.KEY_ENTRY -> {
                item { KeyEntryHeader(state) }
                item {
                    KeyEntryForm(
                        state = state,
                        onApiKeyChanged = onApiKeyChanged,
                        onLabelChanged = onLabelChanged,
                        onConnect = onConnect,
                        onBack = onBack,
                    )
                }
                item { CatalogSection(state.catalog, onRetryCatalog) }
                if (state.connected) {
                    item { CompletionSection(onStartChatting) }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: OnboardingStep) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val reducedMotion = LocalReducedMotion.current
    val target = if (step == OnboardingStep.WELCOME) 0.5f else 1f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) {
            androidx.compose.animation.core.snap()
        } else {
            MotionSpecs.emphasized()
        },
        label = "onboardingStepProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.StepIndicator),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.space2)) {
            StepLabel(
                text = "Welcome",
                active = true,
                modifier = Modifier.weight(1f),
            )
            StepLabel(
                text = "Connect",
                active = step == OnboardingStep.KEY_ENTRY,
                modifier = Modifier.weight(1f),
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(dimens.radiusFull)),
            color = colors.accent,
            trackColor = colors.separator,
        )
    }
}

@Composable
private fun StepLabel(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatUiColors.current
    Text(
        text = text,
        modifier = modifier,
        style = Typography.footnote,
        color = if (active) colors.accent else colors.fgMuted,
    )
}

@Composable
private fun WelcomeHero() {
    val dimens = LocalDimens.current
    val colors = LocalChatUiColors.current
    val reducedMotion = LocalReducedMotion.current
    val enter = if (reducedMotion) {
        EnterTransition.None
    } else {
        fadeIn(animationSpec = tween(MotionSpecs.emphasizedMillis)) +
            slideInVertically(
                animationSpec = tween(MotionSpecs.emphasizedMillis),
                initialOffsetY = { it / 4 },
            )
    }

    AnimatedVisibility(visible = true, enter = enter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(OnboardingTestTags.Welcome),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            BrandMark(size = 88.dp)
            Text(
                text = "ChatUI",
                style = Typography.largeTitle,
                color = colors.fgPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Your keys. Your device. Your conversations.",
                style = Typography.title2,
                color = colors.fgPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "ChatUI is bring-your-own-key (BYOK). You connect your own " +
                    "OpenRouter API key, and your conversations, settings, and key " +
                    "stay on this device. Nothing is sent to a ChatUI server.",
                style = Typography.body,
                color = colors.fgSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "OpenRouter is recommended for this preview. You can add " +
                    "other providers from Providers after setup.",
                style = Typography.subhead,
                color = colors.fgMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProviderPicker(
    state: OnboardingUiState,
    onProviderSelected: (String) -> Unit,
) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier.testTag(OnboardingTestTags.ProviderPicker),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Choose a provider",
            style = MaterialTheme.typography.titleMedium,
        )

        state.providers.forEach { option ->
            ProviderCard(
                option = option,
                selected = option.id == state.selectedProviderId,
                onClick = { onProviderSelected(option.id) },
            )
        }
    }
}

@Composable
private fun ProviderCard(
    option: ProviderOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val haptics = rememberHaptics()
    val reducedMotion = LocalReducedMotion.current
    val container by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.bgSurfaceRaised,
        animationSpec = if (reducedMotion) {
            androidx.compose.animation.core.snap()
        } else {
            tween(MotionSpecs.fadeThroughMillis)
        },
        label = "providerCardColor",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.rowMinHeight)
            .semantics(mergeDescendants = true) {}
            .selectable(
                selected = selected,
                enabled = option.enabled,
                onClick = {
                    haptics.tick()
                    onClick()
                },
            )
            .testTag(OnboardingTestTags.providerOption(option.id)),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) colors.accent else colors.borderSubtle,
        ),
    ) {
        Row(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (selected) colors.accent else colors.bgSurface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label.take(1).uppercase(),
                    style = Typography.headline,
                    color = if (selected) colors.fgOnAccent else colors.fgSecondary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(dimens.space1),
            ) {
                Text(
                    text = option.label,
                    style = Typography.headline,
                    color = colors.fgPrimary,
                )
                if (option.recommended) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = colors.accent,
                                shape = RoundedCornerShape(dimens.radiusFull),
                            )
                            .padding(horizontal = dimens.space2, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Recommended",
                            style = Typography.caption,
                            color = colors.fgOnAccent,
                        )
                    }
                }
                option.statusLabel?.let { status ->
                    Text(
                        text = status,
                        style = Typography.footnote,
                        color = colors.fgMuted,
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun KeyEntryHeader(state: OnboardingUiState) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier.testTag(OnboardingTestTags.KeyEntry),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Connect OpenRouter",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Your key is stored on this device and is never sent to a " +
                "ChatUI server.",
        )
        state.selectedProvider?.let { provider ->
            Text(
                text = "Provider: ${provider.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyEntryForm(
    state: OnboardingUiState,
    onApiKeyChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val reducedMotion = LocalReducedMotion.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val enabled = !state.testing && !state.connected
    var keyVisible by remember { mutableStateOf(false) }
    var keyFocused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.bgSurfaceRaised),
        border = BorderStroke(
            width = if (keyFocused) 2.dp else 1.dp,
            color = if (keyFocused) colors.accent else colors.borderSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKeyChanged,
                label = { Text("OpenRouter API key") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                isError = state.error != null,
                supportingText = {
                    if (state.apiKey.isBlank()) {
                        Text("Paste your OpenRouter key to continue.")
                    } else if (state.error != null) {
                        Text("Check the key and try again.")
                    }
                },
                trailingIcon = {
                    IconButton(
                        onClick = { keyVisible = !keyVisible },
                        enabled = enabled,
                        modifier = Modifier.testTag(OnboardingTestTags.ShowKeyToggle),
                    ) {
                        Icon(
                            imageVector = if (keyVisible) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                Icons.Rounded.Visibility
                            },
                            contentDescription = if (keyVisible) "Hide key" else "Show key",
                        )
                    }
                },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { keyFocused = it.isFocused }
                    .testTag(OnboardingTestTags.ApiKeyField),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        clipboard.getText()?.text?.let(onApiKeyChanged)
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag(OnboardingTestTags.PasteKey),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(dimens.space1))
                    Text("Paste")
                }
                TextButton(
                    onClick = { uriHandler.openUri("https://openrouter.ai/keys") },
                    modifier = Modifier.testTag(OnboardingTestTags.HelpLink),
                ) {
                    Text("Where do I find this?")
                }
            }

            OutlinedTextField(
                value = state.label,
                onValueChange = onLabelChanged,
                label = { Text("Label (optional)") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OnboardingTestTags.LabelField),
            )

            Button(
                onClick = onConnect,
                enabled = state.canConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(OnboardingTestTags.ConnectButton),
            ) {
                AnimatedContent(
                    targetState = when {
                        state.testing -> "Testing"
                        state.connected -> "Connected"
                        else -> "Connect"
                    },
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(animationSpec = tween(MotionSpecs.fadeThroughMillis)) togetherWith
                                fadeOut(animationSpec = tween(MotionSpecs.fadeThroughMillis))
                        }
                    },
                    label = "connectButton",
                ) { label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimens.space2),
                    ) {
                        when (label) {
                            "Testing" -> CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )

                            "Connected" -> Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                            )

                            else -> Unit
                        }
                        Text(label)
                    }
                }
            }

            if (state.testing) {
                Row(
                    modifier = Modifier.testTag(OnboardingTestTags.TestingStatus),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(dimens.space2))
                    Text("Testing key…")
                }
            }

            if (state.connected) {
                Text(
                    text = "Connected. Opening a new chat…",
                    modifier = Modifier.testTag(OnboardingTestTags.ConnectedStatus),
                )
            }

            state.notice?.let { notice ->
                Text(
                    text = notice,
                    modifier = Modifier.testTag(OnboardingTestTags.Notice),
                )
            }

            state.error?.let { error ->
                ErrorCard(error)
            }

            TextButton(
                onClick = onBack,
                enabled = enabled,
                modifier = Modifier.testTag(OnboardingTestTags.BackButton),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun ErrorCard(error: OnboardingErrorCopy) {
    val dimens = LocalDimens.current
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.ErrorCard),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(dimens.space3),
            verticalArrangement = Arrangement.spacedBy(dimens.space1),
        ) {
            Text(
                text = error.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = error.message,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            error.details?.let { details ->
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.testTag(OnboardingTestTags.ErrorDetailsToggle),
                ) {
                    Text(if (showDetails) "Hide details" else "Details")
                }
                if (showDetails) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.testTag(OnboardingTestTags.ErrorDetails),
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogSection(
    catalog: CatalogUiState,
    onRetryCatalog: () -> Unit,
) {
    val dimens = LocalDimens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.Catalog),
        verticalArrangement = Arrangement.spacedBy(dimens.space2),
    ) {
        Text(
            text = "Available models",
            style = MaterialTheme.typography.titleMedium,
        )

        when (catalog) {
            CatalogUiState.Loading -> SkeletonList(
                modifier = Modifier.testTag(OnboardingTestTags.CatalogLoading),
                count = 3,
            )

            is CatalogUiState.Error -> ErrorState(
                title = "Could not load models",
                body = catalog.message,
                onRetry = onRetryCatalog,
                retryModifier = Modifier.testTag(OnboardingTestTags.RetryCatalog),
                modifier = Modifier.testTag(OnboardingTestTags.CatalogError),
            )

            is CatalogUiState.Ready -> if (catalog.models.isEmpty()) {
                EmptyState(
                    title = "No models returned",
                    body = "You can still connect; models will load after validation.",
                    modifier = Modifier.testTag(OnboardingTestTags.CatalogEmpty),
                )
            } else {
                CatalogList(catalog.models)
            }
        }
    }
}

@Composable
private fun CatalogList(models: List<CatalogModel>) {
    val dimens = LocalDimens.current
    var showAll by remember(models) { mutableStateOf(false) }
    val visible = if (showAll) models else models.take(COLLAPSED_MODEL_COUNT)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.CatalogList),
        verticalArrangement = Arrangement.spacedBy(dimens.space1),
    ) {
        visible.forEach { model ->
            CatalogModelRow(model)
        }
        if (models.size > COLLAPSED_MODEL_COUNT) {
            TextButton(
                onClick = { showAll = !showAll },
                modifier = Modifier.testTag(OnboardingTestTags.CatalogDisclosure),
            ) {
                Text(
                    if (showAll) {
                        "Show fewer models"
                    } else {
                        "Show all ${models.size} models"
                    },
                )
            }
        }
    }
}

@Composable
private fun CatalogModelRow(model: CatalogModel) {
    val dimens = LocalDimens.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = model.remoteId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (model.isFree) {
            Text(
                text = "Free",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CompletionSection(onStartChatting: () -> Unit) {
    val colors = LocalChatUiColors.current
    val dimens = LocalDimens.current
    val reducedMotion = LocalReducedMotion.current
    var appeared by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.6f,
        animationSpec = if (reducedMotion) tween(0) else spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "completionCheckScale",
    )

    LaunchedEffect(Unit) {
        appeared = true
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OnboardingTestTags.Completion),
        colors = CardDefaults.cardColors(containerColor = colors.accentSoft),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.sectionGap),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.space3),
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier
                    .size(56.dp)
                    .scale(scale),
            )
            Text(
                text = "You're all set",
                style = Typography.title2,
                color = colors.fgPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Your key is saved on this device. Start chatting whenever " +
                    "you're ready.",
                style = Typography.body,
                color = colors.fgSecondary,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onStartChatting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = dimens.touchTargetMin)
                    .testTag(OnboardingTestTags.StartChattingButton),
            ) {
                Text("Start chatting")
            }
        }
    }
}

private const val COLLAPSED_MODEL_COUNT = 3
