//! Shared views, design tokens, router, and view models.
//!
//! The UI foundation is framework-independent where possible. Slint is used
//! only for the shell component and the desktop entry point.

pub mod accessibility;
pub mod chat;
pub mod code;
pub mod conversations;
pub mod data;
pub mod markdown;
pub mod math;
pub mod models;
pub mod onboarding;
pub mod providers;
pub mod settings;
pub mod shell;
pub mod theme;
pub mod tokens;

#[cfg(test)]
mod accessibility_tests;
#[cfg(test)]
mod code_tests;
#[cfg(test)]
mod markdown_tests;
#[cfg(test)]
mod math_tests;

pub use accessibility::{
    AccessibleRole, Announcement, FocusOrder, FocusRing, Focusable, Key, KeyboardAction,
    LiveRegionPoliteness, Modifiers,
};
pub use chat::{
    AttachmentChip, AttachmentError, AttachmentFileInfo, AttachmentValidationCode,
    AttachmentValidationError, ChatErrorCopy, ChatRunInput, ChatRunResult, ChatRunner,
    ChatRunnerOptions, ChatSendFailure, ChatSendFailureReason, ChatSendResult, ChatService,
    ChatServiceError, ChatStreamAdapter, Clock, ComposerController, ComposerKeyAction,
    ComposerSend, ComposerState, DEFAULT_COMPOSER_LINE_HEIGHT, DRAFT_KEY_PREFIX, DraftPersistence,
    DraftStore, IdGenerator, MAX_ANNOUNCEMENT_CHARS, MAX_COMPOSER_LINES, MAX_DRAFT_LENGTH,
    MAX_DRAFTS, MAX_FILE_NAME_LENGTH, MAX_IMAGE_BYTES, MAX_IMAGE_EDGE, MAX_TEXT_BYTES,
    MemoryDraftStore, MemoryStreamingStore, ModelCapabilities, OUTPUT_QUALITY, PendingAttachment,
    PersistentDraftStore, ProcessedImage, ProviderChatAdapter, RegenerateInput, RetryInput,
    RunRequest, RunnerState, SUPPORTED_IMAGE_MIME_TYPES, SUPPORTED_TEXT_EXTENSIONS,
    SUPPORTED_TEXT_MIME_TYPES, SettingsDraftPersistence, StreamingState, StreamingStore,
    SystemClock, ValidatedAttachmentFile, build_chat_request, can_retry, clipboard_images,
    describe_chat_error, dropped_files, file_extension, format_attachment_size, has_partial_output,
    initial_announcement, output_mime_type_for, process_image, reduce_stream, sanitize_file_name,
    scaled_dimensions, store_pending_attachment, streaming_announcement, sync_composer_window,
    validate_attachment_file,
};
pub use code::{
    CodeBlock, CodeBlockView, CodeWrap, HighlightSpan, HighlightedCode, SUPPORTED_LANGUAGES,
    THEME_NAME, highlight_code, normalize_language,
};
pub use conversations::{
    ConversationFactory, ConversationHistory, ConversationNavigation, ConversationRoute,
    ConversationSearchError, ConversationSearchHit, ConversationSearchResult,
    ConversationSearchService, ConversationSearchState, DEFAULT_CONVERSATION_TITLE,
    DEFAULT_HISTORY_PAGE_SIZE, DEFAULT_SEARCH_LIMIT, DEFAULT_UNDO_WINDOW,
    DefaultConversationFactory, HISTORY_GROUP_ORDER, HistoryConfig, HistoryError, HistoryGroup,
    HistoryGroupKey, HistoryState, HistoryStatus, MAX_HISTORY_ROWS, MAX_SEARCH_LIMIT,
    MAX_SNIPPET_CHARS, format_conversation_timestamp, group_conversations, history_group_for_date,
    search_error,
};
pub use data::{
    CLEAR_ALL_CONFIRMATION, ClearRequest, ClearSummary, ConversationOption, DataBackupPlaceholder,
    DataController, DataError, DataErrorCopy, DataNavigation, DataOperation, DataSection,
    DataService, DataViewModel, DataWindow, EXPORT_EXCLUDES, EXPORT_INCLUDES, EXPORT_WARNING,
    ExportArtifact, ExportDocumentation, ExportFormat, ExportRequest, ExportSummary, ImportMode,
    ImportRequest, ImportSummaryView, KeyRowView, MemoryAttachment, MemoryData, MemoryDataService,
    ProviderKeySummary, RemoveKeyRequest, StorageUsage, StorageUsageEntry, USAGE_NOTE,
    UsageRowView, describe_data_error, format_bytes, sync_window as sync_data_window,
};
pub use markdown::{
    Block, ImageRef, Inline, LinkAction, LinkRef, RenderOptions, RenderedMarkdown, TableRow,
    close_incomplete_fence, render_markdown, render_markdown_default, sanitize_image_url,
    sanitize_link_url,
};
pub use math::{MAX_MATH_SOURCE_CHARS, MathSource, is_math_language, math_fallback};
pub use models::{
    ManualModelForm, ModelAvailability, ModelDiscovery, ModelGroup, ModelListViewModel,
    ModelManagementController, ModelManagementError, ModelManagementErrorCopy,
    ModelManagementService, ModelManagementViewModel, ModelManagementWindow, ModelPickerViewModel,
    ModelRepository, ModelRow, ProviderRepository, RefreshResult,
    SettingsStore as ModelSettingsStore, build_model_groups, capability_chip_label,
    describe_model_error, format_context_window, format_modalities, format_model_price,
    format_price_per_million, is_free_model, model_availability, model_matches_query,
    parse_capabilities, parse_context_window, source_label, sync_window as sync_model_window,
};
pub use onboarding::{
    CatalogLoad, CatalogSource, ConnectInput, ConnectResult, KeyValidator, ModelStore,
    OnboardingController, OnboardingError, OnboardingErrorCopy, OnboardingRoute, OnboardingService,
    OnboardingServiceLike, OnboardingStep, OnboardingViewModel, OnboardingWindow, ProviderStore,
    SettingsStore, describe_catalog_error, describe_onboarding_error, sync_window,
};
pub use providers::{
    ConfirmationAction, ConversationStore, CustomProviderForm, ModelStore as ProviderModelStore,
    PendingConfirmation, ProviderDetailViewModel, ProviderKeyMetadata, ProviderKeyValidator,
    ProviderListEntry, ProviderListViewModel, ProviderManagementController,
    ProviderManagementError, ProviderManagementErrorCopy, ProviderManagementService,
    ProviderManagementViewModel, ProviderManagementWindow,
    ProviderStore as ProviderManagementStore, SettingsStore as ProviderSettingsStore,
    ValidatedBaseUrl, cors_label, describe_provider_error, format_headers_text, format_rate_limit,
    format_usd, parse_headers_text, sync_window as sync_provider_window, validate_api_key,
    validate_auth_type, validate_base_url, validate_headers,
};
pub use settings::{
    APP_NAME, APP_VERSION, AboutInfo, BackupPlaceholder, DOCS_URL, MAX_SYSTEM_PROMPT_CHARS,
    MemoryProviderModelCatalog, MemorySettingsStore, ModelOption, ProviderModelCatalog,
    ProviderOption, RepositorySettingsPersistence, SettingAvailability, SettingDescriptor,
    SettingsController, SettingsError, SettingsErrorCopy, SettingsNavigation, SettingsPersistence,
    SettingsRoute, SettingsService, SettingsStore as SettingsRepositoryStore, SettingsViewModel,
    SettingsWindow, density_from_index, density_index, describe_settings_error,
    font_size_from_index, font_size_index, reasoning_from_index, reasoning_index,
    send_key_from_index, send_key_index, sync_window as sync_settings_window, theme_from_index,
    theme_index,
};
pub use shell::{Breakpoint, Destination, LayoutMode, ShellController, ShellState, ShellWindow};
pub use theme::{ReducedMotion, ResolvedTheme, ThemeSettings};
pub use tokens::{ChatTypography, DensityScale, Motion, Rgba, Tokens};
