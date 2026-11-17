# Android minimalist redesign - implementation tasks

- Source plan: [Android minimalist design plan](../../android-minimalist-design-plan.md)
- Behavior authority: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Blocked
- Scope: Android presentation, navigation integration, accessibility, and resource efficiency. Preserve functionality.
- Breakdown: 41 tasks across the source plan's UX-00 through UX-06 phases.
- Implementation: UX-01 through UX-05 presentation/integration is present;
  UX-06 adds production-shell goldens, route/file/focus regressions and an
  asserted profile journey. Fourteen tasks are Complete; the other 27 retain
  explicit prerequisite/device/full-gate blockers. No generated-profile,
  physical performance or usability acceptance is claimed. UX-00.3 remains
  owner-deferred, not passed.

## Folder and numbering contract

All task files live directly in this folder, without per-task subfolders.
Keep the parent phase number and subtask number in each filename; do not
reuse the scheduling order as its ID.

```text
docs\operations\android-minimalist-design-plan\
  README.md
  UX-00.1-functional-inventory.md
  UX-00.2-fixtures-and-visual-baseline.md
  ...
  UX-06.6-parity-and-closeout.md
```

Record decisions, evidence summaries, and handoffs in the existing task file's
`Handoff notes` section; do not create companion documents or task subfolders.
Keep large generated reports, APKs, traces, and routine build output outside
Git; link to approved artifact storage from those notes. Do not fabricate
empty results or completion reports during planning.

This is a new pack. The completed `docs\operations\android-ux` pack describes
an older design, uses overlapping parent IDs, and is not a dependency or a
status source. Always qualify this pack when selecting a task.

## Execution contract

1. Select one task whose explicit dependencies are all `Complete`. Read this
   README, that task, its source-plan sections, and its listed source/tests.
   The `Order` column is a valid sequential execution order, not an instruction
   to add dependencies on every earlier task.
2. Set `Status` to `In progress` in the task and this index. Do not start the
   next task merely because its number follows.
3. Implement only its owned slice and directly dependent call sites. Every
   task also owns its task document and its index status row.
   Record a concrete blocker before expanding into unrelated files or product
   behavior. Small private helpers may stay beside their owning screen.
4. Preserve current working-tree changes and existing services/ViewModels.
   Do not add a UI module, navigation framework, dependency upgrade, database
   migration, provider protocol, or reduced-feature mode.
5. Add focused regression cases with the implementation, preferably in the
   existing test files named by the task. Do not postpone all testing until
   UX-06 or weaken behavior assertions to accommodate lost functionality.
6. Run the task's focused checks and affected-module lint. Commands below
   assume PowerShell starting at the repository root. Do not install/upgrade
   tooling speculatively; record environment failures separately from app
   failures. A selector must run actual tests, not a zero-test task.
7. Preserve meaningful tags/accessibility names when moving controls. Update
   narrowly affected screenshots when a task changes them; UX-06.1 adds the
   integrated production-shell review. Never blanket-record goldens to make
   unexplained differences disappear.
8. Apply `contracts\parity.md`'s existing process in each change that alters
   user-visible behavior. Presentation differences must not conceal functional
   regressions; UX-06.6 reconciles the final evidence rather than deferring all
   parity work until the end.
9. Record actual commands/results, changed paths, acceptance evidence,
   decisions, remaining risks, and the next eligible task in the task file's
   existing `Handoff notes` section, without creating another document.
10. Mark `Complete` only when all acceptance criteria and required checks have
    evidence. Missing hardware/manual review is `Blocked`, not a pass. Update
    the task and index together, then stop at that task's boundary.

**Approved UX-00.3 exception (2026-11-17):** the owner selected "Accept an
explicitly labeled AVD baseline for UX-00.3; retain physical-device acceptance
for UX-06.4." UX-00.3 may complete with reviewed, reproducible AVD measurements
and frozen baseline artifacts; it must not claim entry-level/mid-tier physical
hardware or hardware-budget acceptance. Its existing dependency edges are
unchanged: UX-01.1 and UX-01.2 require this approved baseline gate, while
UX-06.4 still requires physical old/new measurements and blocks release
acceptance when hardware is missing. This is not a waiver for any other task.

**Owner-directed deferral (2026-11-17):** "skip the perfomance baseline and
lets get to the refinement". UX-00.3 remains `Blocked` with incomplete,
excluded pilot evidence; it is not a performance pass. UX-01.1 and UX-01.2
may proceed under this explicit prerequisite waiver. Keep the recorded
dependency edges and physical UX-06.4 acceptance requirements; remeasure
the preserved old build and candidate before making performance claims.

### Non-negotiable safeguards

- Keep all capabilities in source-plan section 6. A relocated action needs a
  discoverable, labeled entry; gestures are optional shortcuts.
- Keep minimum SDK 26, both densities, all chat font sizes, offline history
  and draft behavior, data/stream recovery, and existing attachment limits.
- No silent changes to selected models, request parameters, routing/privacy,
  costs, persisted defaults, draft capacity, or backup scheduling.
- `loadRemoteImages` currently defaults to true. Correct misleading wording;
  do not change defaults or reset preferences in this design program. Any
  product-default change needs separately approved work.
- Keep secrets/passphrases out of generic saved-state bundles, screenshots,
  logs, fixtures, and handoffs. Use invented content and fake credentials only;
  never make paid generation calls for validation or evidence.
- Back dismisses dialog, sheet, drawer, keyboard, then navigation, one layer
  per action. Consume system/IME insets once.
- Keep cross-feature presentation orchestration in `app`; opening a picker
  must not add provider-UI dependencies to the chat feature.
- Existing model-management and curated routing components need explicit
  production wiring; their existence is not proof of reachability.
- Disabled future per-conversation file export and new math rendering are
  outside scope. Working text/Markdown sharing and all-data export remain.

### Status values

| Status | Meaning |
| --- | --- |
| Not started | No implementation has begun; unmet prerequisites alone are not a blocker report. |
| In progress | The bounded task is actively being implemented. |
| Blocked | A concrete decision, environment, device, or defect prevents completion; name the blocker and owner. |
| Complete | Acceptance criteria and required verification have reviewed evidence. |

Do not cancel a preservation requirement to make the program appear complete.
Scope changes need explicit approval and an updated source plan/dependency map.

## Source-path aliases

Paths in task `Owns` and `Read first` sections are repository-relative.
Expand these aliases before opening files; they are documentation shorthand,
not new directories to create.

| Alias | Actual directory |
| --- | --- |
| Pack | `docs\operations\android-minimalist-design-plan` |
| Shell | `android\app\src\main\java\dev\chatui\shell` |
| AppTests | `android\app\src\test\java\dev\chatui` |
| Screenshots | `android\app\src\test\java\dev\chatui\screenshots` |
| DS | `android\core\designsystem\src\main\kotlin\dev\chatui\core\designsystem` |
| DSTests | `android\core\designsystem\src\test\kotlin\dev\chatui\core\designsystem` |
| Chat | `android\feature\chat\src\main\kotlin\dev\chatui\feature\chat` |
| History | `android\feature\conversations\src\main\kotlin\dev\chatui\feature\conversations` |
| Providers | `android\feature\providers\src\main\kotlin\dev\chatui\feature\providers` |
| Onboarding | `android\feature\onboarding\src\main\kotlin\dev\chatui\feature\onboarding` |
| SettingsUI | `android\feature\settings\src\main\kotlin\dev\chatui\feature\settings` |
| SettingsStore | `android\core\datastore\src\main\kotlin\dev\chatui\core\datastore\settings` |
| BackupUI | `android\feature\backup\src\main\kotlin\dev\chatui\feature\backup` |

Feature tests are under
`android\feature\<feature>\src\test\kotlin\dev\chatui\feature\<feature>`.
Read only the named service APIs needed by the task; service/storage behavior
is read-only unless a tightly coupled integration fix is explicitly required.

## Ordered task list

| Order | Task | Status | Depends on |
| --- | --- | --- | --- |
| 1 | [UX-00.1 Functional inventory and decisions](UX-00.1-functional-inventory.md) | Complete | none |
| 2 | [UX-00.2 Fixtures and visual baseline](UX-00.2-fixtures-and-visual-baseline.md) | Complete | UX-00.1 |
| 3 | [UX-00.3 Reference-device performance baseline](UX-00.3-device-performance-baseline.md) | Blocked | UX-00.2 |
| 4 | [UX-01.1 Semantic colors and contrast](UX-01.1-colors-and-contrast.md) | Complete | UX-00.3 |
| 5 | [UX-01.2 Density, spacing, and touch targets](UX-01.2-density-and-touch-targets.md) | Complete | UX-00.3 |
| 6 | [UX-01.3 Compact scaffold title bar](UX-01.3-compact-scaffold.md) | Complete | UX-01.1, UX-01.2 |
| 7 | [UX-01.4 Applied theme and feedback preferences](UX-01.4-theme-preferences.md) | Complete | UX-01.1 |
| 8 | [UX-01.5 Quiet state and motion primitives](UX-01.5-state-and-motion-primitives.md) | Complete | UX-01.3, UX-01.4 |
| 9 | [UX-02.1 Reusable history presentation](UX-02.1-history-presentation.md) | Complete | UX-01.5 |
| 10 | [UX-02.2 Real Chats root and adaptive history](UX-02.2-chats-root-and-drawer.md) | Complete | UX-02.1 |
| 11 | [UX-02.3 Toolbar overflow and hierarchical navigation](UX-02.3-toolbar-and-back.md) | Blocked | UX-02.2 |
| 12 | [UX-02.4 App-owned model-picker host](UX-02.4-model-picker-host.md) | Blocked | UX-02.3 |
| 13 | [UX-03.1 Content-first message geometry](UX-03.1-message-layout.md) | Complete | UX-01.5 |
| 14 | [UX-03.2 Message actions and metadata footer](UX-03.2-message-actions.md) | Complete | UX-03.1 |
| 15 | [UX-03.3 Responsive composer and single warning](UX-03.3-composer-and-warnings.md) | Blocked | UX-01.5 |
| 16 | [UX-03.4 Latest-message control and streaming motion](UX-03.4-scrolling-and-streaming.md) | Complete | UX-03.1 |
| 17 | [UX-03.5 Bounded remote Markdown images](UX-03.5-remote-images.md) | Complete | UX-03.1 |
| 18 | [UX-03.6 Background attachment ingestion](UX-03.6-attachment-ingestion.md) | Blocked | UX-03.3 |
| 19 | [UX-03.7 App-themed code and rendering reuse](UX-03.7-code-and-markdown-rendering.md) | Complete | UX-03.1 |
| 20 | [UX-04.1 Compact searchable model picker](UX-04.1-model-picker-presentation.md) | Blocked | UX-02.4 |
| 21 | [UX-04.2 Reachable model management](UX-04.2-manage-models-entry.md) | Blocked | UX-04.1 |
| 22 | [UX-04.3 Quiet provider list and truthful status](UX-04.3-provider-list.md) | Blocked | UX-02.4 |
| 23 | [UX-04.4 Focused add-provider forms](UX-04.4-add-provider.md) | Blocked | UX-04.3 |
| 24 | [UX-04.5 Provider detail and secure key editor](UX-04.5-provider-detail.md) | Blocked | UX-04.2, UX-04.4 |
| 25 | [UX-04.6 Conversation essentials and generation](UX-04.6-conversation-settings.md) | Blocked | UX-04.1 |
| 26 | [UX-04.7 Extras JSON and curated routing](UX-04.7-advanced-routing.md) | Blocked | UX-04.6 |
| 27 | [UX-04.8 Focused onboarding connection](UX-04.8-onboarding-connection.md) | Blocked | UX-02.4 |
| 28 | [UX-04.9 Optional backup onboarding](UX-04.9-onboarding-backup.md) | Blocked | UX-04.8 |
| 29 | [UX-05.1 Settings root and appearance](UX-05.1-settings-and-appearance.md) | Blocked | UX-02.4 |
| 30 | [UX-05.2 Global-default model-picker entry](UX-05.2-default-model-picker.md) | Blocked | UX-05.1, UX-04.1 |
| 31 | [UX-05.3 Grouped chat-default controls](UX-05.3-chat-defaults.md) | Blocked | UX-05.2 |
| 32 | [UX-05.4 Data actions and operation states](UX-05.4-data-and-storage.md) | Blocked | UX-05.1 |
| 33 | [UX-05.5 Quiet autosave and truthful privacy copy](UX-05.5-settings-feedback-and-copy.md) | Blocked | UX-05.3, UX-05.4 |
| 34 | [UX-05.6 Focused backup configuration](UX-05.6-backup-configuration.md) | Blocked | UX-04.9, UX-05.1 |
| 35 | [UX-05.7 Backup overview and recovery safeguards](UX-05.7-backup-overview.md) | Blocked | UX-05.6 |
| 36 | [UX-06.1 Integrated production-shell screenshots](UX-06.1-integrated-screenshots.md) | Blocked | UX-02.4, UX-03.2, UX-03.4, UX-03.5, UX-03.6, UX-03.7, UX-04.5, UX-04.7, UX-04.9, UX-05.5, UX-05.7 |
| 37 | [UX-06.2 Accessibility, focus, and inset acceptance](UX-06.2-accessibility-and-insets.md) | Blocked | UX-06.1 |
| 38 | [UX-06.3 Deterministic baseline-profile journey](UX-06.3-baseline-profile.md) | Blocked | UX-06.1 |
| 39 | [UX-06.4 Entry-level performance acceptance](UX-06.4-performance-acceptance.md) | Blocked | UX-06.3 |
| 40 | [UX-06.5 Device flows and usability walkthrough](UX-06.5-device-and-usability.md) | Blocked | UX-06.2 |
| 41 | [UX-06.6 Parity and implementation closeout](UX-06.6-parity-and-closeout.md) | Blocked | UX-06.1, UX-06.2, UX-06.3, UX-06.4, UX-06.5 |

Earlier verification records below are historical selected runs. Later changes
extend their test counts and replace local Gradle XML reports; the
[UX-05/06 record](#ux-0506-verification-record) is the current candidate gate.

## UX-01.2 through UX-03.7 verification record

The requested implementation is present, including the owner-approved bounded
header refinement recorded in UX-01.3 and the source plan. That selected run
recorded **505 passing tests, no failures/errors/skips**:

| Scope actually checked | Tests | Lint |
| --- | --- | --- |
| Full design-system unit suite | 51 | No errors; 5 warnings |
| Full chat unit suite | 170 | No errors; 3 warnings |
| Full conversations unit suite | 108 | No errors; 3 warnings |
| Selected provider picker/snapshot suites | 29 | No errors; 2 warnings |
| Selected app shell/theme/navigation/picker/intent/candidate suites | 147 | No errors; 16 warnings |

Production `:app:compileDebugKotlin` passed. Existing lint was used without
suppressing checks, upgrading dependencies, or claiming the warnings resolved.
Tasks were executed in combined invocations; these commands reproduce the
verified scopes from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:CHATUI_REFINEMENT_DIR = Join-Path (Get-Location) 'android\app\build\reports\android-minimalist-refinement'
Set-Location .\android
$flags = @(
  '--console=plain', '--quiet', '--no-daemon',
  '-Pkotlin.compiler.execution.strategy=in-process',
  '-Pandroid.baselineProfile.skipGeneration=true'
)
.\gradlew.bat @flags `
  :core:designsystem:testDebugUnitTest :core:designsystem:lintDebug `
  :feature:chat:testDebugUnitTest :feature:chat:lintDebug `
  :feature:conversations:testDebugUnitTest :feature:conversations:lintDebug `
  :feature:providers:testDebugUnitTest --tests '*ModelPicker*' --tests '*MessageModelSnapshotTest' `
  :feature:providers:lintDebug
.\gradlew.bat @flags `
  :app:testDebugUnitTest `
  --tests '*MotionHapticsThemeTest' --tests '*ShellThemeTest' `
  --tests '*AdaptiveShellTest' --tests '*ShellNavigationTest' `
  --tests '*ShellSidebarTest' --tests '*ShellInsetsTest' `
  --tests '*ShellTopBarTest' --tests '*ShellBackNavigationTest' `
  --tests '*BackPolicyTest' --tests '*KeyboardShortcutsTest' `
  --tests '*AccessibilityFocusTest' --tests '*AppModelPickerHostTest' `
  --tests '*DeepLink*' --tests '*ExternalIntent*' `
  --tests '*ShellBaselineScreenshotTest' `
  :app:lintDebug :app:compileDebugKotlin `
  '-Proborazzi.test.record=true'
```

JUnit reports are under each checked module's
`build\test-results\testDebugUnitTest`; lint XML/HTML/SARIF is under
`build\reports\lint-results-debug.*`. The recorded task scope is selected, not
the entire app/provider repository suite. No physical timing, frame, OOM, ANR,
vibration, or real permission-grant acceptance is inferred from these reports.

### Candidate visual evidence

There are **104 separate current-candidate PNGs**: 26 cases, each in comfortable/
compact and light/dark. The ignored artifact root is
`android\app\build\reports\android-minimalist-refinement`, with groups
`candidate-actual-shell`, `candidate-foundation`, and
`candidate-standalone-picker`. Both the environment variable and Roborazzi's
record property above are required; setting only the directory does not record.
Do not broaden recording to unrelated screenshot/golden suites.

Representative captures were reviewed for real Chats/empty/populated/search/
archive, 839/840 dp hosting, expanded theme colors, code/table/reasoning, local
images, errors/setup, controlled streaming, long history, and phone/landscape/
1280 dp reading width. Foundation cases cover 320 dp at 100/150/200% fonts.
They set Android's animator scale to 1/1/0 respectively and assert the
theme's observed reduced-motion value, without overriding that theme flag.
The test restores the prior scale afterward.

`chat_portrait_ime224_font200_*` and
`chat_landscape_ime224_font200_*` reserve a **simulated** 224 dp keyboard inset
at 320 x 568 and 640 x 360. Every theme/density variant asserts complete
48 dp input/Add/Send/Stop bounds above the inset. These captures intentionally
do not render a keyboard and are not evidence of real platform IME behavior.
Short attachment-disclosure/removal tests additionally exercise a 56 dp
composer viewport at 200% in both densities.

These are local review artifacts, not promoted/frozen goldens or completion
of UX-06.1's later full-screen-lane review. The old UX-00 captures/build and
fixtures remain unchanged. Preserve/promote accepted candidate evidence to
approved storage during the integrated UX-06 review.

### Earlier-slice acceptance blockers

`adb devices -l` reported no connected Android device. All requested code is
implemented; these statuses deliberately remain `Blocked`, not manual passes:

| Task | Blocker and owner |
| --- | --- |
| UX-02.3 | Android maintainer: real-IME portrait/landscape, 200% text, layered Back and return-focus review. |
| UX-02.4 | Android maintainer: close UX-02.3 and confirm actual picker dismissal/selection returns focus correctly. |
| UX-03.3 | Android maintainer: actual IME, both densities, Add/remove/Send/Stop and send-anyway consent. |
| UX-03.6 | Android maintainer: close UX-03.3 and verify real photo/SAF/paste/drop grants, revocation and cancellation. |

Record those results in the owning handoffs before marking the four tasks
Complete. UX-04 and subsequently UX-05/06 were implemented under explicit
batch requests; those requests are not evidence that UX-02.4 passed.
UX-06.2/06.5 retain the integrated accessibility/device matrix, and
UX-06.4 still requires physical performance comparisons. UX-00.3 remains
owner-deferred/Blocked; its original dependency edges, frozen old build and
budgets are unchanged.

## UX-04 verification record

UX-04.1 through UX-04.9 are implemented, including real provider-scoped model
management, app-owned conversation-picker return, coordinated settings/routing
writes and opt-in real backup setup. That selected run recorded
**312 passing tests, no failures/errors/skips**:

| Scope actually checked | Tests | Lint |
| --- | --- | --- |
| Selected provider/model/settings/routing suites | 144 | No errors; 2 warnings |
| Full onboarding unit suite | 45 | No errors; 1 warning |
| Backup settings screen suite | 6 | No errors; no warnings |
| SecureScreen suite | 2 | Not rerun; no design-system source change |
| Chat route/ViewModel suites | 15 | No errors; 3 warnings |
| Selected app shell/picker/intent/visual suites | 100 | No errors; 16 warnings |

These are selected results, not a full repository rerun or an addition to the
earlier slice's historical 505-case report. Current JUnit/lint report locations
are the same as above. Production `:app:assembleDebug` also passed; the local
APK is `android\app\build\outputs\apk\debug\app-debug.apk`.
Existing lint checks remain enabled; warnings were not claimed resolved.

The following commands reproduce the verified scopes from the repository root.
They were executed in bounded invocations; no paid generation or live backup
backend was used.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
Set-Location .\android
$flags = @(
  '--console=plain', '--quiet', '--no-daemon',
  '-Pkotlin.compiler.execution.strategy=in-process',
  '-Pandroid.baselineProfile.skipGeneration=true'
)
.\gradlew.bat @flags :feature:providers:testDebugUnitTest `
  --tests '*ModelPicker*' --tests '*MessageModelSnapshotTest' `
  --tests '*ProviderList*' --tests '*ProviderDetail*' `
  --tests '*ProviderRoutesTest' --tests '*ConversationSettings*' `
  --tests '*Routing*' --tests '*ParameterSupport*' --tests '*SheetBackTest' `
  :feature:providers:lintDebug
.\gradlew.bat @flags :feature:onboarding:testDebugUnitTest :feature:onboarding:lintDebug
.\gradlew.bat @flags `
  :feature:backup:testDebugUnitTest --tests '*BackupSettingsScreenTest' `
  :feature:backup:lintDebug `
  :core:designsystem:testDebugUnitTest --tests '*SecureScreenTest'
.\gradlew.bat @flags :feature:chat:testDebugUnitTest `
  --tests '*ChatRouteFlowTest' --tests '*ChatViewModelTest' :feature:chat:lintDebug
.\gradlew.bat @flags '-Proborazzi.test.verify=true' :app:testDebugUnitTest `
  --tests '*ShellNavigationTest' --tests '*ShellTopBarTest' `
  --tests '*ShellBackNavigationTest' --tests '*AppModelPickerHostTest' `
  --tests '*ExternalIntent*' --tests '*ColdStartDeepLinkTest' `
  --tests 'dev.chatui.screenshots.ShellBaselineScreenshotTest.standalone*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.onboarding*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.modelPicker*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.provider*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.conversationSettings*' `
  :app:lintDebug :app:assembleDebug
```

### UX-04 visual and preservation evidence

`Screenshots\ScreenshotTest.kt` verifies **33 affected cases / 66 light-dark
goldens** under `android\app\src\test\screenshots`: 40 narrowly updated existing
images and 26 new images. Reviewed scenes include phone/tablet picker filters/
empty/management, truthful provider list/detail, add/custom/key editor,
conversation essentials/generation/Advanced, onboarding welcome/invalid key/
connecting/catalog, and optional/real backup setup. Narrow 320 dp/200% scenes
and a short connecting-landscape scene extend the existing suite.

Modal captures select their actual modal root. Large-font cases set and restore
Robolectric's real font-scale configuration, not just an outer density local
that a modal may replace. Static interaction fixtures settle the test clock
and omit transient ripple/state layers; production feedback and normal
functional tests are unchanged. This corrects observed initiating-control
pixel timing differences without loosening image comparison tolerances.
Only affected scenes were recorded and reviewed; no historical UX-00 artifact
or unrelated golden suite was re-recorded.

These are standalone widget goldens plus focused production route/host tests,
not UX-06.1's full integrated-shell visual acceptance. Two existing standalone
picker candidate cases also pass in both densities/themes without recording
external candidate artifacts. Keyboard tests reserve a simulated inset;
neither they nor static screenshots render or validate an actual IME.

Regressions cover management nonselection/hidden recovery, exact immediate
settings/default resets, raw known/unknown routing-field interleaving, failed
write retry and invalid-draft retention, secure key rotation/removal, optional
backup's no-service-call choice, actual setup enable/failure/cancel, and
pending share/deep-link completion without draft replacement or auto-send.
The existing first-run service remains OpenRouter-only despite its broader
provider-option list; the UI explains that limitation. Other provider setup
remains in Providers. All five backup backends and existing security/storage
semantics are preserved. No UX-05 implementation was included in that earlier
slice; the subsequent implementation is recorded below.

### UX-04 acceptance blockers and next gate

The final `adb devices -l` check reports no connected device. All nine task
files and index rows are therefore **Blocked**, with implementation/automated
criteria recorded separately from required acceptance. **Owner: Android
maintainer.** Close inherited UX-02.4/UX-02.3, then record actual IME,
320 dp/200% and short-landscape reachability, both densities, layered Back,
management/settings/key-editor return focus, onboarding/backup cancellation
and pending-intent completion in the owning handoffs.

Only after those results are recorded may the dependent statuses become
Complete and later gates become eligible. Explicit implementation requests do
not waive missing device acceptance. UX-05.6 now reuses the same
`BackupSetupRoute(service, onCancel, onComplete, modifier)` without declaring
its prerequisites accepted. UX-00.3 remains owner-deferred, and no physical performance,
TalkBack, real-IME or UX-06 release acceptance is claimed here.

## UX-05/06 verification record

Implementation/evidence updated **2026-11-17**. All thirteen requested task
files are **Blocked**, not unstarted or accepted. Their bounded implementation
is present; earlier prerequisites, the red integrated gate and required
device/profile/performance/usability evidence remain explicit. **Owner:
Android maintainer**, with performance/usability reviewers for physical work.
See [UX-06.6](UX-06.6-parity-and-closeout.md) for all 54 preservation IDs and
thirteen source definition-of-done mappings.

### Implemented scope and focused results

Settings now has six concise entries, wrapping Appearance choices and a single
preview. Chat defaults uses the app-owned Defaults picker, separate provider
default reset and grouped Generation/Composer/Privacy with summaries,
validation and atomic immediate updates. Persisted defaults affect future
chats, never existing conversations/historical snapshots. Save feedback is
quiet, mutation-driven and cannot hide an unrelated failed write.

Data & storage prioritizes ordinary actions and uses one truthful operation/
error owner from consent and preparation through picker, actual IO/service
completion, cancellation and failure. Duplicate/concurrent actions are
guarded; export preparation and import read/validation use IO. Existing
plaintext warning, secret exclusion, separate import replace, key confirmation
and exact clear phrase remain.

Backup reuses one ViewModel/form for five-backend Configuration, optional
onboarding and status-first Overview. Editing/testing does not save/enable;
Done requires completed initial backup. Advanced replace/delete retain exact
phrases and safe Cancel focus. Re-entering the passphrase unlocks committed
configuration only in memory, without persisting form edits or rescheduling.
Toolbar Up respects feature Back to return Configuration to Overview.
Provider/storage authentication copy no longer confuses credentials with the
memory-only encryption passphrase.

The scoped app verification passed **65 tests**, including all 45
`ShellBaselineScreenshotTest` methods, seven `SettingsRouteTest` cases,
two cold-start cases and eleven affected standalone methods (22 PNGs).
The final full run also passes these relevant cases. Full settings (40),
backup (51), onboarding (45), conversations (108) and core-backup (123) suites
pass; their counts are included in, not added to, the integrated table below.

Focused source coverage includes actual Defaults selection/cancel/reopen/
focus and future-chat creation, live appearance application, nested backup Up
without committing edits, actual file/service callbacks using a fake document
registry, missing picker retry, actual completion versus launch, and persistent
write/validation failures. Backup tests cover all form variants, commit versus
test/edit, initial failure/retry/Done, re-entered memory-only passphrase and
destructive safeguards. No test requires real credentials or paid requests.

### Visual evidence

There are **160 new tracked actual-shell PNGs**: 40 scenarios in comfortable/
compact and light/dark, under
`android\app\src\test\screenshots\integrated_*_<density>_<theme>.png`.
They exercise the production shell/graph and real facades against fixture
Room/settings/files with fake external provider/storage/crypto boundaries.
No alternate screenshot shell or shipping seed entry was added.

The matrix includes prior chat/history states plus real Settings, Data,
backup configuration/overview/error/recovery, Defaults details/manual ID,
provider detail/management/credentials, actual Extras editor, onboarding and
optional secure setup. Narrow 320 dp/200%, 411 dp/150%, 640 dp landscape,
839/840/1280 dp and simulated keyboard-space cases extend the matrix.
Foundation/standalone picker cases remain separately labeled.

All variants were reviewed, including final changed-image contact sheets and
full-size sensitive/advanced cases. Twenty-two affected existing widget PNGs
were selectively updated (root/Appearance/defaults/About/data phone/tablet
and onboarding backup setup phone). No legacy UX-00 artifact or unrelated
chat/history golden was overwritten. Golden tolerances were not loosened.
Catalog labels and decoded image pixels settle before capture; local images
use a fixed scroll anchor and controlled stream captures omit cursor blink.
The new route test waits for enabled Configuration after asynchronous load,
not merely an already-visible disabled button. These are fixture timing
corrections, not production animations/controls being disabled.

### Final integrated gate

The required integrated command was executed with `--continue`, followed by
bounded fixture corrections and a final complete invocation. **1,250 tests:
1,228 pass, 22 fail, zero skips.** The command exits 1; it is not a clean
repository/Roborazzi acceptance pass.

| Module | Tests | Failures | Lint errors / warnings |
| --- | --- | --- | --- |
| app | 288 | 19 | 0 / 16 |
| core backup | 123 | 0 | 0 / 0 |
| core database | 137 | 3 | 0 / 4 |
| core datastore | 28 | 0 | 0 / 0 |
| core design system | 51 | 0 | 0 / 5 |
| core providers | 42 | 0 | 0 / 0 |
| feature backup | 51 | 0 | 0 / 0 |
| feature chat | 170 | 0 | 0 / 3 |
| feature conversations | 108 | 0 | 0 / 3 |
| feature onboarding | 45 | 0 | 0 / 1 |
| feature providers | 167 | 0 | 0 / 2 |
| feature settings | 40 | 0 | 0 / 2 |

Failure investigation/disposition:

- **18 older standalone `ScreenshotTest` methods:** chat Empty/Populated/
  Streaming/Error phone+tablet; conversation List/Archived/Search/Empty
  phone+tablet; expanded shell phone+tablet. These stop at their light
  comparison, so their dark comparisons are not claimed checked. The
  expectations/source scenes are outside this batch and were not broadly
  re-recorded to hide prior visual differences.
- **`ShellTransitionsTest`:** still clicks removed `shell-nav-backup`.
  Unchanged production `AdaptiveShell` has Providers/Settings footer;
  real Settings/Data > backup routes are separately exercised. The stale test
  and that footer source were not changed in this batch.
- **Three `core.database.MigrationTest` cases:** on Windows, the test driver
  is configured for a relative database name while Robolectric requests an
  absolute temporary path. Database source/tests are untouched.

Android maintainer owns these remaining integrated-gate issues. Earlier
iterations also exposed image-readiness/cursor/scroll composition races and a
click on disabled Configuration; bounded fixture fixes above pass the final
run without relaxed assertions. One earlier full run had a cold-start
missing-chat timeout; both cold-start cases pass the focused and final runs.
That observation is retained, not presented as a verified device fix.

Full `lintDebug` has **zero errors / 36 warnings** with existing checks enabled.
Debug, nonMinifiedRelease and debug instrumentation APK assembly pass.
`:baselineprofile:compileNonMinifiedReleaseKotlin` passes; generation was
explicitly skipped for these local build checks. The configured
`:app:generateBaselineProfile` workflow uses connected **nonMinifiedRelease**,
not BenchmarkRelease. Its resolved generated-source destination is
`android\app\src\release\generated\baselineProfiles`; no profile was generated.
See [UX-06.3](UX-06.3-baseline-profile.md) for required acknowledgements,
guarded seed/update/verify recipe and the unverified device-installation gate.

### Reproduction and local build identity

From the repository root; the selected recording scopes were executed
separately before verification, never with the whole legacy screenshot suite:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
Set-Location .\android
$flags = @(
  '--console=plain', '--quiet', '--no-daemon',
  '-Pkotlin.compiler.execution.strategy=in-process',
  '-Pandroid.baselineProfile.skipGeneration=true'
)
# Relevant app verification: 65 actual tests, not a zero-test task.
.\gradlew.bat @flags '-Proborazzi.test.verify=true' :app:testDebugUnitTest `
  --tests '*ShellBaselineScreenshotTest' --tests '*SettingsRouteTest' `
  --tests '*ColdStartDeepLinkTest' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.settingsRoot*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.settingsAppearance*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.settingsChatDefaults*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.settingsAbout*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.dataSettings*' `
  --tests 'dev.chatui.screenshots.ScreenshotTest.onboardingBackupSetupPhone' `
  :app:lintDebug :app:assembleDebugAndroidTest
# Final full gate: fails only the explicitly recorded older cases above.
.\gradlew.bat @flags --continue testDebugUnitTest lintDebug `
  :app:assembleDebug :app:verifyRoborazziDebug `
  :app:assembleNonMinifiedRelease :app:assembleDebugAndroidTest `
  :baselineprofile:compileNonMinifiedReleaseKotlin
```

Only the selected affected image scopes above and the actual-shell class were
recorded using Roborazzi's record mode; verify mode was a separate run.
Reports remain module-local under `build\test-results\testDebugUnitTest` and
`build\reports\lint-results-debug.*`; app comparisons are under
`android\app\build`. Selected reruns replace that module's XML rather than
forming an additive total.

Current local candidate artifacts are under
`android\app\build\outputs\apk` (ignored, not published or device-verified):

| Relative APK | SHA-256 |
| --- | --- |
| `debug\app-debug.apk` | `189E457139734DFE8893FE7A43948D31CA6FFC4CD8EEE7FAC4EBF52D1AEAFFC9` |
| `nonMinifiedRelease\app-nonMinifiedRelease.apk` | `31C604A88F1689D0F5E592A3F1AA13BCBE659AE4CBBBECAF4382A13C1DE0D8B1` |
| `androidTest\debug\app-debug-androidTest.apk` | `6974A67C1047AC378C33262BA52984CDE2B998637662A09BB0F4D10B8E4BA838` |

These are local debug-signed identities, not release signing/provisioning or
preserved physical evidence. App minSdk 26 and producer minSdk 28 are unchanged;
no dependency version/SDK upgrade, database migration or new UI module was made.

### Acceptance blockers

Final `adb devices -l` has no connected device. There is no actual TalkBack,
hardware keyboard, IME/SAF/navigation-mode/lifecycle/contrast matrix; no
generated/reviewed profile or verified seed-preserving target installation;
no physical entry-level/mid-tier old/new startup/frame/PSS evidence; and no
anonymous walkthrough. JVM screenshots/semantics and compiled test APKs are
not substitutes. UX-06.2 through UX-06.5 record exact missing configurations,
budgets, procedures and owners; UX-06.6 leaves rollout/closeout blocked.
Earlier UX-02/03/04 gates and owner-deferred UX-00.3 remain unchanged.

## Execution lanes and shared-file coordination

UX-00 establishes the baseline; UX-01.5 closes the shared foundation.
UX-02.1 through UX-02.4 establish navigation and picker integration.
UX-03 may start after UX-01.5 without waiting for navigation, as the source
plan permits. UX-04 and UX-05 start after UX-02.4; their explicit cross-links
cover shared picker, provider, and onboarding contracts.

After prerequisites, history/shell, transcript rendering, provider forms, and
settings are useful separate lanes, not a promise that every task is
parallel-safe. Serialize tasks touching the same file:

| Shared surface | Tasks requiring coordination |
| --- | --- |
| `Shell\ChatUiApp.kt`, `Shell\ShellNavHost.kt` | UX-01.4, UX-02.2 through UX-02.4, UX-04.2, UX-04.6, UX-05.1, UX-05.2 |
| `Chat\ChatScreen.kt`, `Chat\ChatRoute.kt` | UX-02.3, UX-02.4, UX-03.3, UX-03.6 |
| `Chat\ChatMessageItem.kt`, `Chat\ChatMessageList.kt` | UX-03.1, UX-03.2, UX-03.4 |
| `Providers\ProviderListScreen.kt`, `Providers\ProviderRoutes.kt` | UX-04.2 through UX-04.5 |
| `SettingsUI\SettingsScreens.kt`, `SettingsUI\SettingsRoute.kt` | UX-05.1 through UX-05.5 |
| `BackupUI\BackupSettingsScreen.kt` and onboarding call sites | UX-04.9, UX-05.6, UX-05.7 |
| Shared screenshot fixtures/goldens | UX-00.2, visual implementation tasks, UX-06.1 |

UX-06.1 waits for every screen-lane endpoint, including independent resource
tasks. Accessibility and profile work can then proceed separately; device
usability and performance converge at UX-06.6. A blocked measurement cannot be
silently replaced by an emulator screenshot or an old milestone's pass.
UX-00.3's explicitly approved AVD-baseline exception does not replace UX-06.4's
physical-device evidence.

## Plan coverage and preservation ownership

| Source-plan requirement | Primary implementation owners | Integrated evidence |
| --- | --- | --- |
| Sections 2, 6, 9: reachable baseline and preservation ledger | UX-00.1, UX-00.2 | UX-06.5, UX-06.6 |
| Section 3: semantic visuals, compact density, applied preferences | UX-01.1 through UX-01.5, UX-05.1 | UX-06.1, UX-06.2 |
| Sections 4, 5.3: useful Chats root, single history CTA, placement and platform navigation | UX-02.1 through UX-02.4 | UX-06.1, UX-06.5 |
| Sections 5.1, 5.2: messaging/content/input preservation | UX-03.1 through UX-03.7 | UX-06.2, UX-06.4, UX-06.5 |
| Section 5.4: picker, defaults versus conversation state, generation, JSON/routing | UX-02.4, UX-04.1, UX-04.2, UX-04.6, UX-04.7, UX-05.2, UX-05.3 | UX-06.1, UX-06.5 |
| Section 5.5: provider setup/status/keys/diagnostics | UX-04.3 through UX-04.5 | UX-06.2, UX-06.5 |
| Section 5.6: onboarding, optional backup, pending intents | UX-04.8, UX-04.9 | UX-06.5 |
| Section 5.7: settings/privacy, data, all backup backends and recovery | UX-05.1 through UX-05.7 | UX-06.1, UX-06.5 |
| Section 7: basic-phone resources and proposed budgets | UX-00.3, UX-01.5, UX-03.4 through UX-03.7, UX-06.3 | UX-06.4 |
| Section 8: accessibility/state/back/inset safeguards | Every affected implementation task | UX-06.2 |
| Section 10: actual-shell captures, pairwise device coverage and usability | UX-06.1 through UX-06.5 | UX-06.6 |

All nine capability families in section 6 must have before/after evidence;
UX-00.1 expands this ownership map into individual reachable actions.

## Selecting and tracking a task

The existing selector reads the task files directly from this folder.
From the repository root:

```powershell
python .\scripts\next-task .\docs\operations\android-minimalist-design-plan
python .\scripts\next-task .\docs\operations\android-minimalist-design-plan --list
python .\scripts\task-status get .\docs\operations\android-minimalist-design-plan\UX-00.1-functional-inventory.md
```

After changing a task status, update the central index explicitly:

```powershell
python .\scripts\task-status sync-index .\docs\operations\android-minimalist-design-plan .\docs\operations\android-minimalist-design-plan\UX-00.1-functional-inventory.md
```

The program is complete only after UX-06.6 accepts the evidence, every task is
`Complete`, and the source plan's definition of done is satisfied. Creating
these documents does not complete any redesign, device check, or measurement.
