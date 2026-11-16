# Android minimalist redesign - implementation tasks

- Source plan: [Android minimalist design plan](../../android-minimalist-design-plan.md)
- Behavior authority: [IMPLEMENTATION.md](../../IMPLEMENTATION.md)
- Status: Not started
- Scope: Android presentation, navigation integration, accessibility, and resource efficiency. Preserve functionality.
- Breakdown: 41 tasks across the source plan's UX-00 through UX-06 phases.
- Implementation: Not started by creating this task pack.

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
| 1 | [UX-00.1 Functional inventory and decisions](UX-00.1-functional-inventory.md) | Not started | none |
| 2 | [UX-00.2 Fixtures and visual baseline](UX-00.2-fixtures-and-visual-baseline.md) | Not started | UX-00.1 |
| 3 | [UX-00.3 Reference-device performance baseline](UX-00.3-device-performance-baseline.md) | Not started | UX-00.2 |
| 4 | [UX-01.1 Semantic colors and contrast](UX-01.1-colors-and-contrast.md) | Not started | UX-00.3 |
| 5 | [UX-01.2 Density, spacing, and touch targets](UX-01.2-density-and-touch-targets.md) | Not started | UX-00.3 |
| 6 | [UX-01.3 Compact scaffold title bar](UX-01.3-compact-scaffold.md) | Not started | UX-01.1, UX-01.2 |
| 7 | [UX-01.4 Applied theme and feedback preferences](UX-01.4-theme-preferences.md) | Not started | UX-01.1 |
| 8 | [UX-01.5 Quiet state and motion primitives](UX-01.5-state-and-motion-primitives.md) | Not started | UX-01.3, UX-01.4 |
| 9 | [UX-02.1 Reusable history presentation](UX-02.1-history-presentation.md) | Not started | UX-01.5 |
| 10 | [UX-02.2 Real Chats root and adaptive history](UX-02.2-chats-root-and-drawer.md) | Not started | UX-02.1 |
| 11 | [UX-02.3 Toolbar overflow and hierarchical navigation](UX-02.3-toolbar-and-back.md) | Not started | UX-02.2 |
| 12 | [UX-02.4 App-owned model-picker host](UX-02.4-model-picker-host.md) | Not started | UX-02.3 |
| 13 | [UX-03.1 Content-first message geometry](UX-03.1-message-layout.md) | Not started | UX-01.5 |
| 14 | [UX-03.2 Message actions and metadata footer](UX-03.2-message-actions.md) | Not started | UX-03.1 |
| 15 | [UX-03.3 Responsive composer and single warning](UX-03.3-composer-and-warnings.md) | Not started | UX-01.5 |
| 16 | [UX-03.4 Latest-message control and streaming motion](UX-03.4-scrolling-and-streaming.md) | Not started | UX-03.1 |
| 17 | [UX-03.5 Bounded remote Markdown images](UX-03.5-remote-images.md) | Not started | UX-03.1 |
| 18 | [UX-03.6 Background attachment ingestion](UX-03.6-attachment-ingestion.md) | Not started | UX-03.3 |
| 19 | [UX-03.7 App-themed code and rendering reuse](UX-03.7-code-and-markdown-rendering.md) | Not started | UX-03.1 |
| 20 | [UX-04.1 Compact searchable model picker](UX-04.1-model-picker-presentation.md) | Not started | UX-02.4 |
| 21 | [UX-04.2 Reachable model management](UX-04.2-manage-models-entry.md) | Not started | UX-04.1 |
| 22 | [UX-04.3 Quiet provider list and truthful status](UX-04.3-provider-list.md) | Not started | UX-02.4 |
| 23 | [UX-04.4 Focused add-provider forms](UX-04.4-add-provider.md) | Not started | UX-04.3 |
| 24 | [UX-04.5 Provider detail and secure key editor](UX-04.5-provider-detail.md) | Not started | UX-04.2, UX-04.4 |
| 25 | [UX-04.6 Conversation essentials and generation](UX-04.6-conversation-settings.md) | Not started | UX-04.1 |
| 26 | [UX-04.7 Extras JSON and curated routing](UX-04.7-advanced-routing.md) | Not started | UX-04.6 |
| 27 | [UX-04.8 Focused onboarding connection](UX-04.8-onboarding-connection.md) | Not started | UX-02.4 |
| 28 | [UX-04.9 Optional backup onboarding](UX-04.9-onboarding-backup.md) | Not started | UX-04.8 |
| 29 | [UX-05.1 Settings root and appearance](UX-05.1-settings-and-appearance.md) | Not started | UX-02.4 |
| 30 | [UX-05.2 Global-default model-picker entry](UX-05.2-default-model-picker.md) | Not started | UX-05.1, UX-04.1 |
| 31 | [UX-05.3 Grouped chat-default controls](UX-05.3-chat-defaults.md) | Not started | UX-05.2 |
| 32 | [UX-05.4 Data actions and operation states](UX-05.4-data-and-storage.md) | Not started | UX-05.1 |
| 33 | [UX-05.5 Quiet autosave and truthful privacy copy](UX-05.5-settings-feedback-and-copy.md) | Not started | UX-05.3, UX-05.4 |
| 34 | [UX-05.6 Focused backup configuration](UX-05.6-backup-configuration.md) | Not started | UX-04.9, UX-05.1 |
| 35 | [UX-05.7 Backup overview and recovery safeguards](UX-05.7-backup-overview.md) | Not started | UX-05.6 |
| 36 | [UX-06.1 Integrated production-shell screenshots](UX-06.1-integrated-screenshots.md) | Not started | UX-02.4, UX-03.2, UX-03.4, UX-03.5, UX-03.6, UX-03.7, UX-04.5, UX-04.7, UX-04.9, UX-05.5, UX-05.7 |
| 37 | [UX-06.2 Accessibility, focus, and inset acceptance](UX-06.2-accessibility-and-insets.md) | Not started | UX-06.1 |
| 38 | [UX-06.3 Deterministic baseline-profile journey](UX-06.3-baseline-profile.md) | Not started | UX-06.1 |
| 39 | [UX-06.4 Entry-level performance acceptance](UX-06.4-performance-acceptance.md) | Not started | UX-06.3 |
| 40 | [UX-06.5 Device flows and usability walkthrough](UX-06.5-device-and-usability.md) | Not started | UX-06.2 |
| 41 | [UX-06.6 Parity and implementation closeout](UX-06.6-parity-and-closeout.md) | Not started | UX-06.1, UX-06.2, UX-06.3, UX-06.4, UX-06.5 |

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
