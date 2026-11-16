# Android minimalist design and UX implementation plan

**Status:** Proposed; implementation has not started.
**Review date:** 2026-11-16.
**Scope:** The native application in `android`.
**Objective:** Make ChatUI calmer, more beautiful, easier to understand, and
comfortable on entry-level Android phones without removing functionality.

## 1. Recommended direction

Adopt **quiet utility**: content-first chat, one clear primary action, restrained
blue accents, readable typography, flat surfaces, and advanced controls revealed
only when relevant. Beauty should come from proportion, alignment, contrast, and
consistency, not additional cards, animations, or decorative effects.

The highest-value changes are:

1. Make Chats a useful full-screen destination on phones instead of a
   placeholder pointing to a closed drawer.
2. Reduce the chat header to history access, title/model selection, and one
   overflow menu. Keep the composer immediately understandable.
3. Give model selection a direct, correctly wired entry point; separate
   choosing a model from configuring a provider.
4. Move advanced forms, account diagnostics, and destructive operations out of
   primary reading and setup flows, keeping them clearly discoverable.
5. Spend fewer resources on continuous animation and repeated layout work,
   while preserving rich content, attachments, streaming, and backup.

This is an implementation plan, not an instruction to rewrite the application.
Keep Compose, Material 3, the existing modules, ViewModels, repositories, and
shared contracts. No database or provider-protocol changes are required for
the proposed visual structure.

### Relationship to existing documents

[IMPLEMENTATION.md](IMPLEMENTATION.md) remains authoritative for product
behavior, security, provider contracts, and cross-client parity.
[android-ux.md](android-ux.md) is an earlier polish proposal, parts of which are
already implemented. This plan refines its visual direction: do not interpret
that document as requiring more avatars, shimmer, large titles, shadows, or
animation on every state change.

Do not silently change model choice, pricing behavior, privacy preferences,
attachment limits, stored data, or backup scheduling during a visual redesign.
Any functional integration described below must be explicitly tested and
reviewed rather than hidden inside a screenshot-only change.

## 2. Current application assessment

### Review basis and limits

The assessment covers the production shell, all feature-screen families,
design-system components, relevant settings and navigation wiring, and
representative committed light/dark screenshot goldens.

The goldens are under `android\app\src\test\screenshots`. The reviewed examples
include populated chat, conversation history, onboarding key entry, provider
detail, model picker, settings root, and the compact drawer fixture.

These are existing fixtures, not fresh device captures. In particular,
`ScreenshotTest.kt` composes chat independently of the production shell, and
its `ScreenshotSidebar` is not the production `ShellSidebar`. Production
navigation findings therefore come from tracing the application code, not
assuming those screenshots show the complete application.

No physical-device usability study or runtime performance measurement was
performed for this document. Performance risks below are code-based findings;
the budgets in section 7 are proposed implementation acceptance targets.

For source references below, use these repository-relative roots:

| Reference | Source root |
| --- | --- |
| Shell | `android\app\src\main\java\dev\chatui\shell` |
| Design system | `android\core\designsystem\src\main\kotlin\dev\chatui\core\designsystem` |
| Feature `<name>` | `android\feature\<name>\src\main\kotlin\dev\chatui\feature\<name>` |
| Settings storage | `android\core\datastore\src\main\kotlin\dev\chatui\core\datastore\settings` |
| Screenshot tests | `android\app\src\test\java\dev\chatui\screenshots` |

### What should be retained

The app is not an unstyled scaffold. It already has semantic colors and
spacing, real Material icons, grouped settings rows, a floating composer,
attachment sheets, message grouping, reasoning disclosure, code-copy and wrap
controls, skeletons, banners, snackbar undo, haptic helpers, reduced-motion
support, and navigation transitions.

Its technical foundations also support the intended direction: local storage,
persistent text drafts, older-message loading, stable chat-list keys and
content types, a separate streaming row, display-sized local image requests,
an adaptive sidebar at 840 dp, and existing Compose/Roborazzi coverage.

Encrypted backup is now implemented and routed, including optional onboarding
setup. It must not be treated as a future placeholder merely because older
documentation and some screen copy still describe it that way.

### Prioritized findings

| Priority | Current finding and evidence | Design response |
| --- | --- | --- |
| P0 | The Conversations route always renders `ConversationsPlaceholder`; history lives in the sidebar. On compact widths that sidebar is closed initially. See Shell `ShellNavHost.kt:157-160` and `AdaptiveShell.kt:332`. | Render history as the main Chats screen on compact widths. Reserve the select-a-chat placeholder for expanded layouts with visible history. |
| P0 | The chat model affordance publishes `onOpenModelPicker = providersCallback`, which ultimately opens Providers rather than a model picker. See Feature chat `ChatScreen.kt:196`, `ChatRoute.kt`, and Shell `ShellNavHost.kt`. | Introduce a distinct model-picker callback and present the existing picker for the current conversation. Keep provider-repair actions separate. |
| P1 | The shell passes a separate, unconnected `rememberScrollState()` to a title bar whose expanded minimum is 96 dp. It does not follow the actual feature list. See Shell `ChatUiApp.kt:224` and Design system `ChatUiScaffold.kt:112-114`. | Use a compact, stable chat toolbar; do not make available reading space depend on an unwired collapse effect. |
| P1 | The production sidebar has its own New chat button, then embeds a conversation list with another New chat action, followed by Chats/Library/Settings groups. See Shell `AdaptiveShell.kt:179-217` and Feature conversations `ConversationListScreen.kt`. | One New chat owner per history surface; give history most of the height and reduce the footer to Providers and Settings. |
| P1 | Share, conversation settings, and keyboard-shortcuts help compete for chat-toolbar width. Provider list also supplies an in-content Providers heading below shell chrome. See Shell `ChatUiApp.kt` and Feature providers `ProviderListScreen.kt`. | One contextual overflow menu; one screen-title owner. Move keyboard help out of permanent touch-first chrome. |
| P1 | Both message roles use a 78% bubble-width limit plus group avatars. Actions, timestamps, and model/provider metadata add separate rows. See Feature chat `ChatMessageItem.kt:222` and `ChatMessageActions.kt`. | Give assistant answers nearly the full reading width; retain a restrained user bubble and consolidate secondary information. |
| P1 | Attachment warnings are rendered above the transcript and again inside the composer. See Feature chat `ChatScreen.kt:519` and `ChatComposer.kt:211`. | Show the warning once, next to the affected pending attachments. Preserve the send-anyway confirmation. |
| P1 | Full picker/model-management/routing components exist, but their public composables have no production callers. Current settings use simpler model dropdowns. See Feature providers `ConversationModelControl.kt`, `ManageModelsScreen.kt`, `DefaultsScreen.kt`, `ConversationRoutingControls.kt`, and Shell `ShellNavHost.kt`. | Connect existing controls through intentional entry points instead of building another competing picker. Distinguish this integration work from already reachable features. |
| P1 | Conversation settings, provider diagnostics, and backup configuration expose many advanced fields before the user's immediate task. See Feature providers `ConversationSettingsSheet.kt`, `ProviderDetailScreen.kt`, and Feature backup `BackupSettingsScreen.kt`. | Summary-first screens, focused editors, and explicit Advanced disclosures with active-value summaries. |
| P1 | Persisted dynamic-color and haptic preferences have UI controls, but `ChatUiApp` forwards only theme and density to `ChatUiTheme`. Code highlighting separately reads the system theme. | Complete preference wiring and make syntax colors follow the resolved application theme. See Settings storage `AndroidSettings.kt`, Shell `ChatUiApp.kt:103`, and Feature chat `ChatCodeBlock.kt`. |
| P1 | Local image display is bounded, but remote Markdown images use `BitmapFactory.decodeStream` without a target size. Attachment URI bytes are read synchronously from picker callbacks. | Reuse a bounded image-loading path and move attachment ingestion off the main thread. These are resource risks, not measured crash/jank claims. See Feature chat `ChatMarkdown.kt` and `ChatScreen.kt`. |
| P2 | Settings enqueue success snackbars for settings emissions; data screens duplicate some inline/snackbar feedback. Data copy says backup is unavailable, while a real backup route exists. Remote-image copy also conflicts with the current default setting. | Quiet save feedback and accurate state-specific copy. See Feature settings `SettingsScreens.kt:218,386,621`, `DataSettingsScreen.kt:341`, and Settings storage `AndroidSettings.kt:28`. |

The disabled "Export conversation (coming soon)" button in
`ConversationSettingsSheet.kt` is not a working export flow. Do not count it
as implemented functionality or make it prominent in the redesign. Working
conversation sharing and all-data export must remain available.

## 3. Visual design contract

### Hierarchy and visual restraint

- One filled primary action for the current task. Refresh, documentation,
  configuration, and destructive actions should not all look primary.
- White/near-black canvas, subtle neutral input surfaces, and a single blue
  accent. Use semantic warning/error colors only when meaningful.
- Prefer sections separated by spacing or a subtle divider. Avoid a card
  inside another card around ordinary text.
- Remove repeated branding and decorative message avatars from the normal
  transcript. Preserve explicit role labels and screen-reader semantics.
- Keep native sheets, dialogs, focus treatment, and predictive-back behavior.
  The result should feel like a careful Android app, not an imitation of iOS.

### Typography, spacing, and shape

| Element | Proposed treatment |
| --- | --- |
| Chat toolbar title | Approximately 20 sp semibold, one line at ordinary font scale; no 34 sp large title on chat. |
| Root screen title | 22-24 sp semibold; render once, normally in the toolbar. |
| Chat body | Keep existing 14/16/18 sp user choices and their line heights. The default remains 16/24; respect system font scaling. |
| Secondary information | Usually 13-14 sp; use words, not only color or tiny symbols, for important state. |
| Code | Preserve the existing readable monospace scale and exact-text copy. |
| Horizontal margins | 16 dp normally, 12 dp at very narrow widths; use existing density preferences consistently. |
| Section spacing | Approximately 24 dp, reduced where needed on short screens; avoid stacking several independent padding layers. |
| Controls | At least 48 x 48 dp interactive targets, including in compact density. Visual icons may be 20-24 dp. |
| Rows | Usually 56-64 dp for two lines, but grow for long text and large fonts. Do not enforce clipping fixed heights. |
| Shape | Restrained 12-16 dp sections and approximately 20 dp composer/user-bubble corners. No decorative tails required. |
| Depth | Flat screen content; tonal separation for sheets and, at most, one subtle composer boundary. No backdrop blur. |

Keep the existing semantic token names. Retain the current neutral palettes
initially; fix hierarchy before undertaking a broad color change.

**Proposed accent correction:** use `#2563EB` in light mode and `#8AB4F8` in dark
mode, with `#FFFFFF` and `#0B1020` respectively for text on filled accent
controls. Solid-color contrast calculations give:

| Pair | Contrast |
| --- | --- |
| Current light `#007AFF` against white | 4.02:1; insufficient for normal-size text. |
| Proposed light `#2563EB` against white | 5.17:1. |
| Proposed dark `#8AB4F8` against current raised `#2C2C2E` | 6.61:1. |
| Proposed dark button text `#0B1020` against `#8AB4F8` | 8.98:1. |

These calculations do not certify every rendered state. Check disabled,
pressed, selected, warning, code, translucent, and dynamic-color combinations
separately. Dynamic color must use the matching on-color roles.

### Responsive layout

Width and device capability are separate concerns: a small viewport does not
necessarily mean low RAM, and a large phone is not necessarily fast.

| Available width | Layout |
| --- | --- |
| Below 600 dp | Single-column screens; full-screen Chats root; modal history drawer from chat. |
| 600-839 dp | Same navigation model, with centered/bounded content rather than stretched forms. |
| 840 dp and above | Preserve the permanent sidebar, initially its existing 280 dp width; main conversation beside it. |

Bound the transcript/composer to roughly 680-760 dp on wide windows and forms
to roughly 560-600 dp. Let narrow phones use their available width. Adjust to
window resizing and short landscape height, not only portrait device classes.

## 4. Information architecture and placement

Keep the current drawer-based conversation model. **Do not add persistent
bottom navigation:** frequent work happens in chat, and another permanent bar
would compete with the composer and keyboard on short screens.

```text
Chats
  New chat
  Search existing conversations
  Active / Archived
  Pinned and date-grouped history
  Open conversation
    Title/model selector -> Model picker
    Add attachment -> Photo / File / Paste
    More -> Conversation settings
            Share as text
            Share as Markdown
            New chat
            Keyboard shortcuts

Providers                         Settings
  Provider detail                   Appearance
    Key status and usage            Chat defaults
    Models -> Manage models         Providers (same canonical destination)
    Connection details              Data & storage
    Change/remove key                 Usage, export/import, keys, clear data
  Add preset/custom provider        Encrypted backup
                                    About and privacy
```

On the Chats root, an overflow menu exposes Providers, App settings, and
Keyboard shortcuts. Inside a chat, the leading history button opens the
drawer; its footer exposes Providers and Settings. These are shortcuts to the
same destinations, not separate settings implementations.

Use Back/Up on hierarchical settings/provider screens. Do not show a menu icon
where the expected action is returning to the parent screen.

| Task | Proposed entry point | Interaction budget |
| --- | --- | --- |
| Open history from a conversation | Leading history/navigation button | One tap. |
| Start a chat from history | Single labeled New chat action | One tap when configured. |
| Open model selection | Title/model control with a chevron | One tap to open; one selection to apply. |
| Stop a response | Composer's stable send/stop position | One tap, never inside a menu. |
| Attach content | Composer Add button, then a labeled source | Two app taps before the system picker. |
| Open conversation settings | More, then Conversation settings | Two taps. |
| Share text or Markdown | Separate labeled entries in More | Two taps to the existing platform share flow. |
| Rename/pin/archive/delete a chat | Its visible row overflow | Two taps to the action; existing confirmation still applies. |

Keep existing route names or aliases for navigation restoration. Route both
backup entry paths to the same real backup feature. Preserve onboarding,
pending external-intent, and last-active-conversation precedence.

## 5. Screen-by-screen design

### 5.1 Chat: reading first

Illustrative compact layout; bracketed controls are not literal text glyphs
to use as production icons:

```text
+--------------------------------------+
| [History] Conversation title [More]   |
|           Model / provider [v]        |
+--------------------------------------+
|                                      |
|          A short user message        |
|                                      |
| Assistant                            |
| A clear answer using the available   |
| reading width. Lists and code retain |
| their existing capabilities.         |
|                                      |
| [Copy]  [More]       usage if enabled |
|                                      |
|                    [Latest messages] |
| [pending attachments, when present]  |
| [one relevant warning, when present] |
| [+] Message...                [Send] |
+--------------------------------------+
```

**Header:** use a stable 64-72 dp minimum when a model subtitle is present,
excluding system insets, and approximately 56 dp on screens without a subtitle.
Allow growth at large font scales. The title/model control must have a
48 dp-minimum hit target and a clear "Change model" accessible label.
Keep the model reachable when scrolled or when the keyboard is open.

**Transcript:** render assistant text on the canvas or a very subtle surface,
using nearly the full content width. Keep user messages right-aligned on a
soft tint, generally up to 88% of available width. Remove avatar gutters.
Group spacing and a restrained role label distinguish speakers; system and
tool messages retain their own labels.

**Actions and metadata:** combine secondary information into one wrapping
footer instead of separate full-width action, timestamp, and metadata rows.
Keep a visible Message actions button; long-press is an additional shortcut,
never the only way to find an action. Show Copy directly for the latest
assistant response; keep Regenerate and Edit last message in the relevant
menu with their current eligibility and confirmation rules.

Historical model/provider attribution must remain available from the message,
not inferred from the current header. If Show token usage is enabled, retain
a readable summary beneath responses; do not turn an explicit preference
into hidden-only information. Additional details can expand on demand.

**Reasoning and code:** keep reasoning collapsed initially, labeled clearly,
and readable on expansion. Code retains language, copy, wrap/scroll, syntax
highlighting, and selection. Keep the current safe Markdown, link handling,
tables, and raw-source math fallback; this redesign does not promise a new
math renderer.

**Scrolling:** preserve conditional following only near the latest response.
Do not pull users away from older content. Merge the separate new-message
pill and down button into one clear Latest messages control when both would
otherwise be present. Keep Load older messages and scroll anchoring.

### 5.2 Composer and message states

Retain the existing Add / text input / Send-Stop structure, but use one calm
surface without a gradient separator plus shadow plus strong border.

- Keep all text-entry modes, draft persistence, photo/file picking, clipboard
  images, drag-and-drop, attachment removal, and unsupported-model warnings.
- Pending attachments appear in one horizontal strip above the input, with
  readable names/sizes and independently usable removal targets.
- Show attachment capability/error information once in this area. Keep the
  explicit Send anyway/Cancel confirmation when required.
- Preserve growth up to eight lines where space permits. On short windows
  with the keyboard open, constrain the visible input height and scroll its
  contents rather than covering the entire transcript. This changes viewport
  allocation, not draft capacity or supported input.
- Keep typed text and readable history available offline as current behavior
  allows; disable sending with a short explanation. Do not imply that messages
  are automatically queued or will send themselves later.
- Keep Stop visible during generation. Never delay stopping until a button
  animation completes.
- Use one restrained generating indicator. Avoid simultaneous bouncing dots,
  bubble shimmer, and a blinking caret.
- Show a pending image/loading state separately from a failed image state.
  Do not display "Image unavailable" while a request is merely loading.

For an empty chat, use a short greeting and the existing suggested prompts,
which fill the draft rather than send immediately. The header already owns
model selection; avoid another large model chooser in the center. Reduce or
omit decorative content while the keyboard is visible.

Missing-provider/key/model states keep one actionable setup banner. Request
failures stay next to the affected message with Retry and optional Details.
Do not show the same failure in a global banner, message card, and snackbar.

### 5.3 Chats root and history drawer

```text
+--------------------------------------+
| Chats                 [New chat] [..] |
| Search conversations                 |
| [Chats] [Archived]                    |
|                                      |
| Pinned                               |
| Conversation title         time [..] |
| Model label                          |
|                                      |
| Today                                |
| Conversation title         time [..] |
| Model label                          |
+--------------------------------------+
```

Use the existing conversation service and list presentation for both full-screen
history and the sidebar/drawer, parameterized for the host. Do not mount two
independently collecting history lists unnecessarily.

The phone root has one New chat action. The drawer/sidebar has one of its own
when it is the active history surface; suppress the embedded list's duplicate
header action. Remove the redundant Conversations navigation row above the
history itself and the Library group.

Keep search easy to locate, the active/archive switch, pinned and all existing
date buckets, match highlighting/snippets, active selection, rename, delete,
undo, pin, archive, and unarchive.

Rows need only a title, current available preview/model label, time, and
overflow. Remove the chevron when the row is already clearly tappable.
Do not introduce a database change just to manufacture last-message previews.
Keep swipe-to-pin/archive as shortcuts with equivalent labeled menu actions.

Give the drawer the available safe viewport instead of trapping it below a
large global title and between several fixed navigation groups. Its two-row
Providers/Settings footer must not make history unusable on short screens.
When the IME is open, let the overall layout scroll/reflow rather than hiding
search results behind fixed controls.

### 5.4 Model picker and conversation controls

**Model picker:** search at the top, then All/Free/Pinned filters and a
virtualized list. Use compact rows rather than tall cards with several
separate chip rows. Keep model label, provider, context, meaningful capability
information, and price available before selection. Wrap them when needed,
rather than truncating pricing or shrinking the font.

Show selected/pinned/free states clearly. The full capability list, canonical
slug, output limits, supported parameters, upstream context, and moderation
information belong in Details. Pin/Hide/Unhide remain in a row menu.

Move the always-expanded manual-add form to a labeled Add model by ID action.
Keep Refresh accessible as a button/menu action as well as pull-to-refresh.
Hidden models must remain recoverable in Manage models. State whether the
picker is changing this conversation or defaults for new chats.

Do not equate Free with unlimited requests or zero connectivity cost. Preserve
availability warnings, cached catalog behavior, and existing selection rules;
never silently substitute a cheaper, faster, or different model.

**Conversation settings:** begin with Model and System prompt. Put sampling
parameters in a Generation disclosure with a summary of active overrides:
temperature, Top P, max output, stop sequences, reasoning effort, and streaming.
Use exact numeric input alongside any slider; preserve the distinction between
a model default and an explicit numeric value.

Keep provider extras JSON in a clearly labeled Advanced section, including
validation and restrictions on overriding core fields. OpenRouter routing
controls belong here with fallbacks, sort, data-collection preference, warnings,
and routing summary. Integrate the existing curated controls rather than
discarding access to the full JSON object.

Use the full model picker instead of another unsearchable dropdown. Preserve
immediate per-conversation persistence, system prompt/parameter retention,
unsupported-parameter notes, and historical model snapshots.

Put working Share actions in the chat overflow. Keep Delete conversation at
the bottom of conversation settings with confirmation. A disabled future
export control must not resemble an available primary action; activating
per-conversation file export is separately scoped functional work.

### 5.5 Providers and provider detail

**List:** one title and one labeled Add provider action. Use simple rows with
provider name and a truthful textual status: configured/needs key/off, as
appropriate. A stored key is not proof of a successful live connection.
No-key local providers must not be presented as broken merely because
`secretId` is null. Do not rely on a colored status dot alone.

**Add provider:** preset first, then the required credential/connection fields,
then one primary action. Optional labels and custom headers remain available
under clear disclosures. For Custom, keep base URL and authentication choice
visible; preserve Bearer, x-api-key, None, and add/edit/remove custom headers.
Use wrapping choices or a selector rather than overflowing chip rows.

Avoid asking the user to select the same preset twice. Preserve enabled,
disabled, and unsupported preset distinctions from the current service.
Never conceal cleartext warnings or blocked-connection explanations inside
Advanced, and do not weaken connection validation.

**Detail:** show name, enabled state, key/configuration status, usage where
reported, and model count first. Offer Models/Manage models and Refresh as
secondary actions, not several full-width blue buttons.

Move auth type, full endpoint/preset, rate limits, CORS metadata, and other
diagnostics into Connection details. Retain key label, limit, free-tier state,
OpenRouter policy notice, and documentation access. Do not invent usage or
credit values when the provider does not report them.

Change key opens a focused secure editor. Remove key remains a clearly labeled
destructive action with its existing confirmation and explanation that
conversations/provider records are retained. Preserve protection of sensitive
screens and never reveal an existing key as part of a design preview.

### 5.6 Onboarding

Keep the current welcome, connection, and optional backup capabilities, but
make the shortest successful path obvious.

- Welcome: small brand mark, one short BYOK/local-storage explanation, provider
  choice with OpenRouter preselected, and Get started.
- Keep theme choice available in a compact optional row rather than making it
  dominate the welcome screen.
- Connection: API key, show/hide, Paste, help, optional label, and Connect.
  One progress treatment is sufficient; avoid showing both a testing button
  spinner and a separate duplicate testing row.
- Preserve key-validation rules; never test a key by generating a paid answer.
  Retain entered values after recoverable errors.
- Keep public model discovery, catalog errors/retry, and model browsing, but
  place the catalog behind a View available models disclosure instead of
  several large skeleton rows competing with credential entry.
- After successful connection, present Backup as explicitly optional with
  Start chatting/Skip for now immediately visible. Show the real existing
  backup form only when the user chooses setup.
- Skipping optional backup must lead to a usable chat/history destination,
  not a closed-drawer placeholder. Keep pending shared text/deep links intact.

All action areas must remain reachable with the IME visible. Keep setup
scrollable; avoid a long backup form nested inside another same-axis scrolling
form. Do not add a marketing carousel, login, required cloud account, or new
setup prerequisite.

### 5.7 Settings, data, and backup

**Settings root:** retain the existing grouped-list foundation. Keep Appearance,
Chat defaults, Providers, Data & storage, Encrypted backup, and About/privacy
as concise rows. Remove duplicate drawer-level Data/Backup shortcuts, not the
underlying screens.

**Appearance:** retain light/dark/system, comfortable/compact, chat font size,
haptics, and dynamic color. Use one compact live preview rather than large
repeated examples. Reduced motion continues to follow the OS. Wire the
existing stored preferences through the application theme and make code
highlighting follow an explicit app theme override.

**Chat defaults:** show default provider/model and system prompt first, with
Generation, Composer, and Privacy groups/disclosures. Keep all existing
controls, including send-key mode, token-usage display, remote images, and
reasoning effort. Explain that defaults affect new conversations.

Keep immediate persistence. Replace repeated "saved" snackbar queues with
quiet, coalesced saved feedback; errors remain persistent and actionable.
Do not add a Save button that falsely implies changes are staged.

**Data & storage:** order the screen as storage total, optional breakdown,
Export, Import, stored API keys, backup link/status, then destructive actions
at the bottom. Clear all data should not precede ordinary export/import work.

Preserve plaintext-export warnings, secret exclusion, document pickers,
import merge/replace semantics, validation, individual-key removal, and the
exact existing destructive confirmations. If an operation is pending, show
its pending state; do not announce success merely because a picker opened.
Keep an explicit distinction between cancelling a picker and failing an
operation.

Correct obsolete backup copy and contradictory privacy wording. The current
`AndroidSettings.loadRemoteImages` default is true although some text says
images are blocked by default. Display truthful explanations for the actual
setting, and resolve the default/spec discrepancy as an explicit product
decision outside a purely visual change. Never reset existing preferences
silently.

**Encrypted backup:** show a status-first overview when configured:

```text
+--------------------------------------+
| [Back] Encrypted backup              |
| Last backup: [actual status]         |
| Pending changes: [actual count]      |
|                                      |
| [Back up now]                        |
| Restore from backup                  |
| Configuration                  [>]   |
| Storage usage and error details      |
|                                      |
| Advanced recovery / remote deletion  |
+--------------------------------------+
```

When unconfigured, show Set up encrypted backup and a short explanation.
Move editable storage credentials into Configuration, with only the chosen
backend's fields visible. Retain S3-compatible, Azure Blob, Google Cloud
Storage, Google Drive, and WebDAV; all existing endpoint, region, container/
bucket, prefix, credential, and folder options remain accessible.

Keep Test connection, enable/disable, passphrase entry, loss warning,
client-side encryption explanation, last backup, pending count, storage usage,
and last error. Configuration and passphrase warnings must be shown before
enable/restore commits, not hidden under a decorative disclosure.

Restore continues to merge by default. Replace-local and Delete remote backup
remain separate, clearly named destructive operations with the existing exact
typed phrases and consequences. Do not put Delete remote backup beside
Back up now as an equally prominent action.

Preserve current save/enable semantics. Do not imply that uncommitted form edits
are the configuration used by a running backup. Keep existing WorkManager
constraints, cadence, and backoff; the redesign does not make backup
Wi-Fi-only, foreground-only, or always-on.

## 6. Functionality-preservation ledger

Use this as a release checklist. Moving an operation is acceptable; deleting
it, obscuring it behind an undocumented gesture, or changing its semantics
to make the screen look simpler is not.

| Capability family | Must remain available after redesign |
| --- | --- |
| Conversation history | Create/open, last-active restoration, all date groups and pins, active/archive views, search and match snippets, rename, delete confirmation/undo, pin/unpin, archive/unarchive. |
| Messaging | Streaming/non-streaming behavior, immediate send feedback, Stop, retry and diagnostic details, regenerate last assistant, edit/resend last user with truncation warning, cancelled/interrupted partial content, older-history loading, conditional auto-scroll, per-chat text drafts. |
| Message content | Safe Markdown/GFM, links, tables, code highlighting/copy/wrap/selection, reasoning disclosure, current raw-source math fallback, role distinction, historical provider/model attribution, usage preference, local attachments and remote-image controls. |
| Input and attachments | Enter/alternate send mode, Shift+Enter, visible send button, multi-line editing, photo/files, image paste, drag-and-drop, names/sizes/removal, capability warnings, explicit send-anyway consent, existing limits. |
| Providers | Presets/custom, optional labels, multiple configured profiles where supported, base URL/auth/custom headers, cleartext policy, enabled state, key validation/rotation/removal, key metadata/usage/limits, catalog refresh, documentation. |
| Model and generation state | Global defaults versus per-chat overrides, search/provider grouping, Free/Pinned filters, context/pricing/modalities/details, pin/hide/unhide/manual ID, system prompt, all sampling/reasoning/streaming settings, extra JSON, OpenRouter routing/privacy semantics. |
| Settings and privacy | Light/dark/system, both densities, all font sizes, dynamic-color fallback, haptic preference, OS reduced motion, send-key choice, token usage, remote images, accurate local-storage/privacy explanation. |
| Data and backup | Usage breakdown, secret-free plaintext export and warning, merge/replace import, individual-key deletion, clear-all confirmation, all five backup backends, credentials/passphrase, test/enable/status/manual backup, merge/replace restore, remote deletion safeguards. |
| Platform integration | Text/Markdown sharing, incoming shared-text drafts, chat deep links and not-found handling, hardware shortcuts/help, keyboard/focus access, predictive back, rotation/resizing, safe system/IME insets, persistent data and startup recovery. |

For the full model-management and curated routing UI, the current code contains
reusable components but lacks production entry-point wiring. Treat exposing
those components as named integration tasks in UX-02/UX-04, not as evidence
that the current application already exposes every row in the ledger.
Unimplemented stretch features and disabled placeholders are not prerequisites
for this redesign.

## 7. Entry-level phone and performance plan

"Basic phones" here means supported entry-level Android hardware, not a new
feature-phone platform. Preserve the current minimum SDK 26, configured in
`android\build-logic\convention\src\main\kotlin\dev\chatui\buildlogic\AndroidApplicationConventionPlugin.kt`.
Do not raise it for visual effects.

The full app should be lightweight by default. Do not create a reduced-feature
Lite version or require users to diagnose their phone's RAM.

### Implementation requirements

| Area | Required approach |
| --- | --- |
| Drawing | No blur/glass, animated backgrounds, remote fonts, large illustrations, or stacked shadows. Prefer opaque/tonal surfaces. Use existing icons and libraries. |
| Motion | Native sheet/drawer motion and short purposeful feedback, normally around 100-180 ms. Static skeletons are acceptable. Respect OS reduced motion everywhere, including list placement and programmatic scrolling. |
| Streaming layout | Preserve the isolated active-stream row. Avoid spring-animating bubble height for every token and restarting animated scrolling for every text/reasoning update. Follow the end only when the user was already following. |
| Update cadence | Preserve network events and persistence semantics. Coalesce only UI work if profiling justifies it; an initial 32-50 ms display window is a candidate, not an arbitrary network throttle. Flush final/error/cancelled content immediately. |
| Markdown/code | Preserve supported rendering. Reuse unchanged parsed/highlighted results and investigate synchronous highlighting for long live code blocks; move expensive work off the UI thread where necessary. Never discard text to improve a benchmark. |
| Images | Keep existing display-sized local image requests. Replace unbounded remote Markdown decoding with target-sized loading/caching, preserving URL restrictions and the user's load consent/preference. Keep filenames/alt text and failure recovery. |
| Attachment ingestion | Read URI content on an appropriate background dispatcher using existing size/validation limits. Show an adding/processing state, avoid accumulating several unnecessary byte copies, and preserve the draft if a read fails. |
| Lists | Keep lazy/virtualized lists, stable keys, and appropriate content types. Reuse history/model list infrastructure; do not eagerly compose an expanded public catalog or all messages. |
| Data access | Do not add full message/attachment scans to draw the sidebar or estimate storage. Keep disk/network work out of composition and avoid duplicate observers introduced by history rehosting. |
| Network/battery | Read cached history/catalogs immediately. Do not refresh catalogs or usage on recomposition. Preserve retry/backoff and background constraints; use no polling loop for decorative status. |
| State | Keep draft and stream recovery. Save lightweight non-secret UI state such as search, selected tab, disclosure, and scroll position where appropriate; do not place credentials/passphrases in generic saved-state bundles. |

### Proposed measurable budgets

Confirm these against named reference devices in UX-00; they are targets, not
claims about the existing app.

| Measure | Acceptance target and method |
| --- | --- |
| Local interaction feedback | Visible response to ordinary taps/typing within 100 ms under representative local workloads; external request duration reported separately. |
| Cold start | Retain the product target below 1.5 s on a mid-tier device. Proposed entry-level target: p95 below 2.5 s to the first usable local screen over at least 30 controlled release-like cold starts. |
| Scrolling/streaming | Aim for the 16.7 ms frame budget at 60 Hz. On the named entry-level device, target p95 frame time at or below 32 ms, with no frozen frames above 700 ms or ANRs in the scripted flows. |
| Memory | No OOM with a 10,000-message fixture plus representative supported images. Compare steady-state and peak PSS against the pre-redesign baseline using matching build configuration and data; investigate growth above 10% or retained growth after repeated navigation. |
| Functional parity | Identical supported actions on low-RAM and higher-end devices. Resource adaptation may reduce decoration, never available features or message content. |
| Network independence | History, settings, and draft editing remain usable under the existing offline rules. A slow provider must not freeze navigation or local UI. |

Measure with deterministic fake streams/catalogs and local data, keeping network
latency separate from rendering. Emulator screenshots are not evidence of
physical-device memory, startup, or battery performance.

Update the existing baseline-profile journey rather than assuming it covers the
new flows. `StartupBaselineProfile.kt` still searches for a literal menu glyph
even though production uses an icon, and its optional lookups can skip an
interaction silently. Use stable accessible selectors and deterministic seeded
setup for history, opening chat, and composer interaction.

## 8. Accessibility, states, and interaction safeguards

- Test 320 dp width and 200% font scale. Controls must wrap/reflow rather than
  shrink labels or clip fields. Use a selector instead of a squeezed
  four-choice chip row when necessary.
- Keep 48 dp targets for toolbar icons, attachment removal, menus, filters,
  and steppers in both density modes. A small visual icon is not a small target.
- Maintain 4.5:1 normal-text contrast and 3:1 large-text/essential-control
  contrast in both themes and dynamic colors.
- Every icon-only action needs a meaningful name; decorative icons should not
  add duplicate TalkBack stops. Selected/expanded/disabled state must be exposed.
- Preserve visible keyboard focus and a sensible traversal order. After a
  picker/dialog closes, restore focus to the initiating control without
  unexpectedly opening the IME.
- Long-press, swipe, and pull-to-refresh always have visible labeled
  alternatives. Never require hover to discover functionality.
- Announce response start, completion, cancellation/error as appropriate, not
  every token. Avoid duplicate announcements from repeated banners.
- Preserve `BackPolicy`: topmost dialog, sheet, drawer, keyboard, then
  navigation. Dismissing one layer must not also leave the screen.
- Consume system/IME insets once. Keep the composer, sheet action areas, and
  snackbar actions clear of gesture and three-button navigation areas.
- Plain-language errors explain the next action. Technical JSON/diagnostics
  stay under Details; validation stays next to its input.
- Async actions distinguish idle, pending, success, failure, and cancellation.
  Preserve cached content during refresh instead of replacing it unnecessarily.
- Destructive actions use explicit labels and existing confirmation semantics.
  Do not preselect or autofocus a destructive confirmation action.

## 9. Phased implementation

Deliver small, reviewable PRs. Dependencies are explicit; phases sharing only
UX-01 can be developed independently.

| Phase | Depends on | Work and primary files | Completion gate |
| --- | --- | --- | --- |
| UX-00: Baseline and inventory | None | Record current reachable actions, compact/large-font captures, and controlled performance baselines. Start with existing screenshot/UI fixtures. | The preservation ledger is assigned to flows; reference devices and measurement conditions are recorded. |
| UX-01: Calm foundations | UX-00 | Add a compact title-bar variant to the existing scaffold; consistent tokens, contrast, density, state feedback, applied theme/haptics/dynamic color. Design system `ChatUiScaffold.kt`, `Color.kt`, `Dimens.kt`, `Typography.kt`, `Theme.kt`, `Buttons.kt`, `GroupedList.kt`, `StateKit.kt`; Shell `ChatUiApp.kt`. | Readable 320 dp/large-font foundations, applied preferences, and no new visual dependency. |
| UX-02: Navigation and entry points | UX-01 | Real compact Chats root, one history CTA, reduced drawer footer, toolbar overflow/Back, distinct model-picker callback and app-owned picker hosting with explicit conversation/global-default targets. Shell `AdaptiveShell.kt`, `ChatUiApp.kt`, `ShellNavHost.kt`; Feature conversations `ConversationListScreen.kt`, `ConversationRoute.kt`; Feature chat `ChatTopBarState.kt`, `ChatRoute.kt`, `ChatScreen.kt`. | No closed-drawer dead end; direct model selection works; history, share, shortcuts, deep links, restore, and back flows remain intact. |
| UX-03: Chat readability and resources | UX-01 | Wider answer layout, compact footers, consolidated warnings/latest control, responsive composer, lighter streaming motion, bounded remote images and asynchronous ingestion. Feature chat `ChatMessageItem.kt`, `ChatMessageActions.kt`, `ChatMessageList.kt`, `ChatComposer.kt`, `ChatScreen.kt`, `ChatMarkdown.kt`, `ChatCodeBlock.kt`. | All message/input operations preserved; keyboard-visible layouts work; representative entry-level rendering meets agreed budgets. |
| UX-04: Setup, providers, and models | UX-01, UX-02 | Focused onboarding/provider forms; compact picker rows and manual-add disclosure; provider Models entry wired to existing management; grouped conversation generation/extras/routing. Feature onboarding `OnboardingScreen.kt`; Feature providers `ProviderListScreen.kt`, `ProviderDetailScreen.kt`, `ProviderRoutes.kt`, `ModelPickerSheet.kt`, `ManageModelsScreen.kt`, `ConversationSettingsRoute.kt`, `ConversationSettingsSheet.kt`, and existing picker/routing ViewModels. | No advanced capability lost; hidden models recoverable; correct picker target; key/setup failures and optional backup remain understandable. |
| UX-05: Settings, data, and backup | UX-01, UX-02 | Global-default picker entry, quiet autosave feedback, concise settings, reordered data actions, accurate copy, status-first backup with separate configuration. Feature settings `SettingsRoute.kt`, `SettingsScreens.kt`, `DataSettingsScreen.kt`; Feature backup `BackupSettingsScreen.kt`; Shell `ShellNavHost.kt` and `DataSettingsDestination.kt` for picker/import/export wiring. | All preferences, export/import safeguards, key deletion, five backup backends, restore modes, and remote deletion remain usable. |
| UX-06: Integrated acceptance | UX-02, UX-03, UX-04, UX-05 | Production-shell captures, accessibility/device checks, focused regression coverage, baseline-profile selector/journey update. Existing app/feature tests and `android\baselineprofile`. | Every checklist item below has reviewed evidence; no blanket golden re-recording to hide unintended changes. |

Keep cross-feature presentation orchestration in `app`; do not make the chat
feature depend on provider UI just to open a picker. Reuse existing services,
state holders, and shared design primitives. This work does not justify a new
UI module, alternative navigation framework, or dependency upgrade.

Changes to intentional UX rules should be explicit in each implementation PR:
assistant full-width layout, compact toolbar, relocated secondary actions, and
short-screen composer viewport are presentation changes. Existing behavior and
persisted values otherwise remain stable.

## 10. Verification and release checklist

### Device and state coverage

| Dimension | Required coverage |
| --- | --- |
| Small windows | 320 x 568 dp, 360 x 640 dp, existing 411 x 891 dp fixtures, and short landscape around 640 x 360 dp. |
| Expanded | Existing 840 dp breakpoint, resizing across it, and a wider tablet window. |
| Resource tier | At least one real entry-level device, ideally around 2 GB RAM/60 Hz, plus a representative mid-tier device. Name actual hardware in results. |
| Platform | API 26 compatibility, an API 31+ dynamic-color device, and a current target-platform device; gesture and three-button navigation. |
| Accessibility | 100%, 150%, and 200% fonts, large display scaling, TalkBack, hardware keyboard, both densities, OS animation disabled. |
| Themes | Light/dark/system and app override differing from the system, including code blocks; dynamic color on/off and fallback below API 31. |
| Content | Empty/populated/long histories, long model/provider names, code/tables/reasoning, supported large attachments, many model rows, and long form errors. |
| Connectivity/lifecycle | Offline, slow/failing provider, missing key/model, stream stop/error/interruption, rotation, process recreation, incoming share, valid/stale chat link, backup failure. |

Use representative/pairwise combinations rather than every possible Cartesian
combination. Small-width chat, model picker, and credential forms must
specifically be exercised with large text and the keyboard visible.

### Existing coverage to extend

- Shell: `AdaptiveShellTest`, `ShellNavigationTest`, `ShellSidebarTest`,
  `ShellTopBarTest`, `ShellBackNavigationTest`, `ShellInsetsTest`,
  `AccessibilityFocusTest`, `MotionHapticsThemeTest`, and deep-link tests.
- Chat: `ChatScreenTest`, `ChatComposerTest`, `ChatMessageListTest`,
  `ChatMessageItemTest`, `ChatMessageActionsTest`, `ChatMarkdownTest`,
  `ChatCodeBlockTest`, `ChatRouteFlowTest`, and draft/stream-recovery coverage.
- History/setup/providers: existing conversation-list/search/back tests,
  `OnboardingScreenTest`, provider route/detail/list tests,
  `ModelPickerContentTest`, routing tests, and `SheetBackTest`.
- Data/backup: `SettingsScreensTest`, `DataSettingsScreenTest`,
  `BackupSettingsScreenTest`, their ViewModels/services, and existing
  scheduler/worker tests when a touched integration warrants them.

Add focused regressions for the current route-to-placeholder issue, direct
model-picker callback, missing preference propagation, and duplicate attachment
warning. Add production-shell screenshots, not only standalone-screen goldens.
Preserve useful test tags and accessible names while reparenting controls.

Use fake keys, conversations, and provider responses. Never capture real
credentials or private conversation content in screenshots/profiles.

Example future validation commands, from PowerShell at the repository root:

```powershell
Set-Location .\android

# Select only the modules affected by the implementation PR.
.\gradlew.bat :core:designsystem:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :feature:chat:testDebugUnitTest :feature:conversations:testDebugUnitTest
.\gradlew.bat :feature:providers:testDebugUnitTest :feature:onboarding:testDebugUnitTest
.\gradlew.bat :feature:settings:testDebugUnitTest :feature:backup:testDebugUnitTest

# Integrated release-candidate checks after the targeted checks.
.\gradlew.bat testDebugUnitTest lintDebug :app:assembleDebug :app:verifyRoborazziDebug

# Connected device/emulator required; profile generation is not a timing benchmark.
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

Check `contracts\parity.md` under the repository's existing parity process for
user-visible behavior changes. Separate intended Android presentation
differences from actual functional differences.

### Usability walkthrough

Before rollout, have a small group of nontechnical users try the compact design
on entry-level phones without being taught hidden gestures. Ask them to start
and find a chat, change its model, add/remove an attachment, stop a response,
find an advanced setting, and skip optional backup setup. Record completion,
wrong turns, unreadable labels, and accidental actions. Any core task requiring
an explanation of an unlabeled icon or undiscoverable gesture needs revision;
attractive screenshots alone do not establish better usability.

### Definition of done

- [ ] Chats is useful on a phone without first opening a drawer.
- [ ] One New chat action per visible history surface; one owner for each title.
- [ ] Model selection opens the correct current-conversation/default picker.
- [ ] Chat is content-first, with no avatar gutter or competing permanent toolbar actions.
- [ ] Send/Stop, attachment removal, history access, and all menus remain reachable at small sizes.
- [ ] Every preservation-ledger capability is retained or its pre-existing integration gap is explicitly tracked.
- [ ] Advanced controls are discoverable, and active overrides are summarized rather than silently hidden.
- [ ] No duplicated attachment/error presentation or misleading loading/success/privacy copy.
- [ ] Settings apply correctly, including haptics, dynamic color, and explicit app-theme code colors.
- [ ] Destructive, import/export, cleartext, and backup consent/confirmation behavior is preserved.
- [ ] Both themes, large text, TalkBack, focus restoration, IME insets, and back layering have reviewed evidence.
- [ ] Agreed physical-device startup/frame/memory budgets are met without removing features.
- [ ] Integrated screenshots and focused regression checks are reviewed before rollout.

The intended outcome is **less interface to interpret, more room for content,
and the same powerful application underneath**.
