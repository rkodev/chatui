//! Framework-independent onboarding state machine.
//!
//! The view model is deliberately free of Slint, storage, and network types.
//! It records the current step, the masked key form, the public catalog, the
//! selected default model, and the completion flag. The Slint controller in
//! [`crate::onboarding::view`] renders this state and forwards user actions.

#![allow(clippy::result_large_err)]

use std::fmt;

use chatui_contracts::ProviderPreset;
use chatui_domain::Model;
use chatui_providers::CatalogModel;

use crate::onboarding::error::{OnboardingError, OnboardingErrorCopy};

/// The onboarding state machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OnboardingStep {
    /// Welcome screen with OpenRouter preselected.
    Welcome,
    /// Key-entry form.
    KeyEntry,
    /// `GET /key` is in flight.
    Validating,
    /// Validation failed; the form is preserved.
    ValidatingFailed,
    /// The public catalog is loading.
    CatalogLoading,
    /// The catalog is loaded and the default model is being selected.
    ModelSelection,
    /// Onboarding is complete and sending is enabled.
    Complete,
}

impl OnboardingStep {
    /// Stable index used by the Slint view.
    pub const fn index(self) -> i32 {
        match self {
            Self::Welcome => 0,
            Self::KeyEntry => 1,
            Self::Validating => 2,
            Self::ValidatingFailed => 3,
            Self::CatalogLoading => 4,
            Self::ModelSelection => 5,
            Self::Complete => 6,
        }
    }

    /// Human-readable label for diagnostics and tests.
    pub const fn label(self) -> &'static str {
        match self {
            Self::Welcome => "welcome",
            Self::KeyEntry => "key-entry",
            Self::Validating => "validating",
            Self::ValidatingFailed => "validating-failed",
            Self::CatalogLoading => "catalog-loading",
            Self::ModelSelection => "model-selection",
            Self::Complete => "complete",
        }
    }
}

/// Onboarding view state.
///
/// `key_input` is the editable form value. It is never included in `Debug`
/// output, and [`OnboardingViewModel::masked_key`] is the only representation
/// intended for display outside the password field.
#[derive(Clone, PartialEq)]
pub struct OnboardingViewModel {
    step: OnboardingStep,
    preset_id: String,
    preset_label: String,
    recommended: bool,
    key_input: String,
    key_label: String,
    remember_key: bool,
    catalog: Vec<CatalogModel>,
    catalog_notice: Option<String>,
    selected_model: Option<Model>,
    default_model_notice: Option<String>,
    error: Option<OnboardingErrorCopy>,
    provider_id: Option<String>,
    model_id: Option<String>,
    onboarding_completed: bool,
    can_skip: bool,
}

impl fmt::Debug for OnboardingViewModel {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("OnboardingViewModel")
            .field("step", &self.step)
            .field("preset_id", &self.preset_id)
            .field("preset_label", &self.preset_label)
            .field("recommended", &self.recommended)
            .field("key_input", &"[REDACTED]")
            .field("key_label", &self.key_label)
            .field("remember_key", &self.remember_key)
            .field("catalog_len", &self.catalog.len())
            .field("catalog_notice", &self.catalog_notice)
            .field(
                "selected_model",
                &self.selected_model.as_ref().map(|model| &model.remote_id),
            )
            .field("default_model_notice", &self.default_model_notice)
            .field("error", &self.error)
            .field("provider_id", &self.provider_id)
            .field("model_id", &self.model_id)
            .field("onboarding_completed", &self.onboarding_completed)
            .field("can_skip", &self.can_skip)
            .finish()
    }
}

impl OnboardingViewModel {
    /// Create a view model for a provider preset.
    pub fn from_preset(preset: &ProviderPreset) -> Self {
        Self::new(
            preset.id.clone(),
            preset.label.clone(),
            preset.is_default || preset.id == "openrouter",
        )
    }

    /// Create a view model with explicit preset metadata.
    pub fn new(
        preset_id: impl Into<String>,
        preset_label: impl Into<String>,
        recommended: bool,
    ) -> Self {
        Self {
            step: OnboardingStep::Welcome,
            preset_id: preset_id.into(),
            preset_label: preset_label.into(),
            recommended,
            key_input: String::new(),
            key_label: String::new(),
            remember_key: true,
            catalog: Vec::new(),
            catalog_notice: None,
            selected_model: None,
            default_model_notice: None,
            error: None,
            provider_id: None,
            model_id: None,
            onboarding_completed: false,
            can_skip: false,
        }
    }

    /// Current step.
    pub fn step(&self) -> OnboardingStep {
        self.step
    }

    /// Preselected preset ID.
    pub fn preset_id(&self) -> &str {
        &self.preset_id
    }

    /// Preselected preset label.
    pub fn preset_label(&self) -> &str {
        &self.preset_label
    }

    /// Whether the preset is labeled recommended.
    pub fn recommended(&self) -> bool {
        self.recommended
    }

    /// Editable key value. Callers must not log this value.
    pub fn key_input(&self) -> &str {
        &self.key_input
    }

    /// Optional key label.
    pub fn key_label(&self) -> &str {
        &self.key_label
    }

    /// Whether the key should be remembered.
    pub fn remember_key(&self) -> bool {
        self.remember_key
    }

    /// Public catalog loaded before or during onboarding.
    pub fn catalog(&self) -> &[CatalogModel] {
        &self.catalog
    }

    /// Non-blocking catalog notice.
    pub fn catalog_notice(&self) -> Option<&str> {
        self.catalog_notice.as_deref()
    }

    /// Selected default model, available after completion.
    pub fn selected_model(&self) -> Option<&Model> {
        self.selected_model.as_ref()
    }

    /// Non-blocking default-model notice.
    pub fn default_model_notice(&self) -> Option<&str> {
        self.default_model_notice.as_deref()
    }

    /// Current validation error copy.
    pub fn error(&self) -> Option<&OnboardingErrorCopy> {
        self.error.as_ref()
    }

    /// Persisted provider ID, available after completion.
    pub fn provider_id(&self) -> Option<&str> {
        self.provider_id.as_deref()
    }

    /// Persisted default model ID, available after completion.
    pub fn model_id(&self) -> Option<&str> {
        self.model_id.as_deref()
    }

    /// Whether onboarding has completed.
    pub fn onboarding_completed(&self) -> bool {
        self.onboarding_completed
    }

    /// Whether sending is allowed.
    ///
    /// Sending requires a completed onboarding, a persisted provider, and a
    /// persisted default model. Skipping without a provider/key therefore keeps
    /// sending disabled.
    pub fn can_send(&self) -> bool {
        self.onboarding_completed && self.provider_id.is_some() && self.model_id.is_some()
    }

    /// Whether the skip action is available.
    ///
    /// The spec does not allow skipping first-run setup, so this is false by
    /// default. It can be enabled for re-entry when a provider/key already
    /// exists.
    pub fn can_skip(&self) -> bool {
        self.can_skip
    }

    /// Masked representation of the key for display outside the password field.
    pub fn masked_key(&self) -> String {
        if self.key_input.is_empty() {
            String::new()
        } else {
            "•".repeat(self.key_input.chars().count().min(12))
        }
    }

    /// Move from the welcome screen to key entry.
    pub fn begin_key_entry(&mut self) {
        if self.step == OnboardingStep::Welcome {
            self.step = OnboardingStep::KeyEntry;
        }
    }

    /// Update the editable key value.
    pub fn set_key_input(&mut self, value: impl Into<String>) {
        self.key_input = value.into();
        if self.step == OnboardingStep::Welcome {
            self.step = OnboardingStep::KeyEntry;
        }
    }

    /// Update the optional key label.
    pub fn set_key_label(&mut self, value: impl Into<String>) {
        self.key_label = value.into();
    }

    /// Update the remember-key preference.
    pub fn set_remember_key(&mut self, value: bool) {
        self.remember_key = value;
    }

    /// Enter the validating state and clear any previous error.
    pub fn start_validation(&mut self) {
        self.step = OnboardingStep::Validating;
        self.error = None;
    }

    /// Record a validation failure while preserving the form values.
    pub fn validation_failed(&mut self, error: OnboardingErrorCopy) {
        self.step = OnboardingStep::ValidatingFailed;
        self.error = Some(error);
    }

    /// Enter the catalog-loading state.
    pub fn catalog_loading(&mut self) {
        self.step = OnboardingStep::CatalogLoading;
    }

    /// Enter the model-selection state.
    pub fn model_selection(&mut self) {
        self.step = OnboardingStep::ModelSelection;
    }

    /// Store the public catalog without changing the current step.
    pub fn set_catalog(&mut self, models: Vec<CatalogModel>, notice: Option<String>) {
        self.catalog = models;
        self.catalog_notice = notice;
    }

    /// Store the public catalog and move to model selection.
    pub fn catalog_loaded(&mut self, models: Vec<CatalogModel>, notice: Option<String>) {
        self.set_catalog(models, notice);
        if self.step == OnboardingStep::CatalogLoading {
            self.step = OnboardingStep::ModelSelection;
        }
    }

    /// Record a non-blocking catalog failure and keep the key-entry form.
    pub fn catalog_failed(&mut self, notice: impl Into<String>) {
        self.catalog_notice = Some(notice.into());
        if self.step == OnboardingStep::CatalogLoading {
            self.step = OnboardingStep::KeyEntry;
        }
    }

    /// Store the selected default model.
    pub fn set_selected_model(&mut self, model: Model) {
        self.model_id = Some(model.id.clone());
        self.selected_model = Some(model);
    }

    /// Complete onboarding with the persisted provider and model.
    pub fn complete(
        &mut self,
        provider_id: impl Into<String>,
        model_id: impl Into<String>,
        selected_model: Model,
        notice: Option<String>,
    ) {
        self.provider_id = Some(provider_id.into());
        self.model_id = Some(model_id.into());
        self.selected_model = Some(selected_model);
        self.default_model_notice = notice;
        self.error = None;
        self.onboarding_completed = true;
        self.step = OnboardingStep::Complete;
    }

    /// Enable or disable the skip action.
    pub fn set_can_skip(&mut self, value: bool) {
        self.can_skip = value;
    }

    /// Skip onboarding only when a provider/key already exists.
    ///
    /// First-run setup cannot be skipped because the spec requires a key to
    /// reach a working chat. Sending stays disabled until a provider and model
    /// are configured.
    pub fn skip(&mut self) -> Result<(), OnboardingError> {
        if !self.can_skip {
            return Err(OnboardingError::NotConfigured(
                "Connect a provider and key before skipping onboarding.".to_owned(),
            ));
        }

        self.onboarding_completed = true;
        self.step = OnboardingStep::Complete;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chatui_contracts::provider_catalog;
    use chatui_domain::{InputModality, ModelCapability, ModelSource, OutputModality, Timestamp};

    fn preset() -> ProviderPreset {
        provider_catalog()
            .get("openrouter")
            .expect("openrouter preset")
            .clone()
    }

    fn model(remote_id: &str) -> Model {
        let now = Timestamp::now();
        Model {
            id: format!("model-{remote_id}"),
            provider_id: "provider-1".to_owned(),
            remote_id: remote_id.to_owned(),
            label: remote_id.to_owned(),
            context_window: Some(128_000),
            max_output_tokens: Some(4096),
            capabilities: vec![ModelCapability::Text],
            pricing: None,
            canonical_slug: None,
            input_modalities: vec![InputModality::Text],
            output_modalities: vec![OutputModality::Text],
            supported_parameters: Vec::new(),
            metadata: None,
            hidden: false,
            pinned: false,
            source: ModelSource::Discovered,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn openrouter_is_preselected_and_recommended() {
        let view_model = OnboardingViewModel::from_preset(&preset());

        assert_eq!(view_model.preset_id(), "openrouter");
        assert_eq!(view_model.preset_label(), "OpenRouter");
        assert!(view_model.recommended());
        assert_eq!(view_model.step(), OnboardingStep::Welcome);
    }

    #[test]
    fn state_machine_transitions_in_order() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());

        view_model.begin_key_entry();
        assert_eq!(view_model.step(), OnboardingStep::KeyEntry);

        view_model.start_validation();
        assert_eq!(view_model.step(), OnboardingStep::Validating);

        view_model.catalog_loading();
        assert_eq!(view_model.step(), OnboardingStep::CatalogLoading);

        view_model.catalog_loaded(Vec::new(), None);
        assert_eq!(view_model.step(), OnboardingStep::ModelSelection);

        view_model.complete("provider-1", "model-1", model("openrouter/auto"), None);
        assert_eq!(view_model.step(), OnboardingStep::Complete);
        assert!(view_model.onboarding_completed());
    }

    #[test]
    fn validation_failure_preserves_the_form() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());
        view_model.set_key_input("sk-test-not-a-real-key");
        view_model.set_key_label("Personal");
        view_model.start_validation();
        view_model.validation_failed(OnboardingErrorCopy {
            title: "API key rejected".to_owned(),
            message: "Check the key".to_owned(),
            details: None,
        });

        assert_eq!(view_model.step(), OnboardingStep::ValidatingFailed);
        assert_eq!(view_model.key_input(), "sk-test-not-a-real-key");
        assert_eq!(view_model.key_label(), "Personal");
        assert!(view_model.error().is_some());
    }

    #[test]
    fn masked_key_never_contains_the_key() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());
        view_model.set_key_input("sk-test-not-a-real-key");

        let masked = view_model.masked_key();
        assert!(!masked.contains("sk-test-not-a-real-key"));
        assert!(!masked.is_empty());
    }

    #[test]
    fn debug_redacts_the_key() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());
        view_model.set_key_input("sk-test-not-a-real-key");

        let rendered = format!("{view_model:?}");
        assert!(!rendered.contains("sk-test-not-a-real-key"));
        assert!(rendered.contains("[REDACTED]"));
    }

    #[test]
    fn sending_is_disabled_until_complete() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());
        assert!(!view_model.can_send());

        view_model.complete("provider-1", "model-1", model("openrouter/auto"), None);
        assert!(view_model.can_send());
    }

    #[test]
    fn skip_is_rejected_without_existing_configuration() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());

        let error = view_model.skip().expect_err("skip is not allowed");
        assert!(error.message().contains("Connect a provider"));
        assert_eq!(view_model.step(), OnboardingStep::Welcome);
        assert!(!view_model.can_send());
    }

    #[test]
    fn skip_completes_but_keeps_sending_disabled_without_a_provider() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());
        view_model.set_can_skip(true);

        view_model.skip().expect("skip is allowed");
        assert_eq!(view_model.step(), OnboardingStep::Complete);
        assert!(view_model.onboarding_completed());
        assert!(!view_model.can_send());
    }

    #[test]
    fn catalog_failure_keeps_the_key_entry_form() {
        let mut view_model = OnboardingViewModel::from_preset(&preset());
        view_model.begin_key_entry();
        view_model.catalog_loading();
        view_model.catalog_failed("Could not load the model catalog.");

        assert_eq!(view_model.step(), OnboardingStep::KeyEntry);
        assert_eq!(
            view_model.catalog_notice(),
            Some("Could not load the model catalog.")
        );
    }
}
