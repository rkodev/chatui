#!/usr/bin/env python3
"""Run repository tasks sequentially in fresh GitHub Copilot CLI sessions."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path


def nonnegative_integer(value: str) -> int:
    if not value.isascii() or not value.isdigit():
        raise argparse.ArgumentTypeError("must be a non-negative integer")
    return int(value)


def model_name(value: str) -> str:
    if not value.strip():
        raise argparse.ArgumentTypeError("model must not be empty")
    return value.strip()


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "task_dir",
        nargs="?",
        default=str(Path("docs") / "operations" / "M0"),
        help="task folder, relative to the Git root (default: %(default)s)",
    )
    parser.add_argument(
        "--model",
        type=model_name,
        default=os.environ.get("COPILOT_MODEL", "gpt-6.1-sol"),
        help="Copilot model (default: COPILOT_MODEL or gpt-6.1-sol)",
    )
    parser.add_argument(
        "--max-tasks",
        type=nonnegative_integer,
        default=os.environ.get("MAX_TASKS", "0"),
        help="maximum attempts; 0 means unlimited (default: MAX_TASKS or 0)",
    )
    parser.add_argument(
        "--include-in-progress",
        action="store_true",
        default=os.environ.get("INCLUDE_IN_PROGRESS", "0") != "0",
        help="also resume eligible In progress tasks (or INCLUDE_IN_PROGRESS=1)",
    )
    parser.add_argument("--agent", help="optional installed Copilot custom agent")
    parser.add_argument(
        "--allow-all-tools",
        action="store_true",
        help="explicitly auto-approve tools, but not all paths or URLs",
    )
    parser.add_argument("--allow-tool", action="append", default=[], metavar="RULE")
    parser.add_argument("--deny-tool", action="append", default=[], metavar="RULE")
    parser.add_argument("--allow-url", action="append", default=[], metavar="URL")
    parser.add_argument(
        "--trust-folder",
        action="store_true",
        help="trust the checkout configuration for these Copilot runs",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the first eligible task's command as JSON without running it",
    )
    return parser.parse_args(argv)


def run_helper(
    repo_root: Path, name: str, *args: str
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(repo_root / "scripts" / name), *args],
        cwd=repo_root,
        capture_output=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        check=False,
    )


def copilot_command(
    args: argparse.Namespace, repo_root: Path, task_dir: Path, task: Path
) -> list[str]:
    instructions = repo_root / ".opencode" / "agent" / "task-runner.md"
    prompt = (
        f"Implement exactly one task: {task}. Read the task file, follow the "
        "task-runner instructions, and stop after that task is Complete or Blocked. "
        "Do not start another task.\n\n"
        f"Read and follow the Markdown workflow in {instructions}. Its OpenCode "
        "YAML frontmatter does not configure Copilot permissions; obey Copilot's "
        "actual permissions and never work around a denial.\n"
        f"The task index is {task_dir / 'README.md'}. Keep its row synchronized. "
        "Follow the workflow's scope, verification, handoff, and per-task commit "
        "rules. If required verification, permissions, or a decision are "
        "unavailable, record the evidence and mark the task Blocked, not Complete.\n"
        "On Windows, use PowerShell and Windows paths, and invoke extensionless "
        "Python helpers through Python. Do not assume Bash or sed is installed.\n"
        "Do not invoke any batch task runner, re-run the task selector, spawn "
        "subagents, change models, or start a second task."
    )
    command = ["copilot", "--model", args.model, "--no-ask-user"]
    if args.agent:
        command.extend(["--agent", args.agent])
    for flag in ("allow_all_tools", "trust_folder"):
        if getattr(args, flag):
            command.append("--" + flag.replace("_", "-"))
    for flag in ("allow_tool", "deny_tool", "allow_url"):
        for value in getattr(args, flag):
            command.extend(["--" + flag.replace("_", "-"), value])
    command.extend(["--prompt", prompt])
    return command


def run_tasks(args: argparse.Namespace, repo_root: Path) -> int:
    task_dir = (repo_root / args.task_dir).resolve()
    if not task_dir.is_dir():
        print(f"error: task folder does not exist: {task_dir}", file=sys.stderr)
        return 2

    selector_args = [str(task_dir)]
    if args.include_in_progress:
        selector_args.append("--include-in-progress")

    attempted = 0
    last_task = None
    while True:
        if args.max_tasks and attempted >= args.max_tasks:
            print(f"Reached maximum of {args.max_tasks} task(s).", file=sys.stderr)
            break

        selected = run_helper(repo_root, "next-task", *selector_args)
        if selected.returncode == 1:
            print("No eligible tasks remain.", file=sys.stderr)
            break
        if selected.returncode:
            print(selected.stderr.strip(), file=sys.stderr)
            return selected.returncode
        if not selected.stdout.strip():
            print("error: task selector returned an empty path", file=sys.stderr)
            return 2

        task = (repo_root / selected.stdout.strip()).resolve()
        if task == last_task:
            print(f"error: selector repeated a task without progress: {task}", file=sys.stderr)
            return 1
        last_task = task
        command = copilot_command(args, repo_root, task_dir, task)
        if args.dry_run:
            print(json.dumps(command))
            print("Dry run: no task executed or changed.", file=sys.stderr)
            return 0

        executable = shutil.which("copilot")
        if not executable:
            print("error: copilot was not found on PATH", file=sys.stderr)
            return 2
        command[0] = executable
        print(f"Running task {attempted + 1} with {args.model}: {task}", file=sys.stderr)
        result = subprocess.run(
            command, cwd=repo_root, stdin=subprocess.DEVNULL, check=False
        )
        attempted += 1
        if result.returncode:
            print(f"error: Copilot exited with code {result.returncode}", file=sys.stderr)
            return result.returncode

        status_result = run_helper(repo_root, "task-status", "get", str(task))
        if status_result.returncode:
            print(status_result.stderr.strip(), file=sys.stderr)
            return status_result.returncode
        status = status_result.stdout.strip()
        if status not in {"Complete", "Blocked"}:
            print(
                f"error: task did not reach Complete or Blocked: {task} (status: {status})",
                file=sys.stderr,
            )
            return 1
        synced = run_helper(
            repo_root, "task-status", "sync-index", str(task_dir), str(task)
        )
        if synced.returncode:
            print(synced.stderr.strip(), file=sys.stderr)
            return synced.returncode
        print(f"Task {status}: {task}", file=sys.stderr)

    print(f"run-copilot-tasks finished: {attempted} task(s) attempted.", file=sys.stderr)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        root = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True,
            encoding="utf-8",
            check=False,
        )
        if root.returncode:
            print(root.stderr.strip(), file=sys.stderr)
            return root.returncode
        return run_tasks(args, Path(root.stdout.strip()))
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("Interrupted; inspect the current task before resuming.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
