#!/usr/bin/env python3
"""Measure ChatUI desktop cold start on Linux/Wayland.

Method
------
Each run launches the packaged release binary with ``--smoke
--no-single-instance`` and a fresh ``XDG_CONFIG_HOME``, ``XDG_DATA_HOME``, and
``XDG_CACHE_HOME`` so no previous window state or single-instance lock is
reused. ``WAYLAND_DEBUG=1`` makes the Wayland client log protocol messages to
stderr.

The first Wayland protocol line marks the client's connection to the
compositor. The first ``wl_surface.attach(wl_buffer)`` followed by a
``wl_surface.commit()`` on the same surface marks the first frame submitted to
the compositor. Time to first frame is::

    (wall time at first protocol line - launch wall time)
    + (first frame timestamp - first protocol line timestamp)

The ``--smoke`` timer exits the app 1500 ms after the event loop starts, so the
total wall time is also recorded as a cross-check. The Wayland debug logging
adds overhead, so the first-frame value is an upper bound on the real cold
start.

This script is Linux/Wayland specific. macOS and Windows need their own
measurement method and runner; see ``docs/operations/M3.5/performance.md``.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

WAYLAND_LINE_RE = re.compile(r"\[(\d+\.\d+)\].*(?:wl_|xdg_)")
XDG_SURFACE_RE = re.compile(
    r"xdg_wm_base#\d+\.get_xdg_surface\(new id xdg_surface#\d+, wl_surface#(\d+)\)"
)
ATTACH_RE = re.compile(r"wl_surface#(\d+)\.attach\(wl_buffer")
COMMIT_RE = re.compile(r"wl_surface#(\d+)\.commit\(\)")


def run_once(binary: Path, timeout: float) -> dict[str, float]:
    workdir = Path(tempfile.mkdtemp(prefix="chatui-cold-start-"))
    env = dict(os.environ)
    env.update(
        {
            "XDG_CONFIG_HOME": str(workdir / "config"),
            "XDG_DATA_HOME": str(workdir / "data"),
            "XDG_CACHE_HOME": str(workdir / "cache"),
            "WAYLAND_DEBUG": "1",
        }
    )
    for name in ("config", "data", "cache"):
        (workdir / name).mkdir(parents=True, exist_ok=True)

    launch = time.monotonic()
    proc = subprocess.Popen(
        [str(binary), "--smoke", "--no-single-instance"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        env=env,
        text=True,
        bufsize=1,
    )

    state: dict[str, object] = {
        "first_line_wall": None,
        "first_line_ts": None,
        "first_frame_ts": None,
        "main_surface": None,
        "main_attached": False,
    }

    def reader() -> None:
        assert proc.stderr is not None
        for line in proc.stderr:
            match = WAYLAND_LINE_RE.search(line)
            if not match:
                continue
            timestamp = float(match.group(1))
            if state["first_line_wall"] is None:
                state["first_line_wall"] = time.monotonic()
                state["first_line_ts"] = timestamp
            xdg_surface = XDG_SURFACE_RE.search(line)
            if xdg_surface:
                state["main_surface"] = xdg_surface.group(1)
                continue
            attach = ATTACH_RE.search(line)
            if attach:
                if attach.group(1) == state["main_surface"]:
                    state["main_attached"] = True
                continue
            commit = COMMIT_RE.search(line)
            if (
                commit
                and state["first_frame_ts"] is None
                and state["main_attached"]
                and commit.group(1) == state["main_surface"]
            ):
                state["first_frame_ts"] = timestamp

    thread = threading.Thread(target=reader, daemon=True)
    thread.start()
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        raise RuntimeError(f"binary did not exit within {timeout:.1f}s")
    finally:
        thread.join(timeout=5.0)
        shutil.rmtree(workdir, ignore_errors=True)
    total_ms = (time.monotonic() - launch) * 1000.0

    first_line_wall = state["first_line_wall"]
    first_line_ts = state["first_line_ts"]
    first_frame_ts = state["first_frame_ts"]
    if first_line_wall is None or first_line_ts is None or first_frame_ts is None:
        raise RuntimeError(
            "did not observe a Wayland first frame; is WAYLAND_DEBUG supported "
            "and is the app running on a Wayland compositor?"
        )

    first_line_ms = (first_line_wall - launch) * 1000.0
    frame_after_line_ms = first_frame_ts - first_line_ts
    return {
        "first_frame_ms": first_line_ms + frame_after_line_ms,
        "first_line_ms": first_line_ms,
        "frame_after_line_ms": frame_after_line_ms,
        "total_ms": total_ms,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--binary",
        default="rust/target/release/chatui-desktop",
        help="release binary to measure (default: %(default)s)",
    )
    parser.add_argument(
        "--runs",
        type=int,
        default=5,
        help="number of cold-start runs (default: %(default)s)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="per-run timeout in seconds (default: %(default)s)",
    )
    parser.add_argument(
        "--warmup",
        type=int,
        default=1,
        help="unmeasured warmup runs before the measured runs (default: %(default)s)",
    )
    args = parser.parse_args()

    binary = Path(args.binary)
    if not binary.is_file():
        print(f"error: binary not found: {binary}", file=sys.stderr)
        return 2
    if args.runs < 1:
        print("error: --runs must be at least 1", file=sys.stderr)
        return 2
    if args.warmup < 0:
        print("error: --warmup must be zero or greater", file=sys.stderr)
        return 2

    for index in range(1, args.warmup + 1):
        try:
            run_once(binary, args.timeout)
        except RuntimeError as error:
            print(f"warmup {index}: FAILED: {error}", file=sys.stderr)
            return 1
        print(f"warmup {index}: complete (not measured)")

    results = []
    for index in range(1, args.runs + 1):
        try:
            result = run_once(binary, args.timeout)
        except RuntimeError as error:
            print(f"run {index}: FAILED: {error}", file=sys.stderr)
            return 1
        results.append(result)
        print(
            f"run {index}: first_frame={result['first_frame_ms']:.1f} ms "
            f"(connect={result['first_line_ms']:.1f} ms, "
            f"frame={result['frame_after_line_ms']:.1f} ms), "
            f"total={result['total_ms']:.1f} ms"
        )

    first_frames = [result["first_frame_ms"] for result in results]
    totals = [result["total_ms"] for result in results]
    print()
    print(f"runs: {len(results)}")
    print(
        "first frame: "
        f"median={statistics.median(first_frames):.1f} ms, "
        f"min={min(first_frames):.1f} ms, "
        f"max={max(first_frames):.1f} ms"
    )
    print(
        "total wall: "
        f"median={statistics.median(totals):.1f} ms, "
        f"min={min(totals):.1f} ms, "
        f"max={max(totals):.1f} ms"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
