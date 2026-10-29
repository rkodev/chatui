# Task records

The task-by-task implementation breakdown has been superseded by the milestone documents in [`docs/milestones/`](../milestones/README.md). Milestones define scope, ownership boundaries, dependencies, exit gates, and verification; concrete tasks are generated from one milestone at a time.

Only the completed repository bootstrap record remains here:

| ID | Record | Status | Milestone |
| --- | --- | --- | --- |
| T000 | [Repository foundations and developer commands](T000-foundations.md) | Complete | M0 prerequisite |

New task files must be generated under `docs/tasks/<milestone-id>/`, for example `docs/tasks/M1/T110-web-openrouter.md`, and must follow the task-generation contract in [`docs/milestones/README.md`](../milestones/README.md). Do not add new task files at this directory's root.
