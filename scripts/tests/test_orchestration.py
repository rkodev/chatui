#!/usr/bin/env python3
"""Deterministic tests for task selection, status, and CLI orchestration."""

from __future__ import annotations

import io
import json
import os
import re
import runpy
import subprocess
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
NEXT_TASK = REPO_ROOT / "scripts" / "next-task"
RUN_TASKS = REPO_ROOT / "scripts" / "run-tasks"
RUN_COPILOT_TASKS = REPO_ROOT / "scripts" / "run-copilot-tasks.py"
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

    def test_supports_dotted_milestone_task_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            task_dir = Path(tmp)
            first = write_task(task_dir, "M3.5-00.1", 1)
            second = write_task(task_dir, "M3.5-00.2", 2, dependencies="M3.5-00.1")

            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), first.resolve())

            mark_status(first, "Complete")
            result = run_next_task(task_dir)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(Path(result.stdout.strip()).resolve(), second.resolve())

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


class CopilotRunTasksTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.task_dir = Path(temporary.name) / "tasks with spaces \u6d4b\u8bd5"
        self.task_dir.mkdir()
        self.task_dir = self.task_dir.resolve()
        self.main = runpy.run_path(str(RUN_COPILOT_TASKS))["main"]
        self.real_run = subprocess.run
        self.executable = str(REPO_ROOT / "fake copilot.exe")
        self.calls: list[tuple[list[str], dict]] = []
        self.outcomes: list[str] = []
        self.exit_code = 0
        self.repeat_task: Path | None = None

    def fake_run(self, command: list[str], **kwargs) -> subprocess.CompletedProcess:
        if command[0] == sys.executable:
            self.assertIn(Path(command[1]), (NEXT_TASK, TASK_STATUS))
            if Path(command[1]) == NEXT_TASK and self.repeat_task:
                return subprocess.CompletedProcess(command, 0, f"{self.repeat_task}\n", "")
            return self.real_run(command, **kwargs)
        if command == ["git", "rev-parse", "--show-toplevel"]:
            return self.real_run(command, **kwargs)

        self.assertEqual(command[0], self.executable)
        self.calls.append((command, kwargs))
        if not self.exit_code:
            prompt = command[command.index("--prompt") + 1]
            task = Path(
                prompt.split("Implement exactly one task: ", 1)[1]
                .split(". Read the task file", 1)[0]
            )
            status = self.outcomes.pop(0) if self.outcomes else "Complete"
            mark_status(task, status)
        return subprocess.CompletedProcess(command, self.exit_code)

    def invoke(
        self, *args: str, env: dict[str, str] | None = None, missing_cli: bool = False
    ) -> tuple[int, str, str]:
        environment = os.environ.copy()
        for name in ("COPILOT_MODEL", "MAX_TASKS", "INCLUDE_IN_PROGRESS"):
            environment.pop(name, None)
        environment.update(env or {})
        stdout, stderr = io.StringIO(), io.StringIO()
        with (
            mock.patch.dict(os.environ, environment, clear=True),
            mock.patch("shutil.which", return_value=None if missing_cli else self.executable),
            mock.patch("subprocess.run", side_effect=self.fake_run),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            try:
                code = self.main([str(self.task_dir), *args])
            except SystemExit as exc:
                code = exc.code
        return code, stdout.getvalue(), stderr.getvalue()

    def write_index(self, *tasks: Path) -> Path:
        readme = self.task_dir / "README.md"
        rows = [
            "# Tasks\n",
            "| Order | Task | Status | Depends on |",
            "| --- | --- | --- | --- |",
        ]
        rows.extend(
            f"| {index} | [{task.stem}]({task.name}) | Not started | none |"
            for index, task in enumerate(tasks, 1)
        )
        readme.write_text("\n".join(rows) + "\n", encoding="utf-8")
        return readme

    def test_runs_fresh_sessions_in_dependency_order_and_syncs_index(self) -> None:
        first = write_task(self.task_dir, "UX-00.1", 1)
        second = write_task(self.task_dir, "UX-00.2", 2, dependencies="UX-00.1", bold=True)
        index = self.write_index(first, second)

        code, _, stderr = self.invoke()

        self.assertEqual(code, 0, stderr)
        self.assertEqual(len(self.calls), 2)
        for (command, kwargs), task in zip(self.calls, (first, second)):
            self.assertEqual(command[command.index("--model") + 1], "gpt-6.1-sol")
            self.assertEqual(Path(kwargs["cwd"]), REPO_ROOT)
            self.assertIn(str(task), command[command.index("--prompt") + 1])
            for flag in ("--agent", "--allow-all-tools", "--trust-folder", "--continue"):
                self.assertNotIn(flag, command)
            self.assertIn(f"]({task.name}) | Complete |", index.read_text(encoding="utf-8"))

    def test_model_override_precedence(self) -> None:
        task = write_task(self.task_dir, "T-001", 1)
        for args, environment, expected in (
            ([], {"COPILOT_MODEL": "gpt-6.1-terra"}, "gpt-6.1-terra"),
            (["--model", "gpt-6.1-sol"], {"COPILOT_MODEL": "gpt-6.1-terra"}, "gpt-6.1-sol"),
        ):
            with self.subTest(args=args):
                mark_status(task, "Not started")
                code, _, stderr = self.invoke(*args, env=environment)
                self.assertEqual(code, 0, stderr)
                command = self.calls[-1][0]
                self.assertEqual(command[command.index("--model") + 1], expected)

    def test_limits_attempts_and_explicitly_resumes_in_progress(self) -> None:
        first = write_task(self.task_dir, "T-001", 1, status="In progress")
        second = write_task(self.task_dir, "T-002", 2)
        for args, environment, expected in (
            (["--max-tasks", "1"], {}, second),
            (["--max-tasks", "1", "--include-in-progress"], {}, first),
            ([], {"MAX_TASKS": "1", "INCLUDE_IN_PROGRESS": "1"}, first),
        ):
            with self.subTest(args=args, environment=environment):
                mark_status(first, "In progress")
                mark_status(second, "Not started")
                self.calls.clear()
                code, _, stderr = self.invoke(*args, env=environment)
                self.assertEqual(code, 0, stderr)
                self.assertEqual(len(self.calls), 1)
                self.assertIn(str(expected), self.calls[0][0][-1])

    def test_syncs_blocked_tasks_and_continues_only_independent_work(self) -> None:
        first = write_task(self.task_dir, "T-001", 1)
        dependent = write_task(self.task_dir, "T-002", 2, dependencies="T-001")
        independent = write_task(self.task_dir, "T-003", 3)
        index = self.write_index(first, dependent, independent)
        self.outcomes = ["Blocked", "Complete"]

        code, _, stderr = self.invoke()

        self.assertEqual(code, 0, stderr)
        self.assertEqual(len(self.calls), 2)
        self.assertIn(str(independent), self.calls[1][0][-1])
        content = index.read_text(encoding="utf-8")
        self.assertIn(f"]({first.name}) | Blocked |", content)
        self.assertIn(f"]({dependent.name}) | Not started |", content)
        self.assertIn(f"]({independent.name}) | Complete |", content)

    def test_stops_on_copilot_failure_or_unfinished_task(self) -> None:
        task = write_task(self.task_dir, "T-001", 1)
        write_task(self.task_dir, "T-002", 2)
        for exit_code, status, expected in (
            (7, "Not started", 7),
            (0, "Not started", 1),
            (0, "In progress", 1),
        ):
            with self.subTest(exit_code=exit_code, status=status):
                mark_status(task, "Not started")
                self.calls.clear()
                self.exit_code = exit_code
                self.outcomes = [status]
                code, _, _ = self.invoke()
                self.assertEqual(code, expected)
                self.assertEqual(len(self.calls), 1)

    def test_dry_run_forwards_explicit_permissions_without_changing_tasks(self) -> None:
        task = write_task(self.task_dir, "T-001", 1)
        index = self.write_index(task)
        before = (task.read_bytes(), index.read_bytes())

        code, stdout, stderr = self.invoke(
            "--dry-run",
            "--agent", "custom-copilot-agent",
            "--allow-all-tools",
            "--trust-folder",
            "--allow-tool", "shell(git status:*)",
            "--allow-tool", "write",
            "--deny-tool", "shell(git push:*)",
            "--allow-url", "https://example.com",
            missing_cli=True,
        )

        self.assertEqual(code, 0, stderr)
        self.assertFalse(self.calls)
        self.assertEqual((task.read_bytes(), index.read_bytes()), before)
        command = json.loads(stdout)
        self.assertEqual(command.count("--allow-tool"), 2)
        self.assertIn("shell(git status:*)", command)
        self.assertIn("shell(git push:*)", command)
        self.assertIn("https://example.com", command)
        self.assertIn("custom-copilot-agent", command)
        self.assertIn("--allow-all-tools", command)
        self.assertIn("--trust-folder", command)

    def test_rejects_bad_configuration_and_selector_errors_before_copilot(self) -> None:
        task = write_task(self.task_dir, "T-001", 1)
        for args, environment in (
            (["--max-tasks", "-1"], {}),
            ([], {"MAX_TASKS": "invalid"}),
            (["--model", ""], {}),
            (["--unknown-option"], {}),
        ):
            with self.subTest(args=args, environment=environment):
                code, _, _ = self.invoke(*args, env=environment)
                self.assertEqual(code, 2)
        task.write_text("# T-001\n\n- Status: Not started\n- Depends on: none\n", encoding="utf-8")
        code, _, stderr = self.invoke()
        self.assertEqual(code, 2, stderr)
        self.assertIn("missing required field", stderr)
        self.task_dir = self.task_dir / "missing"
        code, _, stderr = self.invoke()
        self.assertEqual(code, 2, stderr)
        self.assertIn("does not exist", stderr)
        self.assertFalse(self.calls)

    def test_missing_copilot_is_only_an_error_when_a_task_needs_running(self) -> None:
        task = write_task(self.task_dir, "T-001", 1)
        code, _, stderr = self.invoke(missing_cli=True)
        self.assertEqual(code, 2)
        self.assertIn("not found on PATH", stderr)

        mark_status(task, "Complete")
        code, _, stderr = self.invoke(missing_cli=True)
        self.assertEqual(code, 0, stderr)
        self.assertFalse(self.calls)

    def test_stops_if_index_sync_fails(self) -> None:
        write_task(self.task_dir, "T-001", 1)
        write_task(self.task_dir, "T-002", 2)
        (self.task_dir / "README.md").write_text("# Missing task rows\n", encoding="utf-8")

        code, _, stderr = self.invoke()

        self.assertEqual(code, 2)
        self.assertIn("no index row links to", stderr)
        self.assertEqual(len(self.calls), 1)

    def test_rejects_repeated_selection_without_starting_another_session(self) -> None:
        self.repeat_task = write_task(self.task_dir, "T-001", 1)

        code, _, stderr = self.invoke()

        self.assertEqual(code, 1)
        self.assertIn("without progress", stderr)
        self.assertEqual(len(self.calls), 1)


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
