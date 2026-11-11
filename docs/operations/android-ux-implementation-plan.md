# Android UI/UX Polish Implementation Plan

- Source design review: [android-ux.md](../android-ux.md)
- Authoritative spec: [IMPLEMENTATION.md](../IMPLEMENTATION.md)
- Current milestone baseline: [M3 verification](../operations/M3/M3-verification.md) and [M4 verification](../operations/M4/M4-verification.md)
- Status: Proposed
- Prepared: 2026-11-11
- Scope: Visual, interaction, motion, feedback, and perceived-performance polish of the existing Android client. No new product features, provider protocols, backup/sync, or localization.
- Platforms: Android only
- Planning assumption: M1–M4 Android behavior is complete and is the functional baseline. This plan changes presentation and interaction, not data, security, or transport semantics.

## 1. Summary

The Android client is functionally complete but visually reads as a developer
scaffold. The design review in [android-ux.md](../android-ux.md) identifies
eight structural gaps: no iconography, no motion, no depth/shape language,
undifferentiated form-dump screens, a plain chat surface, text-only feedback,
a stock shell, and underused design tokens.

This plan converts that review into an ordered, test-aware implementation
program. It is deliberately front-loaded with a design-system foundation so
that screen work composes shared primitives instead of accumulating one-off
styling. The largest risks are not visual: they are **test churn** (many tests
assert on text labels and exact token values), **screenshot golden churn**, and
**scope creep** into feature behavior.

The plan is organized as 12 tasks across 5 phases. Foundation and shell/chat
carry the highest value; settings/providers are lower risk and can follow.

| Workstream | Current completion | Assessment |
| --- | --- | --- |
| Design tokens (color/type/shape/elevation) | Partial | Tokens exist but are web-derived, underused, and asserted by exact-value tests |
| Iconography | Missing | No icons dependency; text glyphs used as icons |
| Motion | Missing | Zero animation usage in app/feature code |
| Haptics | Missing | No haptic feedback anywhere |
| Shared UI primitives | Missing | No scaffold, grouped list, skeleton, empty/error state, snackbar kit |
| Shell and navigation | Partial | Adaptive sidebar exists; stock top bar, no transitions, no large title |
| Chat surface | Partial | Functional list/composer; flat bubbles, no grouping/streaming indicator/scroll FAB |
| Conversations | Partial | Grouping/search/undo exist; card rows, inline undo, no swipe actions |
| Onboarding | Partial | Functional two-step form; no hero, progress, or success state |
| Providers and models | Partial | Functional forms; no grouping, hierarchy, or skeletons |
| Settings and data | Partial | Functional forms; no grouped lists, sliders, or segmented controls |
| States and feedback | Missing | Text-only loading/errors; no skeletons, snackbars, or inline banners |
| Accessibility | Partial | 48 dp targets, semantics, reduced motion exist; new controls need audit |
| Performance | Partial | Stable keys and image sizing exist; new motion/shimmer needs profiling |

### 1.1 Critical path

```text
UX-00 Preflight, decisions, baseline
   |
UX-01 Design-system foundation (tokens, icons, motion, haptics, primitives)
   |
   +--> UX-02 Shell and navigation
   |       |
   |       +--> UX-03 Chat experience
   |       +--> UX-04 Conversations
   |       +--> UX-05 Onboarding
   |
   +--> UX-06 Providers and model picker
   +--> UX-07 Settings and data
   |
UX-08 States and feedback (can start after UX-01, adopted by UX-03..07)
   |
UX-09 Motion and haptics tuning (after UX-02..07)
   |
UX-10 Accessibility and dynamic type audit (after UX-02..07)
   |
UX-11 Performance and baseline profile (after UX-03, UX-09)
   |
UX-12 Screenshot goldens, device review, and exit evidence (final gate)
```

### 1.2 Estimate

| Staffing | Estimate | Notes |
| --- | --- | --- |
| One Android implementer | 22–30 working days | Foundation + shell + chat alone are ~2 weeks; settings/providers add another |
| Two Android implementers | 13–17 working days | Split: one on foundation/shell/chat, one on conversations/onboarding/providers/settings |
| Two implementers + design reviewer | 11–14 working days | Recommended; design review catches optical issues early and reduces rework |

The schedule risk is concentrated in UX-01 (token retune breaks tests) and
UX-12 (golden regeneration and device review).

## 2. Current-State Audit

### 2.1 Reusable assets

| Asset | Current state | Plan impact |
| --- | --- | --- |
| `core/designsystem/Color.kt` | Semantic `ChatUiColors` + Material mapping | Extend with grouped/input/separator/skeleton roles; retune values |
| `core/designsystem/Dimens.kt` | Spacing, radius, density tokens | Add layout/shape/elevation tokens; keep existing names |
| `core/designsystem/Typography.kt` | Body/code/heading + chat sizes | Replace with a full Apple-like ramp; keep `chatStyle()` contract |
| `core/designsystem/Motion.kt` | Fast/normal durations + reduced-motion observer | Add spring specs and named transitions; keep reduced-motion guard |
| `core/designsystem/Theme.kt` | Theme/density/reduced-motion provider | Add dynamic color opt-in and haptics provider |
| `core/designsystem/SecureScreen.kt` | FLAG_SECURE wrapper | Reuse unchanged |
| `app/.../shell/AdaptiveShell.kt` | Permanent sidebar ≥ 840 dp + modal drawer | Redesign visuals; keep breakpoint and test tags |
| `app/.../shell/ChatUiApp.kt` | Stock `Scaffold` + `TopAppBar` | Replace with `ChatUiScaffold` large-title bar |
| `feature/chat/ChatMessageList.kt` | Stable keys, streaming row, scroll logic | Add grouping, scroll FAB, animated placement |
| `feature/chat/ChatMessageItem.kt` | Markdown, attachments, actions, errors | Redesign bubbles, grouping, streaming indicator |
| `feature/chat/ChatComposer.kt` | Auto-grow field, attach menu, send/stop | Redesign as floating bar with morphing send |
| `feature/conversations/ConversationListScreen.kt` | Grouping, search, pin/archive, undo | Redesign rows, swipe actions, snackbar undo |
| `feature/onboarding/OnboardingScreen.kt` | Two-step form + catalog | Add hero, progress, success state |
| `feature/providers/*` | List/detail/model picker | Group, add skeletons, move forms to sheets |
| `feature/settings/*` | Root + section forms | Convert to grouped inset lists |
| `app/src/test/.../screenshots/ScreenshotTest.kt` | Roborazzi light/dark goldens | Regenerate and extend after each phase |
| `core/designsystem/.../DesignSystemTest.kt` | Exact token value assertions | Must be updated with the token retune |

### 2.2 Confirmed missing or unproven areas

| Capability | Current state | Consequence |
| --- | --- | --- |
| Icon dependency | No `material-icons` in `libs.versions.toml` or modules | Cannot replace text glyphs without adding a dependency |
| Motion usage | No `animate*`/`AnimatedVisibility`/`Crossfade` in app/feature | Every state change is a hard cut |
| Haptics | No `LocalHapticFeedback` usage | No tactile confirmation on send/copy/delete |
| Shared primitives | No scaffold/grouped list/skeleton/state kit | Screens duplicate raw Material widgets |
| Dynamic color | Not implemented | No Material You support |
| Dark theme resources | `values-night/colors.xml` only; no `values-night/themes.xml` | Splash/theme parent is light-only |
| Branded splash | Generic chat glyph in `ic_splash.xml` | No brand identity at launch |
| Scroll affordance | No scroll-to-bottom FAB or new-message pill | Users can lose their place during streaming |
| Snackbar host | No app-level snackbar | Undo and async results are inline text |
| Swipe actions | None | Conversation actions require the overflow menu |
| Skeletons | None | Loading is text |
| Large-title bar | None | Stock top bar with static title |
| Navigation transitions | None | Abrupt screen changes |
| Settings for haptics/dynamic color | `Settings` model has no such fields | New preferences require model + DataStore changes |

### 2.3 Test and evidence baseline

- `DesignSystemTest` asserts exact ARGB values, spacing, and font sizes. Any
  token retune will fail these tests; they must be updated in the same change.
- `ScreenshotTest` captures phone/tablet light/dark goldens for chat,
  conversations, onboarding, providers, model picker, settings, and data.
  Every visual change requires golden regeneration and review.
- Feature tests assert on visible text such as "Send", "Attach", "Copy",
  "Settings", "Share", "New chat", "Archived", and "Loading providers…".
  Replacing text buttons with icon buttons will break these assertions unless
  test tags and accessible names are preserved and assertions are updated.
- `ChatUiColors` and `Dimens` are data classes; adding fields is source-breaking
  for any exhaustive construction in tests. Prefer defaulted fields.
- `Settings` is serialized to DataStore. Adding fields requires defaults and a
  compatibility check; do not reorder or remove existing fields.

## 3. Gap Analysis

Status values:

- **Missing**: no implementation exists.
- **Partial**: foundation exists but the deliverable is not met or not proven.
- **Present**: complete, subject only to regression verification.
- **Blocked**: cannot proceed until a decision or dependency is resolved.

### 3.1 Foundation (F)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| F-01 | Icon dependency and icon set | Missing | No icons library; text glyphs used as icons | Add `material-icons-extended` (or curated `Icons.Rounded`) to catalog and modules | Build + icon usage grep |
| F-02 | Replace all text-glyph icons | Missing | `☰`, `⌨`, `⋯`, `…` in shell/conversations/chat | Replace with icons; keep test tags and content descriptions | Compose tests + grep |
| F-03 | Retuned semantic color palette | Partial | Web-derived palette; no grouped/input/separator/skeleton roles | Extend `ChatUiColors` with defaulted fields; retune light/dark values | `DesignSystemTest` update + contrast test |
| F-04 | Full type ramp | Partial | Body/code/heading only; default font family | Add largeTitle/title1-3/headline/body/callout/subhead/footnote/caption | Typography tests + screenshot review |
| F-05 | Shape and elevation scale | Partial | Radii exist; no elevation/shadow tokens | Add shape/elevation tokens and `Modifier.softShadow()` | Unit test + visual review |
| F-06 | Motion specs and helpers | Partial | Durations only; no springs or transitions | Add spring specs, `fadeThrough`, `sharedAxisX`, `animatePress` | Motion unit test + reduced-motion test |
| F-07 | Haptics helper and setting | Missing | No haptics; no setting | Add `Haptics` helper + `hapticFeedback` setting (default on) | Unit test + settings test |
| F-08 | Dynamic color opt-in | Missing | Not implemented | Add `dynamicColor` setting; derive accent on API 31+ | Theme test + device check |
| F-09 | Shared scaffold | Missing | Stock `Scaffold` per screen | Add `ChatUiScaffold` with large-title bar, snackbar host, insets | Compose test |
| F-10 | Grouped list primitives | Missing | Raw `Column`/`LazyColumn` forms | Add `GroupedList`, `GroupedSection`, `SettingsRow` | Compose test |
| F-11 | Button/action primitives | Missing | Raw `Button`/`TextButton` | Add `PrimaryButton`, `SecondaryButton`, `IconAction`, `CircleIconButton` | Compose test |
| F-12 | State kit | Missing | Text-only loading/errors | Add `Skeleton`, `EmptyState`, `ErrorState`, `InlineBanner`, `LoadingButton` | Compose test |
| F-13 | Branded splash and dark theme resources | Partial | Generic glyph; no `values-night/themes.xml` | Add brand mark, dark theme parent, splash handoff | Resource review + device check |

### 3.2 Shell and navigation (S)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| S-01 | Collapsing large-title app bar | Missing | Stock `TopAppBar` | Implement scroll-linked large title with inline collapse | Compose test + screenshot |
| S-02 | Translucent bars and scroll scrim | Missing | Opaque bars | Add translucent surface + hairline separator fade | Screenshot review |
| S-03 | Chat title in app bar | Partial | Title duplicated in content header | Move title/model into app bar; remove duplicate header | Chat screen test |
| S-04 | Sidebar redesign | Partial | Functional but plain `NavigationDrawerItem`s | Grouped sections, icons, selected pill, New chat button | Shell tests + screenshot |
| S-05 | Drawer redesign | Partial | Plain modal sheet | Rounded sheet, header, New chat, animated scrim | Shell tests |
| S-06 | Navigation transitions | Missing | No enter/exit transitions | Add shared-axis/fade-through transitions | Navigation test |
| S-07 | Predictive-back ordering | Present | Implemented in M3 | Verify still correct after shell rewrite | Back-policy tests |
| S-08 | Compact bottom navigation decision | Missing | Sidebar-only model | Decide bottom bar vs sidebar-only; document | Decision record |
| S-09 | Composer safe-area treatment | Partial | IME padding exists | Add floating composer + bottom gradient | Insets test + device |

### 3.3 Chat (C)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| C-01 | Bubble geometry and alignment | Partial | Full-width flat boxes | 78% max width, tail corner, tuned padding | Screenshot + Compose test |
| C-02 | Message grouping and avatars | Missing | Role label above every message | Group consecutive roles; avatar on first; timestamp on tap/end | Compose test |
| C-03 | Streaming indicator | Partial | `…` glyph | Three-dot bounce + caret + first-token shimmer | Compose test + screenshot |
| C-04 | Reasoning disclosure | Partial | Text button + plain text | Animated disclosure with preview | Compose test |
| C-05 | Code block polish | Partial | Functional, text buttons | Rounded container, language pill, icon copy/wrap | Code block test |
| C-06 | Message actions | Partial | Always-visible text buttons | Long-press menu + trailing overflow; inline for last only | Actions test |
| C-07 | Scroll-to-bottom FAB / new-message pill | Missing | None | Add FAB and pill with derived state | List test |
| C-08 | Animated scroll | Partial | `requestScrollToItem` jumps | Use `animateScrollToItem`; preserve position | List test |
| C-09 | Composer redesign | Partial | `OutlinedTextField` + text buttons | Floating bar, `+` attach sheet, morphing send/stop | Composer test + screenshot |
| C-10 | Attachment chips | Partial | Text rows | Thumbnail chips with remove animation | Composer test |
| C-11 | Empty state | Partial | Plain text | Branded empty state + prompt chips + model selector | Screenshot + test |
| C-12 | Availability/error cards | Partial | Inline red text | Icon cards, warning vs error, retry, snackbar | Chat screen test |

### 3.4 Conversations (V)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| V-01 | Grouped list rows | Partial | Card rows | 52 dp rows, preview, relative time, pin, chevron | List test + screenshot |
| V-02 | Swipe actions | Missing | Overflow menu only | Swipe pin/archive/delete with haptic threshold | Gesture test |
| V-03 | Snackbar undo | Partial | Inline undo row | App snackbar with Undo + progress | List test |
| V-04 | Search in app bar | Partial | Inline `OutlinedTextField` | Expanding search field, clear, match highlight | Search test |
| V-05 | Sticky group headers | Partial | Plain headers | Sticky uppercase captions | List test |
| V-06 | Empty state | Partial | Plain text | Illustration + CTA | Screenshot |
| V-07 | Segmented Chats/Archived | Partial | Text toggle | Segmented control | List test |
| V-08 | Animated item placement | Missing | Instant list changes | `animateItemPlacement` + fade | List test |

### 3.5 Onboarding (O)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| O-01 | Hero welcome step | Partial | Plain text | Brand mark, value prop, primary CTA, entrance animation | Screenshot + test |
| O-02 | Step indicator | Missing | None | Two-step animated progress | Test |
| O-03 | Provider cards | Partial | Radio rows | Selectable cards with logos, badge, checkmark | Test |
| O-04 | Key entry polish | Partial | Plain fields | Secure card, show/hide, paste, inline validation, help link | Test |
| O-05 | Validation success state | Partial | Spinner + text | Button morph to check, auto-advance | Test |
| O-06 | Catalog skeleton + disclosure | Partial | Text loading + full list | Skeleton, collapse by default, "Show all" | Test |
| O-07 | Completion state | Missing | None | "You're all set" + check animation + CTA | Test |

### 3.6 Providers and model picker (P)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| P-01 | Provider list grouping | Partial | Flat list + inline forms | Connected/Available sections, avatar rows, status dot | Test + screenshot |
| P-02 | Add-provider sheet | Partial | Inline forms | FAB/`+` opens preset/custom sheet | Test |
| P-03 | Provider detail sections | Partial | Flat card + text lines | Header card + grouped sections + usage bar | Test + screenshot |
| P-04 | Destructive key actions | Partial | Inline buttons | Destructive section + confirmation sheet | Test |
| P-05 | Model picker filters | Partial | Search only | Segmented All/Free/Pinned | Test |
| P-06 | Model row redesign | Partial | Dense card + 3 text buttons | Compact row, chips, checkmark, swipe/menu pin/hide | Test |
| P-07 | Model details disclosure | Partial | Always expanded | Expandable/secondary sheet | Test |
| P-08 | Model picker skeleton/empty | Partial | Text loading | Skeleton + friendly empty | Test |

### 3.7 Settings and data (T)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| T-01 | Grouped settings root | Partial | `TextButton` list | Inset grouped list with icons/values/chevrons | Test + screenshot |
| T-02 | Appearance controls | Partial | Filter chips | Segmented theme + live preview; density/font steppers | Test |
| T-03 | Chat defaults grouping | Partial | One long form | Model/Generation/Composer/Privacy cards; sliders | Test |
| T-04 | Data destructive section | Partial | Inline red text | Red-tinted section + typed confirmation sheet | Test |
| T-05 | About layout | Partial | Plain text | Brand mark + grouped rows | Screenshot |
| T-06 | Global snackbar results | Missing | Inline text | Snackbar host for save/export/import | Test |

### 3.8 States, accessibility, performance, quality (X/A/R/Q)

| ID | Required deliverable | Current | Gap | Resolution | Verification |
| --- | --- | --- | --- | --- | --- |
| X-01 | Skeleton loading everywhere | Missing | Text loading | Adopt `Skeleton` in all async screens | Grep + tests |
| X-02 | Consistent error/empty states | Partial | Ad-hoc text | Adopt `ErrorState`/`EmptyState` | Tests |
| X-03 | Snackbar feedback | Missing | Inline text | Adopt app snackbar host | Tests |
| X-04 | Pull-to-refresh | Missing | None | Add to providers/models where refresh exists | Test |
| A-01 | Icon content descriptions | Partial | Text glyphs with semantics hacks | Real icons + descriptions | Semantics test |
| A-02 | 48 dp targets on new controls | Partial | Existing pass | Audit new icon/swipe controls | Compose test |
| A-03 | Dynamic type at 200% | Partial | Not verified | Reflow audit; fix clipping | Manual + screenshot |
| A-04 | Contrast in both themes + dynamic color | Partial | Not verified | Contrast test | Unit test |
| A-05 | Reduced motion for all new motion | Partial | Guard exists | Apply guard to every animation | Motion test |
| A-06 | Haptic setting and TalkBack order | Missing | No haptics; order unverified | Add setting; verify traversal | Manual + test |
| R-01 | No streaming jank with new motion | Partial | Baseline exists | Profile; isolate streaming row | Macrobenchmark/manual |
| R-02 | 60 fps list scrolling | Partial | Baseline exists | Profile conversations/models | Macrobenchmark |
| R-03 | Cold start < 1.5 s | Partial | Baseline exists | Re-measure after shell rewrite | Baseline profile |
| R-04 | Baseline profile update | Partial | Exists | Regenerate for new shell/chat | `generateBaselineProfile` |
| Q-01 | Update `DesignSystemTest` | Present | Asserts old values | Update with retune | Unit test |
| Q-02 | Update text-label assertions | Present | Many text assertions | Preserve tags/names; update copy assertions | Full test run |
| Q-03 | Regenerate Roborazzi goldens | Present | Old goldens | Regenerate per phase and review | `verifyRoborazziDebug` |
| Q-04 | New Compose tests for primitives | Missing | None | Add tests for each new primitive | Unit test |
| Q-05 | Device review matrix | Partial | M3/M4 evidence | Re-run phone/tablet light/dark/TalkBack | Device evidence |

## 4. Task Breakdown

| Order | Task | Depends on | Area | Parallel-safe with |
| --- | --- | --- | --- | --- |
| 1 | UX-00 Preflight, decisions, and baseline capture | none | preflight | none; blocks all |
| 2 | UX-01 Design-system foundation | UX-00 | foundation | none; blocks screens |
| 3 | UX-02 Shell and navigation | UX-01 | shell | UX-06, UX-07 |
| 4 | UX-03 Chat experience | UX-01, UX-02 | chat | UX-04, UX-05 |
| 5 | UX-04 Conversations | UX-01, UX-02 | conversations | UX-03, UX-05 |
| 6 | UX-05 Onboarding | UX-01 | onboarding | UX-03, UX-04 |
| 7 | UX-06 Providers and model picker | UX-01 | providers | UX-02, UX-07 |
| 8 | UX-07 Settings and data | UX-01 | settings | UX-02, UX-06 |
| 9 | UX-08 States and feedback adoption | UX-01, UX-03..07 | cross-cutting | none; touches all screens |
| 10 | UX-09 Motion and haptics tuning | UX-02..07 | motion | UX-10 |
| 11 | UX-10 Accessibility and dynamic type audit | UX-02..07 | accessibility | UX-09 |
| 12 | UX-11 Performance and baseline profile | UX-03, UX-09 | performance | UX-12 |
| 13 | UX-12 Screenshot goldens, device review, exit evidence | all | evidence | none; final gate |

### 4.1 Task details

#### UX-00 — Preflight, decisions, and baseline capture

- Confirm the design review is the source of truth and freeze the token
  direction (palette, type ramp, shape, motion).
- Decide: compact bottom navigation vs sidebar-only (S-08); dynamic color
  default; haptics default; whether to bundle a custom font.
- Capture current Roborazzi goldens and a baseline cold-start/scroll profile.
- Inventory every test that asserts on text labels or exact token values.
- Record decisions in a short decision log in this plan or a sibling file.

#### UX-01 — Design-system foundation

- Add icons dependency; replace text glyphs.
- Extend `ChatUiColors`, `Dimens`, `Typography`, `Motion`; add `Haptics`,
  `SoftShadow`, `MotionSpecs`.
- Add primitives: `ChatUiScaffold`, `GroupedList`, `SettingsRow`, buttons,
  `Skeleton`, `EmptyState`, `ErrorState`, `InlineBanner`, `LoadingButton`,
  `SegmentedControl`, `Stepper`, `LabeledSlider`.
- Add dynamic color opt-in and haptics setting (model + DataStore + UI).
- Update `DesignSystemTest` and add primitive tests.
- Update splash/brand mark and `values-night/themes.xml`.

#### UX-02 — Shell and navigation

- Implement `ChatUiScaffold` large-title bar with scroll-linked collapse.
- Redesign sidebar/drawer with grouped nav, icons, New chat.
- Add navigation transitions and verify predictive back.
- Move chat title/model into the app bar; remove duplicate header.
- Add composer safe-area treatment.

#### UX-03 — Chat experience

- Bubble geometry, grouping, avatars, timestamps.
- Streaming indicator, caret, first-token shimmer.
- Reasoning disclosure, code block polish, message action menu.
- Scroll FAB/new-message pill and animated scroll.
- Composer redesign, attach sheet, attachment chips, morphing send.
- Empty state and availability/error cards.

#### UX-04 — Conversations

- Grouped rows, swipe actions, snackbar undo, app-bar search, sticky headers,
  empty state, segmented Chats/Archived, animated placement.

#### UX-05 — Onboarding

- Hero, step indicator, provider cards, key entry polish, validation success,
  catalog skeleton/disclosure, completion state.

#### UX-06 — Providers and model picker

- Provider list grouping, add-provider sheet, detail sections, usage bar,
  destructive actions, model filters/rows/details/skeleton.

#### UX-07 — Settings and data

- Grouped root, appearance controls, chat defaults grouping, data destructive
  section, about layout, snackbar results.

#### UX-08 — States and feedback adoption

- Replace all text loading with skeletons; adopt error/empty/banner/snackbar
  consistently; add pull-to-refresh where refresh exists.

#### UX-09 — Motion and haptics tuning

- Apply the motion language table from the design review; tune springs; add
  haptics on send/copy/save/delete/selection; verify reduced motion.

#### UX-10 — Accessibility and dynamic type audit

- Icon descriptions, 48 dp targets, 200% font scale, contrast, TalkBack order,
  focus restoration, haptic setting.

#### UX-11 — Performance and baseline profile

- Profile streaming and list scrolling; regenerate baseline profile; re-measure
  cold start; document methodology and hardware.

#### UX-12 — Screenshot goldens, device review, exit evidence

- Regenerate and review all goldens (phone/tablet, light/dark); run device
  matrix; record evidence; update this plan's status.

## 5. Phased Roadmap

| Phase | Tasks | Outcome |
| --- | --- | --- |
| 0 — Foundation | UX-00, UX-01 | Tokens, icons, motion, haptics, primitives, splash |
| 1 — Shell and chat | UX-02, UX-03 | Large-title shell, redesigned chat and composer |
| 2 — Content screens | UX-04, UX-05 | Polished conversations and onboarding |
| 3 — Management screens | UX-06, UX-07 | Polished providers, models, settings, data |
| 4 — Polish and evidence | UX-08, UX-09, UX-10, UX-11, UX-12 | Consistent states, tuned motion, accessibility, performance, goldens |

## 6. Test Strategy and Churn Management

- **Preserve test tags and accessible names.** Replace text buttons with icon
  buttons but keep the same `testTag` and `contentDescription`; update only
  assertions that read visible text.
- **Update token tests in the same change as the retune.** `DesignSystemTest`
  asserts exact ARGB/sp/dp values; do not leave it failing across tasks.
- **Regenerate goldens per phase, not once at the end.** Review each diff for
  unintended layout shifts.
- **Add primitive tests before adopting primitives.** A broken `Skeleton` or
  `GroupedList` should fail in isolation, not across ten screens.
- **Keep behavior tests green.** This plan must not change streaming, storage,
  security, or navigation semantics; if a test fails for behavior reasons, stop
  and investigate rather than rewriting the test.
- **Add a contrast test** for both themes and dynamic color.
- **Add a reduced-motion test** that asserts animations snap to end state.

## 7. Acceptance Criteria

- [ ] No text glyphs used as icons anywhere.
- [ ] Every screen composes shared design-system primitives and tokens.
- [ ] All state changes animate or snap under reduced motion with no layout jumps.
- [ ] Chat has grouped bubbles, avatars, streaming indicator, scroll FAB, and a
      floating composer.
- [ ] Conversations support swipe actions and snackbar undo.
- [ ] Onboarding has a hero, step indicator, and success state.
- [ ] Providers/models/settings use grouped, hierarchical layouts.
- [ ] Skeletons replace text loading; errors use cards with retry.
- [ ] Haptics fire on send/copy/save/delete and can be disabled.
- [ ] Dynamic color opt-in works; both themes pass contrast.
- [ ] 200% font scale and TalkBack pass; 48 dp targets preserved.
- [ ] Cold start < 1.5 s; 60 fps scrolling; no streaming jank.
- [ ] Roborazzi goldens updated and reviewed for phone/tablet, light/dark.
- [ ] No regressions in existing Compose, Robolectric, or screenshot tests.
- [ ] Decisions (bottom nav, dynamic color, haptics, font) are recorded.

## 8. Verification

```bash
make android-lint && make android-test && make android-assemble
cd android && ./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug
cd android && ./gradlew :app:connectedDebugAndroidTest   # device/emulator
cd android && ./gradlew :app:generateBaselineProfile     # device/emulator
```

Plus manual review: light/dark, phone/tablet, 200% font scale, TalkBack,
reduced motion, haptics on/off, dynamic color on/off, and a 10k-message scroll
test.

## 9. Risks and Decisions

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Token retune breaks `DesignSystemTest` | High | Update tests in the same change; add contrast test |
| Text-label assertions break | High | Preserve tags/names; update copy assertions deliberately |
| Golden churn obscures regressions | Medium | Regenerate per phase and review diffs |
| Motion causes jank | Medium | Use `graphicsLayer`; profile streaming; honor reduced motion |
| Shimmer/blur cost | Medium | Lightweight gradients; avoid blur on large surfaces |
| Scope creep into features | High | Reject behavior changes; file follow-ups |
| Dynamic color breaks brand | Low | Opt-in only; keep status colors fixed |
| Settings serialization break | Medium | Add defaulted fields; compatibility test |
| Device availability | Medium | Capture Robolectric goldens early; schedule device pass |

Decisions to confirm in UX-00:

- Compact bottom navigation vs sidebar-only.
- Dynamic color default (recommended: off, opt-in).
- Haptics default (recommended: on, with setting).
- Custom font vs system font (recommended: system font first).
- Whether to add a `core:ui` module or keep primitives in `core:designsystem`
  (recommended: keep in `core:designsystem` to avoid module churn).

## 10. File-Level Work Map

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
| Settings model | `core/model/Settings.kt`, `core/datastore/settings/*` |
| Resources | `app/src/main/res/{values/themes.xml,values-night/themes.xml,drawable/ic_splash.xml}` |
| Dependencies | `android/gradle/libs.versions.toml`, module `build.gradle.kts` |
| Tests | `core/designsystem/src/test/...`, `app/src/test/.../screenshots/ScreenshotTest.kt`, feature test suites |

## 11. Out of Scope

- New product features, provider protocols, or transport changes.
- Backup/sync (M5) and managed hosting (M6).
- Localization and translations.
- Play Store listing, store copy, and data-safety forms.
- Web and Rust clients.
- Any change to secret storage, backup exclusion, or network security behavior.
