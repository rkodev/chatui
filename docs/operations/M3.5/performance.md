# Performance, cold start, and installer-size evidence

- Task: M3.5-11.5
- Milestone: M3.5
- Spec: [IMPLEMENTATION.md](../../IMPLEMENTATION.md) sections 10.9 and 11.9
- Plan: [M3.5 plan](../M3.5-rust-desktop-implementation-plan.md) sections 3.7 and 6
- Last updated: 2026-11-08

This page records the reproducible cold-start method, the measured machine, the
release artifact version, the Linux installer sizes, the observed build times,
and the comparison against the milestone budgets. Only release artifacts are
used for the claims. Debug builds are not measured.

## Results summary

| Budget | Target | Measured | Result |
| --- | --- | --- | --- |
| Cold start to first frame | under 1.5 s | median 417.1 ms, range 321.8-1051.1 ms (packaged `.deb`, 10 runs) | Pass |
| Installer size | under 40 MB | `.deb` 6.51 MiB (6.83 MB); `.tar.gz` 8.59 MiB (9.00 MB) | Pass |
| Installed size | not budgeted | 18.52 MiB (19.42 MB) for both Linux artifacts | Recorded |
| Build time | not budgeted | 5m 47s clean-ish release build (M3.5-11.1); 1.5 s incremental `make rust-build`; 12.1 s incremental `make rust-package` | Recorded |

No Linux budget miss was observed. macOS and Windows installer sizes and cold
starts were not measured because no macOS or Windows runner or machine is
available in this environment (blockers B-01 and B-02 in
[M3.5-verification.md](M3.5-verification.md)).

## Machine and OS

Measured on 2026-11-08 on the development host:

| Item | Value |
| --- | --- |
| OS | Ubuntu 26.04.1 LTS (Resolute Raccoon) |
| Kernel | Linux 7.0.0-42-generic x86_64 |
| Desktop session | GNOME on Wayland (`XDG_SESSION_TYPE=wayland`) |
| CPU | Intel Core i9-10980HK @ 2.40 GHz, 8 cores / 16 threads |
| Memory | 30 GiB |
| Disk | NVMe SSD (`/dev/nvme1n1p2`, rotational 0), ext4 |
| Toolchain | rustc 1.98.1 (48a229cea 2026-09-01), cargo 1.98.1 (797e8a9bc 2026-08-05) |
| Target | `x86_64-unknown-linux-gnu` |

The host is a mid-tier desktop-class laptop. It is not a controlled benchmark
machine; the median and range are reported instead of a single best run.

## Build type and artifact version

| Item | Value |
| --- | --- |
| Build type | Cargo `release` profile |
| Profile settings | `lto = "thin"`, `codegen-units = 1`, `strip = "symbols"`, `panic = "abort"` |
| App version | `0.1.0` (from `rust/apps/desktop/Cargo.toml`) |
| Source revision at measurement | `6e29be9` (M3.5-11.4) |
| Release binary | `rust/target/release/chatui-desktop`, 19,415,648 bytes, stripped |
| Linux `.deb` | `rust/dist/chatui_0.1.0_amd64.deb` |
| Linux `.tar.gz` | `rust/dist/ChatUI-0.1.0-linux-x86_64.tar.gz` |
| `.deb` SHA-256 | `558d1486d3e22cf4e50a11030b4b8c91a60ede981b768f1e42f33f28b59120f8` |
| `.tar.gz` SHA-256 | `c081367b40709991278f4563c06a0e11a9283c539110f5d154d7e8549cf0e0cf` |

The release profile and the retained debug-symbol artifact are documented in
[release-profile.md](release-profile.md). The packaging pipeline and checksums
are documented in [packaging.md](packaging.md).

## Cold-start method

The measurement script is
[`scripts/measure-cold-start.py`](../../../scripts/measure-cold-start.py). It
uses the packaged release binary, not a debug build.

Each measured run:

1. Creates a fresh temporary `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, and
   `XDG_CACHE_HOME`, so no previous window state, database, or single-instance
   lock is reused.
2. Launches the binary with `--smoke --no-single-instance`.
3. Sets `WAYLAND_DEBUG=1` and reads the Wayland protocol log from stderr.
4. Finds the main window surface from the
   `xdg_wm_base.get_xdg_surface(..., wl_surface#N)` request.
5. Records the first `wl_surface#N.attach(wl_buffer)` followed by
   `wl_surface#N.commit()` on that surface. This is the first frame submitted
   to the compositor, which is the point at which the shell is interactive.
6. Computes time to first frame as the wall time from launch to the first
   Wayland protocol line plus the protocol timestamp delta from that line to
   the first frame commit.

The script performs one unmeasured warmup run before the measured runs. The
warmup loads the binary and its shared libraries into the page cache, so the
measured runs isolate application startup from disk I/O and are reproducible on
the same machine. This is a cold **process** start with a warm **file** cache.
The first run after extraction, with a cold file cache, was also measured
separately and is recorded below.

The `--smoke` timer exits the app 1500 ms after the event loop starts. The
script also records total wall time as a cross-check; it is not used as the
cold-start claim because it includes the fixed 1500 ms timer and shutdown.

`WAYLAND_DEBUG` logging adds overhead, so the first-frame value is an upper
bound on the real cold start. The method is Linux/Wayland specific. macOS and
Windows need their own runner and measurement method.

### Commands

From the repository root:

```bash
# Build and package the release artifacts.
make rust-build
make rust-package

# Measure the packaged .deb binary (primary claim).
tmpdir="$(mktemp -d)"
dpkg-deb -x rust/dist/chatui_0.1.0_amd64.deb "$tmpdir"
python3 scripts/measure-cold-start.py \
  --binary "$tmpdir/usr/bin/chatui-desktop" \
  --runs 10

# Cross-check the release binary in the build tree.
python3 scripts/measure-cold-start.py \
  --binary rust/target/release/chatui-desktop \
  --runs 10
```

The script prints one line per run and a median/min/max summary. It exits
non-zero if it cannot observe a Wayland first frame or if a run times out.

## Cold-start results

### Packaged `.deb` binary (primary claim)

Measured on 2026-11-08 with `--runs 10` and one warmup run:

| Run | Time to first frame | Connect | Frame after connect | Total wall |
| --- | --- | --- | --- | --- |
| 1 | 1051.1 ms | 51.0 ms | 1000.1 ms | 1725.9 ms |
| 2 | 665.7 ms | 31.9 ms | 633.8 ms | 1670.0 ms |
| 3 | 329.8 ms | 14.9 ms | 314.9 ms | 1568.2 ms |
| 4 | 388.8 ms | 18.1 ms | 370.7 ms | 1618.5 ms |
| 5 | 414.6 ms | 18.2 ms | 396.4 ms | 1618.1 ms |
| 6 | 387.5 ms | 17.2 ms | 370.3 ms | 1569.0 ms |
| 7 | 447.3 ms | 17.7 ms | 429.6 ms | 1619.5 ms |
| 8 | 419.6 ms | 18.4 ms | 401.3 ms | 1618.9 ms |
| 9 | 453.6 ms | 22.7 ms | 430.9 ms | 1619.2 ms |
| 10 | 321.8 ms | 14.9 ms | 306.9 ms | 1621.9 ms |
| **Median** | **417.1 ms** | 18.1 ms | 398.9 ms | 1619.1 ms |
| **Range** | **321.8-1051.1 ms** | 14.9-51.0 ms | 306.9-1000.1 ms | 1568.2-1725.9 ms |

The median is 417.1 ms, well under the 1.5 s target. The slowest run is
1051.1 ms, also under the target. The first run is slower than the rest; the
warmup loads the binary, but the first measured run still shows some
system-level variance.

### Release binary in the build tree (cross-check)

Measured on 2026-11-08 with `--runs 10` and one warmup run:

| Run | Time to first frame | Connect | Frame after connect | Total wall |
| --- | --- | --- | --- | --- |
| 1 | 312.7 ms | 14.9 ms | 297.8 ms | 1568.3 ms |
| 2 | 369.0 ms | 16.0 ms | 353.0 ms | 1568.7 ms |
| 3 | 327.6 ms | 16.7 ms | 310.9 ms | 1619.1 ms |
| 4 | 353.7 ms | 15.9 ms | 337.8 ms | 1569.1 ms |
| 5 | 456.0 ms | 21.8 ms | 434.2 ms | 1619.6 ms |
| 6 | 491.7 ms | 26.7 ms | 465.0 ms | 1620.7 ms |
| 7 | 470.6 ms | 20.1 ms | 450.6 ms | 1621.1 ms |
| 8 | 487.1 ms | 26.3 ms | 460.8 ms | 1622.4 ms |
| 9 | 411.2 ms | 23.1 ms | 388.1 ms | 1619.5 ms |
| 10 | 442.1 ms | 19.0 ms | 423.1 ms | 1618.5 ms |
| **Median** | **426.6 ms** | 19.6 ms | 405.6 ms | 1619.3 ms |
| **Range** | **312.7-491.7 ms** | 14.9-26.7 ms | 297.8-465.0 ms | 1568.3-1622.4 ms |

The build-tree binary is byte-identical to the packaged binary (same SHA-256).
The difference between the two tables is run-to-run system noise, not an
artifact difference.

### Cold file cache (single observation)

The first measured run after extracting the `.deb`, before the warmup, took
1090.5 ms to first frame. This is a single observation, not a median, and it is
recorded only to show that the cold-file-cache case also stayed under the 1.5 s
target. The primary claim uses the warm-file-cache method above because it is
reproducible without root access to drop the page cache.

## Installer sizes

Only Linux artifacts are available in this environment. macOS and Windows
artifacts require their own runners (B-01 and B-02).

| OS | Artifact | Compressed size | Installed size | Budget | Result |
| --- | --- | --- | --- | --- | --- |
| Linux x86_64 | `chatui_0.1.0_amd64.deb` | 6,826,114 bytes (6.51 MiB / 6.83 MB) | 19,416,832 bytes (18.52 MiB / 19.42 MB) | under 40 MB | Pass |
| Linux x86_64 | `ChatUI-0.1.0-linux-x86_64.tar.gz` | 9,003,566 bytes (8.59 MiB / 9.00 MB) | 19,416,885 bytes (18.52 MiB / 19.42 MB) | under 40 MB | Pass |
| macOS Apple Silicon | `.app` / `.dmg` | not measured | not measured | under 40 MB | Blocked (B-01) |
| Windows x86_64 | portable `.zip` | not measured | not measured | under 40 MB | Blocked (B-02) |

The installed size is the sum of the regular files after extracting the
artifact. It is dominated by the 19,415,648-byte release binary. The `.deb`
installs the binary, desktop entry, SVG icon, and copyright file; the `.tar.gz`
contains the binary, desktop entry, SVG icon, and README.

The installer sizes are well under the 40 MB budget. The `.deb` is smaller than
the `.tar.gz` because `dpkg-deb` uses xz compression.

## Build times

| Command | Scope | Observed time | Notes |
| --- | --- | --- | --- |
| `make rust-build` | Linux x86_64, incremental | 1.50 s | Release workspace build with cached dependencies and artifacts. |
| `make rust-package` | Linux x86_64, incremental | 12.11 s | Release build plus `.deb` and `.tar.gz` packaging and checksums. |
| `cargo build --release -p chatui-desktop` | Linux x86_64, clean-ish | 5m 47s (347.5 s) | Recorded by M3.5-11.1 in [release-profile.md](release-profile.md); 3.0 GiB peak RSS. |

The incremental times are the observed wall times for the verification
commands on this host. They are not clean-build times. The clean-ish release
build from M3.5-11.1 is the representative build-time figure. macOS and Windows
build times require their own runners and are not available.

## Budget comparison and misses

| Budget | Target | Result | Miss? |
| --- | --- | --- | --- |
| Cold start | under 1.5 s on a mid-tier desktop | median 417.1 ms, range 321.8-1051.1 ms | No |
| Installer size | under 40 MB | `.deb` 6.51 MiB, `.tar.gz` 8.59 MiB | No |
| No bundled browser engine | required | Slint native renderer; packaging copies only the binary and desktop assets | No |

No Linux miss was observed. The macOS and Windows rows are not misses; they are
unmeasured because the required runners or machines are unavailable. They must
be measured on their own runners before a cross-platform release claim. The
existing blockers are recorded in [M3.5-verification.md](M3.5-verification.md)
section 5.

## Limitations

- The host is a single development laptop, not a controlled benchmark machine.
  The median and range are reported to make the noise visible.
- The file cache is warmed before the measured runs. The cold-file-cache case
  was observed once at 1090.5 ms and stayed under the target, but it is not the
  primary claim.
- `WAYLAND_DEBUG` logging adds overhead, so the first-frame values are upper
  bounds.
- The method measures the first frame submitted to the compositor. It does not
  measure the time until every widget is painted or until a user can type; the
  first frame is the practical "shell is interactive" point.
- macOS and Windows cold start and installer sizes are not measured.
- The build times are incremental except for the M3.5-11.1 clean-ish baseline.

## Out of scope

- Optimizing the app beyond the release profile and feature-gating work.
- Web and Android performance.
- Updater correctness (M3.5-11.3).
- macOS and Windows measurements without their runners.
