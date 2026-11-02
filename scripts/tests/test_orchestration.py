#!/usr/bin/env python3
"""Deterministic tests for scripts/next-task and scripts/run-tasks."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
NEXT_TASK = REPO_ROOT / "scripts" / "next-task"
RUN_TASKS = REPO_ROOT / "scripts" / "run-tasks"
TASK_STATUS = REPO_ROOT / "scripts" / "task-status"

FAKE_OPENCODE = """#!{python}
import json
import os
import re
import sys
from pathlib import Path

log_path = Path(os.environ["FAKE_OPENCODE_LOG"])
with log_path.open("a", encoding="utf-8") as log:
    log.write(json.dumps(sys.argv[1:]) + "\\n")

if os.environ.get("FAKE_OPENCODE_COMPLETE") == "1":
    marker = "Implement exactly one task: "
    suffix = ". Read the task file"
    for argument in sys.argv[1:]:
        if marker not in argument:
            continue
        task_path = Path(argument.split(marker, 1)[1].split(suffix, 1)[0])
        text = task_path.read_text(encoding="utf-8")
        text = re.sub(
            r"(?m)^- Status:\\s*(?:Not started|In progress)\\s*$",
            "- Status: Complete",
            text,
        )
        text = re.sub(
            r"(?m)^\\*\\*Status:\\*\\*\\s*(?:Not started|In progress)\\s*$",
            "**Status:** Complete",
            text,
        )
        task_path.write_text(text, encoding="utf-8")
"""


def write_task(
    directory: Path,
    task_id: str,
    order: int,
    status: str = "Not started",
    dependencies: str = "none",
    bold: bool = False,
) -> Path:
    filename = task_id.replace(".", "_").lower() + ".md"
    path = directory / filename
    if bold:
        content = textwrap.dedent(
            f"""\
            # {task_id} - Test task

            **Status:** {status}
            **Order:** {order}
            **Depends on:** {dependencies}

            ## Outcome
            Selector test fixture.
            ## Acceptance criteria
            - [ ] done
            ## Verification
            - `true`
            """
        )
    else:
        content = textwrap.dedent(
            f"""\
            # {task_id} - Test task

            - Status: {status}
            - Parent task: test
            - Order: {order}
            - Depends on: {dependencies}

            ## Goal
            Selector test fixture.
            ## Acceptance criteria
            - [ ] done
            ## Verification
            - `true`
            """
        )
    path.write_text(content, encoding="utf-8")
    return path


def mark_status(path: Path, status: str) -> None:
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^- Status:\s*.*$", f"- Status: {status}", text)
    text = re.sub(r"(?m)^\*\*Status:\*\*\s*.*$", f"**Status:** {status}", text)
    path.write_text(text, encoding="utf-8")


def run_next_task(task_dir: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(NEXT_TASK), str(task_dir), *args],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def install_fake_opencode(bin_dir: Path) -> None:
    fake = bin_dir / "opencode"
    fake.write_text(FAKE_OPENCODE.format(python=sys.executable), encoding="utf-8")
    fake.chmod(0o755)


def run_tasks(
    task_dir: Path,
    extra_env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env.update(extra_env or {})
    return subprocess.run(
        [str(RUN_TASKS), str(task_dir)],
        cwd=REPO_ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


class NextTaskTests(unittest.TestCase):
    def test_picks_lowest_order_eligible_and_supports_bold_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            second = write_task(task_dir, "T-002", 2)
            first = write_task(task_dir, "T-001", 1, bold=True)

            result = run_next_task(task_dir)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), first.resolve())
            self.assertEqual(result.stderr, "")
            self.assertNotEqual(Path(result.stdout.strip()).resolve(), second.resolve())

    def test_waits_for_incomplete_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            first = write_task(task_dir, "T-001", 1)
            second = write_task(task_dir, "T-002", 2, dependencies="T-001")

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), first.resolve())

            mark_status(first, "Complete")
            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), second.resolve())

    def test_skips_blocked_tasks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            write_task(task_dir, "T-001", 1, status="Blocked")
            eligible = write_task(task_dir, "T-002", 2)

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), eligible.resolve())

    def test_resumes_in_progress_only_with_flag(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            in_progress = write_task(task_dir, "T-001", 1, status="In progress")
            not_started = write_task(task_dir, "T-002", 2)

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                Path(result.stdout.strip()).resolve(), not_started.resolve()
            )

            result = run_next_task(task_dir, "--include-in-progress")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                Path(result.stdout.strip()).resolve(), in_progress.resolve()
            )

    def test_exits_one_when_everything_is_complete(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            write_task(task_dir, "T-001", 1, status="Complete")
            write_task(task_dir, "T-002", 2, status="Complete")

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(result.stdout, "")

    def test_ignores_markdown_without_a_task_id(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            (task_dir / "README.md").write_text(
                "# Task index\n\n| Task | Status |\n| --- | --- |\n| T-001 | Not started |\n",
                encoding="utf-8",
            )
            (task_dir / "notes.md").write_text(
                "Status: Complete\nOrder: 0\nDepends on: none\n", encoding="utf-8"
            )
            task = write_task(task_dir, "T-001", 1)

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), task.resolve())

    def test_expands_dependency_ranges(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            first = write_task(task_dir, "T-001", 1, status="Complete")
            second = write_task(task_dir, "T-002", 2)
            third = write_task(task_dir, "T-003", 3, status="Complete")
            fourth = write_task(
                task_dir, "T-004", 4, dependencies="T-001\u2013T-003"
            )

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), second.resolve())

            mark_status(second, "Complete")
            listed = run_next_task(task_dir, "--list")
            self.assertEqual(listed.returncode, 0, listed.stderr)
            self.assertIn("T-001, T-002, T-003", listed.stdout)

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), fourth.resolve())
            self.assertTrue(first.exists() and third.exists())

    def test_expands_all_prior_tasks_prose(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            first = write_task(task_dir, "T-001", 1, status="Complete")
            second = write_task(task_dir, "T-002", 2)
            third = write_task(task_dir, "T-003", 3, dependencies="and all prior tasks")

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), second.resolve())

            mark_status(second, "Complete")
            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), third.resolve())
            self.assertTrue(first.exists())

    def test_exits_two_for_missing_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "missing"

            result = run_next_task(missing)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(result.stdout, "")
            self.assertIn("does not exist", result.stderr)

    def test_exits_two_for_malformed_task(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            (task_dir / "t-001.md").write_text(
                "# T-001 - Missing order\n\n- Status: Not started\n- Depends on: none\n",
                encoding="utf-8",
            )

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 2)
            self.assertIn("missing required field", result.stderr)


class RunTasksTests(unittest.TestCase):
    def test_progresses_tasks_and_places_auto_before_message(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            task_dir = root / "tasks"
            bin_dir = root / "bin"
            task_dir.mkdir()
            bin_dir.mkdir()
            first = write_task(task_dir, "T-001", 1)
            second = write_task(task_dir, "T-002", 2)
            log = root / "calls.jsonl"
            install_fake_opencode(bin_dir)

            result = run_tasks(
                task_dir,
                {
                    "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "FAKE_OPENCODE_LOG": str(log),
                    "FAKE_OPENCODE_COMPLETE": "1",
                    "MAX_TASKS": "2",
                },
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(len(calls), 2)
            messages = []
            for call in calls:
                self.assertIn("--auto", call)
                message_index = next(
                    index
                    for index, value in enumerate(call)
                    if value.startswith("Implement exactly one task: ")
                )
                self.assertLess(call.index("--auto"), message_index)
                messages.append(call[message_index])
            self.assertIn(str(first), messages[0])
            self.assertIn(str(second), messages[1])
            self.assertIn("Complete", first.read_text(encoding="utf-8"))
            self.assertIn("Complete", second.read_text(encoding="utf-8"))

    def test_auto_can_be_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            task_dir = root / "tasks"
            bin_dir = root / "bin"
            task_dir.mkdir()
            bin_dir.mkdir()
            write_task(task_dir, "T-001", 1)
            log = root / "calls.jsonl"
            install_fake_opencode(bin_dir)

            result = run_tasks(
                task_dir,
                {
                    "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "FAKE_OPENCODE_LOG": str(log),
                    "FAKE_OPENCODE_COMPLETE": "1",
                    "MAX_TASKS": "1",
                    "OPENCODE_AUTO": "0",
                },
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(len(calls), 1)
            self.assertNotIn("--auto", calls[0])
            self.assertTrue(
                any(value.startswith("Implement exactly one task: ") for value in calls[0])
            )

    def test_fails_when_task_is_not_finalized(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            task_dir = root / "tasks"
            bin_dir = root / "bin"
            task_dir.mkdir()
            bin_dir.mkdir()
            task = write_task(task_dir, "T-001", 1)
            log = root / "calls.jsonl"
            install_fake_opencode(bin_dir)

            result = run_tasks(
                task_dir,
                {
                    "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "FAKE_OPENCODE_LOG": str(log),
                    "FAKE_OPENCODE_COMPLETE": "0",
                    "MAX_TASKS": "0",
                },
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("did not reach Complete or Blocked", result.stderr)
            self.assertIn("Not started", task.read_text(encoding="utf-8"))
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(len(calls), 1)

    def test_syncs_index_when_task_is_complete(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            task_dir = root / "tasks"
            bin_dir = root / "bin"
            task_dir.mkdir()
            bin_dir.mkdir()
            task = write_task(task_dir, "T-001", 1)
            other = write_task(task_dir, "T-002", 2)
            readme = task_dir / "README.md"
            readme.write_text(
                textwrap.dedent(
                    """\
                    # Tasks

                    | Order | Task | Status | Depends on |
                    | --- | --- | --- | --- |
                    | 1 | [T-001](t-001.md) | Not started | none |
                    | 2 | [T-002](t-002.md) | Not started | none |
                    """
                ),
                encoding="utf-8",
            )
            log = root / "calls.jsonl"
            install_fake_opencode(bin_dir)

            result = run_tasks(
                task_dir,
                {
                    "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "FAKE_OPENCODE_LOG": str(log),
                    "FAKE_OPENCODE_COMPLETE": "1",
                    "MAX_TASKS": "1",
                },
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            index_text = readme.read_text(encoding="utf-8")
            self.assertIn("| [T-001](t-001.md) | Complete |", index_text)
            self.assertIn("| [T-002](t-002.md) | Not started |", index_text)
            self.assertIn("Complete", task.read_text(encoding="utf-8"))
            self.assertIn("Not started", other.read_text(encoding="utf-8"))

    def test_propagates_missing_task_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            missing = Path(tmp) / "missing"
            result = run_tasks(missing, {"MAX_TASKS": "1"})
            self.assertEqual(result.returncode, 2)
            self.assertIn("does not exist", result.stderr)

    def test_propagates_selector_bad_input(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            (task_dir / "t-001.md").write_text(
                "# T-001 - Missing order\n\n- Status: Not started\n- Depends on: none\n",
                encoding="utf-8",
            )

            result = run_tasks(task_dir, {"MAX_TASKS": "1"})
            self.assertEqual(result.returncode, 2)
            self.assertIn("missing required field", result.stderr)

    def test_can_include_in_progress_tasks(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            task_dir = root / "tasks"
            bin_dir = root / "bin"
            task_dir.mkdir()
            bin_dir.mkdir()
            in_progress = write_task(task_dir, "T-001", 1, status="In progress")
            write_task(task_dir, "T-002", 2)
            log = root / "calls.jsonl"
            install_fake_opencode(bin_dir)

            result = run_tasks(
                task_dir,
                {
                    "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "FAKE_OPENCODE_LOG": str(log),
                    "FAKE_OPENCODE_COMPLETE": "1",
                    "MAX_TASKS": "1",
                    "INCLUDE_IN_PROGRESS": "1",
                },
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual(len(calls), 1)
            self.assertIn(str(in_progress), calls[0][-1])


class TaskStatusTests(unittest.TestCase):
    def run_status(
        self, *args: str
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(TASK_STATUS), *args],
            cwd=REPO_ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_get_supports_bullet_and_bold_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            bullet = write_task(task_dir, "T-001", 1, status="In progress")
            bold = write_task(task_dir, "T-002", 2, status="Complete", bold=True)

            result = self.run_status("get", str(bullet))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "In progress")

            result = self.run_status("get", str(bold))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "Complete")

    def test_get_rejects_missing_or_unknown_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            missing = task_dir / "missing.md"
            missing.write_text("# T-001 - Missing\n", encoding="utf-8")
            unknown = write_task(task_dir, "T-002", 2, status="Waiting")

            self.assertEqual(self.run_status("get", str(missing)).returncode, 2)
            self.assertEqual(self.run_status("get", str(unknown)).returncode, 2)

    def test_sync_index_updates_only_the_matching_row(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            task = write_task(task_dir, "T-001", 1, status="Complete")
            write_task(task_dir, "T-002", 2)
            readme = task_dir / "README.md"
            readme.write_text(
                textwrap.dedent(
                    """\
                    # Tasks

                    | Order | Task | Status | Depends on |
                    | --- | --- | --- | --- |
                    | 1 | [T-001](t-001.md) | In progress | none |
                    | 2 | [T-002](t-002.md) | Not started | none |
                    """
                ),
                encoding="utf-8",
            )

            result = self.run_status("sync-index", str(task_dir), str(task))
            self.assertEqual(result.returncode, 0, result.stderr)
            index_text = readme.read_text(encoding="utf-8")
            self.assertIn("| [T-001](t-001.md) | Complete |", index_text)
            self.assertIn("| [T-002](t-002.md) | Not started |", index_text)

    def test_sync_index_errors_when_row_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            task = write_task(task_dir, "T-001", 1, status="Complete")
            (task_dir / "README.md").write_text(
                "# Tasks\n\n| Order | Task | Status | Depends on |\n",
                encoding="utf-8",
            )

            result = self.run_status("sync-index", str(task_dir), str(task))
            self.assertEqual(result.returncode, 2)
            self.assertIn("no index row links to", result.stderr)


if __name__ == "__main__":
    unittest.main()
