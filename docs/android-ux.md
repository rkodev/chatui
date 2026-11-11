# Android UI/UX Polish — Production Design Review

- Status: Proposed
- Scope: `android/` (native Kotlin + Jetpack Compose client)
- Goal: Turn the functional M1–M3 Android client into a polished, Apple-like,
  production-quality experience — "visual candy" with a smooth, low-friction,
  far less clanky feel.
- Non-goal: New product features, provider protocols, backup/sync, localization.
  This is a visual, interaction, and perceived-performance pass over existing
  behavior.

---

## 1. Executive summary

The Android client is functionally complete for M1–M3 but visually reads as a
developer scaffold. The core problems are structural, not cosmetic:

1. **No iconography.** Navigation, overflow, and keyboard affordances are literal
   text glyphs (`☰`, `⌨`, `⋯`, `…`) in `ChatUiApp.kt:308`, `ChatUiApp.kt:322`,
   and `ConversationListScreen.kt:488`. There is no `material-icons` dependency
   in `android/gradle/libs.versions.toml`.
2. **No motion.** A repo-wide search finds zero `animate*`, `AnimatedVisibility`,
   `Crossfade`, or `animateContentSize` usage in `android/feature` or
   `android/app`. Every state change is an instant hard cut.
3. **No elevation, shape, or depth language.** Cards use flat `surfaceVariant`
   fills; the design system has radii but almost no component uses elevation,
   tonal layering, or shadows.
4. **Screens are long undifferentiated forms.** Providers, settings, and
   conversation settings are single `LazyColumn`/`Column` dumps of
   `OutlinedTextField`s and `TextButton`s with no grouping, hierarchy, or
   progressive disclosure.
5. **The chat surface is a plain list of boxes.** Message bubbles are full-width
   rectangles with a role label and timestamp above each one; there is no
   avatar, no grouping, no streaming shimmer, no typing indicator, no scroll
   affordance, and no composer polish.
6. **Feedback is text-only.** Loading is "Loading…" text, errors are red text,
   success is a label change. There are no skeletons, no snackbars, no haptics,
   no toasts, no inline validation states.
7. **The shell is a stock `Scaffold` + `TopAppBar`.** No large-title collapse,
   no blur/translucency, no animated drawer, no shared-element or predictive
   transitions, no bottom safe-area treatment for the composer.
8. **The design tokens are web-derived and underused.** `Color.kt`,
   `Dimens.kt`, `Typography.kt`, and `Motion.kt` exist but most screens bypass
   them with hardcoded `24.dp`, `8.dp`, `MaterialTheme.typography.*`, and
   `MaterialTheme.colorScheme.*`.

The recommended direction is an **Apple-like, content-first, calm interface**:
generous whitespace, strong typographic hierarchy, soft depth, restrained
color, spring-based motion, tactile feedback, and a small set of reusable
polished primitives. The work is best delivered as a design-system upgrade
followed by screen-by-screen adoption, not as scattered one-off tweaks.

---

## 2. Design north star

Adopt these principles as the review rubric for every screen:

| Principle | What it means on Android |
| --- | --- |
| **Clarity** | One primary action per screen. Hierarchy is obvious at a glance. No competing buttons. |
| **Deference** | Chrome recedes; content leads. Translucent bars, subtle dividers, no heavy borders. |
| **Depth** | Soft elevation and tonal layering communicate structure, not decoration. |
| **Directness** | Gestures and controls respond instantly; motion explains cause and effect. |
| **Craft** | Optical alignment, consistent 4/8 dp rhythm, real icons, tuned type. |
| **Calm** | Restrained palette, no alarm-red unless destructive, no layout jumps. |
| **Tactility** | Haptics on commit actions, springy press states, no dead taps. |
| **Continuity** | State survives rotation/process death; transitions preserve context. |

Reference feel: iOS Messages/Notes + Apple Settings grouped lists + ChatGPT/Claude
mobile chat surfaces, expressed with Material 3 mechanics (predictive back,
edge-to-edge, dynamic color) so it still feels native to Android.

---

## 3. Current-state audit

### 3.1 What already exists (keep and build on)

- Semantic token layer: `core/designsystem/Color.kt`, `Dimens.kt`,
  `Typography.kt`, `Motion.kt`, `Theme.kt`.
- Reduced-motion plumbing: `rememberReducedMotion()` in `Motion.kt`.
- `SecureScreen` for key-entry/detail screens.
- Adaptive shell with permanent sidebar ≥ 840 dp (`AdaptiveShell.kt`).
- Predictive-back fallbacks, IME insets, edge-to-edge (`MainActivity.kt`).
- Stable `LazyColumn` keys/content types in chat and conversations.
- Coil image sizing and cache keys (`ChatMessageItem.kt`).
- Roborazzi screenshot harness and Compose UI tests.

### 3.2 Gap inventory (severity-ranked)

| # | Gap | Where | Severity |
| --- | --- | --- | --- |
| G1 | Text glyphs used as icons | `ChatUiApp.kt:308,322`, `ConversationListScreen.kt:488` | High |
| G2 | Zero animation/transition anywhere | all features | High |
| G3 | Chat bubbles are full-width flat boxes, no avatars/grouping | `ChatMessageItem.kt` | High |
| G4 | Composer is a bare `OutlinedTextField` + text buttons | `ChatComposer.kt` | High |
| G5 | No streaming/typing affordance beyond a `…` glyph | `ChatMessageItem.kt` | High |
| G6 | Settings/providers are undifferentiated form dumps | `SettingsScreens.kt`, `ProviderListScreen.kt`, `ProviderDetailScreen.kt` | High |
| G7 | No skeletons; loading is text | all screens | High |
| G8 | No snackbar/undo surface; delete undo is an inline row | `ConversationListScreen.kt` | Medium |
| G9 | No haptics | all | Medium |
| G10 | Top bar is stock; no large-title collapse or translucency | `ChatUiApp.kt` | Medium |
| G11 | Conversation rows are cards with a `⋯` menu; no swipe actions | `ConversationListScreen.kt` | Medium |
| G12 | Onboarding is a plain form; no hero, progress, or delight | `OnboardingScreen.kt` | Medium |
| G13 | Model picker is a dense card list with inline details | `ModelPickerSheet.kt` | Medium |
| G14 | No empty-state illustration or guidance | `ChatScreen.kt`, `ConversationListScreen.kt` | Medium |
| G15 | Hardcoded spacing/typography bypasses tokens | many screens | Medium |
| G16 | No dynamic color / Material You support | `Theme.kt` | Low |
| G17 | Splash icon is a generic chat glyph; no branded mark | `res/drawable/ic_splash.xml` | Low |
| G18 | No scroll-to-bottom FAB / new-message pill | `ChatMessageList.kt` | Medium |
| G19 | Error presentation is inline red text, no retry affordance pattern | `ChatScreen.kt`, providers | Medium |
| G20 | No press/ripple tuning; default Material ripples everywhere | all | Low |

---

## 4. Foundation: upgrade the design system first

All screen work depends on this layer. Do it before touching screens.

### 4.1 Color

- Keep the semantic `ChatUiColors` contract but **retune the palette** for a
  softer, Apple-like feel:
  - Light canvas `#FFFFFF`, grouped background `#F2F2F7` (iOS grouped), raised
    surface `#FFFFFF`, separator `#E5E5EA`.
  - Dark canvas `#000000` or `#0B0B0F`, grouped `#1C1C1E`, raised `#2C2C2E`,
    separator `#38383A`.
  - Accent: a single confident blue (`#0A84FF` dark / `#007AFF` light) with a
    tinted `accentContainer` for selected rows and chips.
  - User bubble: accent-tinted, not a separate blue block; assistant bubble:
    grouped surface with no border.
- Add missing semantic roles: `bgGrouped`, `bgInput`, `separator`, `accentSoft`,
  `onAccentSoft`, `scrim`, `skeletonBase`, `skeletonHighlight`.
- Add **dynamic color** (Material You) as an opt-in setting: when enabled,
  derive `accent`/`accentContainer` from `dynamicLightColorScheme` /
  `dynamicDarkColorScheme` (API 31+) while keeping semantic status colors fixed.
- Ensure all text pairs meet 4.5:1; verify with a token contrast test.

### 4.2 Typography

- Replace `FontFamily.Default` with a **variable system font stack** and tuned
  scale. On Android use `FontFamily.SansSerif` with explicit weights; consider
  bundling Inter or SF-like `Google Sans`/`Roboto Flex` for headings.
- Define a real type ramp with tracking and line-height:
  - `largeTitle` 34/41 bold (screen titles, collapsible)
  - `title1` 28/34 bold, `title2` 22/28 semibold, `title3` 20/25 semibold
  - `headline` 17/22 semibold, `body` 17/24 regular, `callout` 16/21
  - `subhead` 15/20, `footnote` 13/18, `caption` 12/16
- Use **negative tracking** on large titles and **positive tracking** on
  captions, matching Apple's optical rhythm.
- Chat body should default to 17 sp with 1.4 line height; keep the existing
  `ChatFontSize` preference mapped onto the new ramp.
- Code: keep monospace but add a subtle background, 13 sp, 1.5 line height.

### 4.3 Spacing, shape, elevation

- Keep the 4/8 dp scale but add semantic layout tokens: `screenPadding` (20 dp),
  `sectionGap` (28 dp), `rowMinHeight` (52 dp), `cardPadding` (16 dp),
  `bubblePaddingH` (14 dp), `bubblePaddingV` (10 dp).
- Shape scale: `xs 8`, `sm 12`, `md 16`, `lg 20`, `xl 28`, `bubble 20` with a
  **4 dp tail corner** on the sender side for chat bubbles.
- Elevation: define `level0` (flat), `level1` (1 dp + soft shadow), `level2`
  (3 dp), `level3` (8 dp for sheets/FAB). Prefer tonal surfaces over heavy
  shadows; use a custom `Modifier.softShadow()` with low-alpha ambient shadow.

### 4.4 Iconography

- Add `androidx.compose.material:material-icons-extended` (or a curated
  `Icons.Rounded` subset) to the version catalog and app/feature modules.
- Replace every text glyph:
  - `☰` → `Icons.Rounded.Menu`
  - `⌨` → `Icons.Rounded.Keyboard`
  - `⋯` → `Icons.Rounded.MoreHoriz`
  - `…` typing → animated three-dot indicator
  - `→` send → `Icons.Rounded.ArrowUpward` in a circular accent button
  - `✓` copied → `Icons.Rounded.Check`
- Standardize icon size (20 dp inline, 24 dp actions) and stroke weight.
- Add a small branded app mark for splash and About.

### 4.5 Motion

- Expand `ChatUiMotion` with spring specs and named transitions:
  - `springSnappy` (stiffness 500, damping 0.85) for press/selection
  - `springGentle` (stiffness 300, damping 0.9) for layout/expand
  - `fadeThrough` 180 ms, `sharedAxisX` 220 ms, `emphasized` 300 ms
- Provide reusable `Modifier`/composable helpers: `animatePress`, `fadeInScale`,
  `AnimatedContent` wrappers, `animateItemPlacement` for lists.
- Respect `LocalReducedMotion` everywhere; when reduced, snap to end state.

### 4.6 Haptics

- Add a `Haptics` helper using `LocalHapticFeedback` + `HapticFeedbackType`:
  - light tick on selection/toggle
  - confirm on send, copy, save
  - reject on validation failure
  - long-press on message actions
- Gate behind a settings toggle ("Haptic feedback", default on).

---

## 5. Shell and navigation

### 5.1 Top app bar

- Replace the stock `TopAppBar` with a **collapsing large-title bar**:
  - Large title (34 sp) that shrinks to a centered inline title on scroll.
  - Translucent background with a hairline separator that fades in on scroll.
  - Leading icon: menu (compact) or none (expanded); trailing: contextual
    actions (share, settings) as icon buttons, not text.
- On the chat screen, move the conversation title into the app bar and remove
  the duplicated in-content header row (`ChatScreen.kt:330`). Keep the model as
  a tappable subtitle/chip that opens the model picker.
- Add a subtle **status-bar scrim** so content scrolling under the bar stays
  legible.

### 5.2 Sidebar / drawer

- Expanded sidebar: grouped sections ("Chats", "Library", "Settings") with
  section headers, icon + label rows, selected row with accent-soft pill and
  rounded 12 dp corners.
- Compact drawer: full-height sheet with rounded trailing corners, a header
  with app mark + "New chat" primary button, then the conversation list.
- Animate drawer open/close with the standard predictive-back scale/translate;
  add a scrim fade.
- Add a **"New chat" FAB or prominent button** in the sidebar header; today the
  only new-chat affordance is inside the conversation list header.

### 5.3 Navigation transitions

- Use `NavHost` enter/exit transitions: shared-axis X for lateral navigation,
  fade-through for top-level destination switches, and a subtle scale+fade for
  chat open.
- Preserve predictive back: drawer → sheet → dialog → keyboard → navigate.
- Add a **bottom navigation bar** for compact widths? Decision: keep the
  sidebar-only model (per M3-00.2) but add a compact bottom bar with Chats /
  Providers / Settings for one-handed reachability. If rejected, document why.

### 5.4 Insets and edge-to-edge

- Keep edge-to-edge; ensure the composer sits above the IME with a rounded
  floating container and a bottom safe-area gradient.
- Add `WindowInsets.navigationBars` handling to the composer so it never sits
  flush against the gesture bar.

---

## 6. Chat experience (highest-impact surface)

### 6.1 Message list

- **Bubbles:** max width 78% (user right, assistant left), 20 dp radius with a
  4 dp tail corner, 14×10 dp padding, 2 dp vertical gap within a group and
  12 dp between groups.
- **Grouping:** consecutive messages from the same role share a group; show the
  role/avatar only on the first message; timestamps appear on tap or at group
  end, not above every message.
- **Avatars:** small 28 dp assistant avatar (brand mark) and a user initial
  circle; optional, but strongly recommended for warmth.
- **Assistant surface:** grouped background, no border; user surface:
  accent-tinted. Remove the per-message role label row.
- **Streaming:** replace the `…` glyph with a three-dot bouncing indicator, then
  a blinking caret at the end of streamed text. Add a subtle shimmer on the
  bubble while waiting for the first token.
- **Reasoning:** collapse into a disclosure row with a chevron and a one-line
  preview; animate expand/collapse with `animateContentSize`.
- **Code blocks:** rounded 12 dp container, language pill, copy icon button with
  a check confirmation, wrap/scroll toggle as an icon, horizontal scroll with
  edge fade.
- **Actions:** move copy/regenerate/edit into a **long-press context menu** and
  a trailing `⋯` on hover/focus; keep a compact inline row only for the last
  assistant message. Animate the action row in.
- **Meta:** model/usage moves into a collapsible "details" sheet or a subtle
  footnote, not a permanent line under every message.

### 6.2 Scroll behavior

- Add a **scroll-to-bottom FAB** that appears when the user scrolls up during
  streaming, with a "new messages" pill when content arrives off-screen.
- Use `animateScrollToItem` for programmatic scrolls; never jump.
- Preserve scroll position across rotation and process death.
- Add top/bottom content fades so messages dissolve under the bars.

### 6.3 Composer

- Replace the `OutlinedTextField` + text buttons with a **floating rounded
  input bar**:
  - Leading `+` attach button (opens a bottom sheet with Photo / File / Paste).
  - Multi-line auto-growing field with placeholder "Message…".
  - Trailing circular send button (arrow-up icon) that morphs into a stop
    square while streaming; animate the morph.
  - Attachment chips as rounded thumbnails with an `×` overlay, horizontally
    scrollable, with a remove animation.
- Move "Attach"/"Paste" out of the text row into the `+` sheet; remove the
  always-visible text buttons.
- Add a subtle border that brightens on focus and a soft shadow.
- Show a character/token hint only when near a limit.
- Offline state: replace the red text with a compact inline banner above the
  bar and disable send with a tooltip.
- Add a **send haptic** and a springy press scale on the send button.

### 6.4 Empty state

- Replace "Start the conversation" text with a centered, branded empty state:
  app mark, a warm one-line greeting, 2–3 suggested prompt chips, and a model
  selector. Animate in with a gentle fade+rise.

### 6.5 Availability and errors

- Convert the availability banner into a **card** with an icon, title, body, and
  a single primary action; use warning (amber) not error (red) for setup gaps.
- Errors: inline card with icon, human copy, "Details" disclosure, and a Retry
  button. Use a snackbar for transient failures with a Retry action.
- Add a "message failed" state with a retry chip attached to the bubble.

---

## 7. Conversations

- Replace card rows with **grouped list rows**: 52 dp min height, title
  (semibold), one-line preview snippet, relative timestamp, pin indicator, and
  a chevron. Selected row uses an accent-soft background.
- Add **swipe actions**: swipe right to pin, swipe left to archive/delete with
  a colored action background and haptic threshold.
- Replace the inline undo row with a **Snackbar** ("Conversation deleted" +
  Undo) anchored above the list, auto-dismissing with a progress ring.
- Search: move into the app bar as a search field that expands with animation;
  show recent searches and a clear button; highlight matches in results.
- Group headers: sticky, uppercase caption style with a subtle background.
- Empty state: illustration + "No conversations yet" + "Start a chat" button.
- Archived view: segmented control (Chats / Archived) instead of a text toggle.

---

## 8. Onboarding

- Add a **hero step** with the app mark, a short value proposition, and a
  "Get started" primary button; animate the mark in.
- Show a **step indicator** (2 steps) with animated progress.
- Provider picker: large selectable cards with provider logos, a "Recommended"
  badge, and a checkmark; animate selection.
- Key entry: a focused, secure card with a show/hide eye icon, paste button,
  inline validation, and a "Where do I find this?" help link.
- Validation: replace the spinner row with a button that morphs to a success
  check, then auto-advance with a fade.
- Catalog: show a skeleton list while loading, then a compact model list with
  free badges; collapse by default with a "Show all models" disclosure.
- Add a final "You're all set" confirmation with a confetti-free, tasteful
  check animation and a "Start chatting" button.

---

## 9. Providers and model picker

### 9.1 Provider list

- Group into "Connected" and "Available" sections.
- Provider rows: logo/initial avatar, name, status dot (enabled/disabled),
  key/usage summary as a subtitle, chevron. Remove the "Manage" text button.
- Move "Add provider" into a **FAB** or a top-right `+` that opens a bottom
  sheet with preset cards and a "Custom" option.
- Preset/custom forms move into dedicated sheets with grouped fields, section
  headers, and a sticky primary action; no more one long scroll.

### 9.2 Provider detail

- Header card: avatar, name, base URL, enabled switch.
- Grouped sections: "Key & usage", "Models", "Advanced".
- Usage/limit as a **progress bar** with a label, not plain text lines.
- Key rotation/removal in a destructive section with a confirmation sheet.
- Docs link as a row with an external-link icon.

### 9.3 Model picker

- Full-height sheet with a search field pinned at top, segmented filters
  (All / Free / Pinned), and grouped rows.
- Model row: name, provider, context/price as compact chips, free badge, and a
  checkmark for the selected model. Details move into an expandable section or
  a secondary sheet.
- Pin/hide via swipe or a trailing menu, not three text buttons per row.
- Add a skeleton list while loading and a friendly empty state.

---

## 10. Settings and data

- Convert the root settings into a **grouped inset list** (iOS style): rounded
  cards per section, icon + label + value + chevron rows, section footers for
  explanatory copy.
- Appearance: theme as a segmented control with live preview swatches; density
  and font size as steppers/sliders with live preview text.
- Chat defaults: group into "Model", "Generation", "Composer", "Privacy" cards;
  use sliders for temperature/top-p with numeric readouts; switches with
  descriptions.
- Data: destructive actions in a red-tinted section with typed confirmation in
  a sheet; export/import as rows with icons and progress states.
- About: app mark, version, and privacy copy in a clean grouped layout.
- Add a global **Snackbar host** for save/export/import results.

---

## 11. States and feedback system

Create a shared state kit in `core/designsystem`:

- `Skeleton` shimmer primitives (list, bubble, card variants).
- `EmptyState(icon, title, body, action)`.
- `ErrorState(icon, title, body, retry)`.
- `InlineBanner(severity, title, body, action)`.
- `LoadingButton` (spinner replaces label, width preserved).
- `SnackbarHost` with action support and consistent styling.
- `PullToRefresh` where lists can refresh (providers, models).

Rules:

- Never show raw "Loading…" text; use skeletons for content and spinners only
  for actions.
- Never use red for non-destructive setup gaps; reserve red for destructive and
  failed actions.
- Every async action has pending, success, and failure feedback.
- Preserve layout during state changes (no jumps); reserve space for banners.

---

## 12. Motion choreography

Define a small, consistent motion language:

| Interaction | Motion |
| --- | --- |
| Screen push/pop | shared-axis X 220 ms + fade |
| Top-level switch | fade-through 180 ms |
| Sheet open/close | spring slide + scrim fade |
| Dialog | scale 0.96→1 + fade 150 ms |
| List item insert/remove | `animateItemPlacement` + fade |
| Bubble appear | fade + 8 dp rise, 180 ms |
| Streaming caret | 1 s blink |
| Send button morph | spring width/icon crossfade |
| Selection/toggle | spring scale 0.96 press |
| Snackbar | slide up + fade |
| Large title collapse | scroll-linked interpolation |

All motion must be disabled under reduced motion (snap to end state) and must
never gate content visibility.

---

## 13. Accessibility and inclusivity

- Keep 48 dp targets; audit new icon buttons and swipe actions for alternatives
  (swipe must have a menu equivalent).
- Add content descriptions to every icon; remove text-glyph semantics hacks.
- Ensure dynamic type: test at 200% font scale; layouts must reflow, not clip.
- Maintain 4.5:1 contrast in both themes and with dynamic color.
- Announce streaming start/complete/error only (already implemented); keep it.
- Provide a "Reduce motion" and "Haptic feedback" setting in addition to the
  system setting.
- Support TalkBack traversal order and focus restoration after sheets/dialogs.
- Add a high-contrast theme option if dynamic color is enabled.

---

## 14. Performance and perceived performance

- Keep stable keys/content types; add `contentType` to conversation and model
  lists.
- Use `derivedStateOf` for scroll affordances; avoid recomposing history during
  streaming (already handled — preserve it).
- Prefetch/decode images at display size (already done); add placeholders and
  crossfade.
- Add baseline profile coverage for the new shell and chat entry.
- Target: cold start < 1.5 s, 60 fps scrolling on a 10k-message conversation,
  no dropped frames during streaming.
- Use `Modifier.graphicsLayer` for animated transforms; avoid layout thrash.
- Defer non-critical work (catalog, usage) until after first frame.

---

## 15. Theming and platform integration

- Edge-to-edge with transparent system bars and icon appearance driven by theme.
- Dynamic color opt-in (Material You) with a fallback palette.
- Splash: branded mark on the canvas color, with a smooth handoff to the shell
  (no white flash). Update `ic_splash.xml` and `themes.xml`.
- Support Android 12+ splash API and predictive back animations.
- Respect system dark mode and the persisted theme override.
- Add a subtle app-wide background texture/gradient? No — keep it flat and calm.

---

## 16. Shared component library to add

Create in `core/designsystem` (or a new `core/ui` module):

- `ChatUiScaffold` (large-title bar, snackbar host, insets).
- `GroupedList`, `GroupedSection`, `SettingsRow`.
- `PrimaryButton`, `SecondaryButton`, `IconAction`, `CircleIconButton`.
- `ChatBubble`, `TypingIndicator`, `StreamingCaret`.
- `AttachmentChip`, `ComposerBar`.
- `Skeleton`, `EmptyState`, `ErrorState`, `InlineBanner`.
- `SegmentedControl`, `Stepper`, `LabeledSlider`.
- `ProviderAvatar`, `ModelRow`.
- `Haptics`, `MotionSpecs`, `SoftShadow`.

Every screen should compose these primitives rather than raw Material widgets.

---

## 17. Phased implementation roadmap

### Phase 0 — Foundation (1 sprint)

1. Add icon dependency; replace all text glyphs.
2. Expand tokens: color roles, type ramp, shape, elevation, motion, haptics.
3. Add shared primitives: scaffold, grouped list, buttons, skeleton, states.
4. Add dynamic color opt-in and splash branding.
5. Add motion helpers and reduced-motion guards.

### Phase 1 — Shell and chat (1–2 sprints)

1. Collapsing large-title app bar + translucent bars.
2. Sidebar/drawer redesign with grouped nav and New chat.
3. Navigation transitions and predictive-back polish.
4. Chat bubbles, grouping, avatars, streaming indicator, scroll FAB.
5. Composer redesign with attach sheet and morphing send button.
6. Empty state and error/availability cards.

### Phase 2 — Conversations and onboarding (1 sprint)

1. Grouped conversation rows, swipe actions, snackbar undo, search in app bar.
2. Onboarding hero, step indicator, provider cards, key entry polish, success.

### Phase 3 — Providers, models, settings (1–2 sprints)

1. Provider list/detail redesign with grouped sections and usage bars.
2. Model picker sheet redesign with filters and skeletons.
3. Settings grouped lists, sliders, segmented controls, data sheets.
4. Global snackbar and feedback consistency.

### Phase 4 — Polish and verification (1 sprint)

1. Motion tuning and haptics pass.
2. Accessibility and dynamic-type audit.
3. Performance profiling and baseline profile update.
4. Roborazzi golden refresh; screenshot review in light/dark, phone/tablet.
5. Device matrix pass and release notes.

---

## 18. Acceptance criteria

- [ ] No text glyphs used as icons anywhere.
- [ ] Every screen uses the shared design-system primitives and tokens.
- [ ] All state changes animate (or snap under reduced motion) with no layout
      jumps.
- [ ] Chat has grouped bubbles, avatars, streaming indicator, scroll FAB, and a
      floating composer.
- [ ] Conversations support swipe actions and snackbar undo.
- [ ] Onboarding has a hero, step indicator, and success state.
- [ ] Providers/models/settings use grouped, hierarchical layouts.
- [ ] Skeletons replace text loading; errors use cards with retry.
- [ ] Haptics fire on send/copy/save/delete.
- [ ] Dynamic color opt-in works; both themes pass contrast.
- [ ] 200% font scale and TalkBack pass; 48 dp targets preserved.
- [ ] Cold start < 1.5 s; 60 fps scrolling; no streaming jank.
- [ ] Roborazzi goldens updated and reviewed for phone/tablet, light/dark.
- [ ] No regressions in existing Compose UI, Robolectric, or screenshot tests.

---

## 19. Risks and non-goals

- **Scope creep:** this is a visual/interaction pass; do not add features.
- **Test churn:** many tests assert on text labels and tags. Preserve test tags
  and accessible names; update copy assertions deliberately.
- **Motion sickness:** always honor reduced motion; never animate layout in a
  way that hides content.
- **Performance:** shimmer and blur can be expensive; use lightweight
  gradients and `graphicsLayer`.
- **Dynamic color:** can break brand consistency; keep it opt-in.
- **Non-goals:** localization, new providers, backup/sync, Play Store listing,
  and any behavior change to streaming, storage, or security.

---

## 20. File-level work map

| Area | Primary files |
| --- | --- |
| Tokens | `core/designsystem/{Color,Dimens,Typography,Motion,Theme}.kt` |
| Primitives | new files under `core/designsystem/` |
| Shell | `app/.../shell/{ChatUiApp,AdaptiveShell,ShellNavHost}.kt` |
| Chat | `feature/chat/{ChatScreen,ChatMessageList,ChatMessageItem,ChatComposer,ChatCodeBlock,ChatMessageActions}.kt` |
| Conversations | `feature/conversations/ConversationListScreen.kt` |
| Onboarding | `feature/onboarding/OnboardingScreen.kt` |
| Providers | `feature/providers/{ProviderListScreen,ProviderDetailScreen,ModelPickerSheet,ConversationSettingsSheet}.kt` |
| Settings | `feature/settings/{SettingsScreens,DataSettingsScreen}.kt` |
| Resources | `app/src/main/res/{values/themes.xml,drawable/ic_splash.xml}` |
| Dependencies | `android/gradle/libs.versions.toml`, module `build.gradle.kts` |

---

## 21. Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug
cd android && ./gradlew :app:connectedDebugAndroidTest   # device/emulator
```

Plus manual review: light/dark, phone/tablet, 200% font scale, TalkBack,
reduced motion, and a 10k-message scroll test.
