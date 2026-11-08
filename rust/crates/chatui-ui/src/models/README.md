# Model management API and fallback behavior

This module implements the model management screen and the model picker from
spec sections 3.3, 7.8, and 11.5. It is framework-independent except for the
Slint view in `view.rs`.

## Layout

| File | Responsibility |
| --- | --- |
| `state.rs` | Pure view models, grouping, search, formatting, and availability rules |
| `service.rs` | Injected repositories, discovery refresh, manual add, pin/hide, default model |
| `error.rs` | Blocking failure types and plain-language copy |
| `view.rs` | Slint window, controller, and UI tests |

## Injected traits

The service never talks to SQLite or the network directly.

```rust
pub trait ModelRepository {
    fn list_all(&self) -> Result<Vec<Model>, ModelManagementError>;
    fn list_by_provider(&self, provider_id: &str) -> Result<Vec<Model>, ModelManagementError>;
    fn get(&self, id: &str) -> Result<Option<Model>, ModelManagementError>;
    fn insert(&self, model: &Model) -> Result<(), ModelManagementError>;
    fn update(&self, model: &Model) -> Result<(), ModelManagementError>;
    fn set_pinned(&self, id: &str, pinned: bool) -> Result<(), ModelManagementError>;
    fn set_hidden(&self, id: &str, hidden: bool) -> Result<(), ModelManagementError>;
}

pub trait ProviderRepository {
    fn list(&self) -> Result<Vec<Provider>, ModelManagementError>;
    fn get(&self, id: &str) -> Result<Option<Provider>, ModelManagementError>;
}

pub trait ModelDiscovery {
    fn discover(&self, provider: &Provider) -> Result<Vec<CatalogModel>, ChatError>;
}

pub trait SettingsStore {
    fn load(&self) -> Result<Settings, ModelManagementError>;
    fn save(&self, settings: &Settings) -> Result<(), ModelManagementError>;
}
```

`ModelDiscovery` implementations call the provider's model-list endpoint. They
never send a paid completion request and never receive a provider key from this
module.

## Service surface

| Method | Behavior |
| --- | --- |
| `list_models(provider_id)` | Builds the management list, including hidden rows, with pinned/hidden counts |
| `refresh(provider_id)` | Discovers and upserts the catalog; degrades to cached or fallback models |
| `set_pinned(model_id, pinned)` | Persists the pinned flag and returns the updated model |
| `set_hidden(model_id, hidden)` | Persists the hidden flag and returns the updated model |
| `add_manual_model(form)` | Validates and inserts a user-defined model |
| `picker(provider_id, query, include_hidden)` | Builds the picker groups, default indication, availability, and docs link |
| `set_default_model(model_id)` | Persists the global default provider and model |
| `default_model_id()` / `default_provider_id()` | Reads the persisted defaults |
| `providers()` | Lists providers for the controller |

## Refresh and fallback behavior

1. Call `ModelDiscovery::discover`.
2. On success, upsert by `(provider_id, remote_id)`.
   - Existing rows keep their local ID, `pinned`, `hidden`, and `source`.
   - Manual rows keep their user-provided label and metadata.
   - Models that disappear from the catalog are not deleted.
   - `remote_id` is copied from the catalog and never rewritten.
3. On failure, keep cached models and return a non-blocking notice:
   `Discovery unavailable; using N cached model(s).`
4. When the cache is empty, persist the preset fallback list from
   `fallback_model_ids_for` and `defaults_for_preset`, marked with
   `metadata.fallback = true` and `source = preset`.
5. When no fallback exists, return the discovery error. The view shows the
   error copy with a retry action.

A degraded refresh is never a send failure. The view shows the notice and keeps
the cached rows selectable.

## Search and grouping

- Search is case-insensitive and matches the label, `remote_id`, and canonical
  slug.
- Pinned models appear first in a dedicated `Pinned` group.
- Remaining models are grouped by provider and sorted by provider label.
- Models are sorted by label, then `remote_id`.
- Hidden models are excluded from the picker and included in the management
  screen.
- Distinct provider IDs are never deduplicated by canonical slug.

## Manual add validation

- `remote_id` is trimmed and must not be empty.
- `(provider_id, remote_id)` must be unique.
- A zero context window is rejected.
- An empty label defaults to `remote_id`.
- Empty capabilities default to `text`.
- Empty input/output modalities default to `text`.
- The record is stored with `source = manual`.

## Display rules

- Capability chips: `Text`, `Vision`, `Files`, `Tools`, `Reasoning`,
  `Streaming`.
- Context: `128K context`, `1M context`, or `Context unknown`.
- Modalities: `text, image in · text out`.
- Price: USD per million tokens, for example `$1.5 / M input · $6 / M output`.
- Free badge: `remote_id` ends with `:free`, or the effective input, output, and
  per-request price is zero.
- Source: `Discovered`, `Preset`, or `Manual`.
- Default model: marked with a `Default` badge and a highlighted row.
- Unavailable model: the picker shows a warning and keeps the user's selection
  instead of silently substituting another model.
- Docs link: the provider preset `docsUrl` from `contracts/providers.json`.

## Security

Model metadata never contains provider keys. The module does not read the
secret store, and the view never renders a key.
