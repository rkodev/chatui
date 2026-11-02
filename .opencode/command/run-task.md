---
description: Execute exactly one task from a task folder or task file, then stop.
agent: task-runner
---

Run exactly one repository task.

Arguments: `$ARGUMENTS`

Rules:

- If `$ARGUMENTS` is empty, use `docs/operations/M0`.
- If `$ARGUMENTS` is a folder, run `scripts/next-task "$ARGUMENTS"` once and use the path it prints. Add `--include-in-progress` only if the user explicitly asks to resume an `In progress` task.
- If `$ARGUMENTS` is a task file, use that file directly.
- Do not select or start any other task.
- Stop after that one task is `Complete` or `Blocked`.
- For an unattended batch, the orchestrator supplies `--auto`; explicit deny rules still apply.
