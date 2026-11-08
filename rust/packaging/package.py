#!/usr/bin/env python3
"""Build reproducible ChatUI desktop artifacts and SHA-256 checksums.

This is the equivalent packaging pipeline selected in M3.5-11.2. It uses the
host OS's native packaging tools instead of cargo-dist because cargo-dist does
not cover the full required format matrix without additional wrappers.

Usage:
    python3 rust/packaging/package.py
    python3 rust/packaging/package.py --skip-build
    python3 rust/packaging/package.py --list-targets
    python3 rust/packaging/package.py --clean

Only the host target is built. Cross-OS artifacts are produced on their own
runners in M3.5-11.4. Artifacts are written to rust/dist/ and are ignored by
git; never commit them.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import tomllib
import zipfile
from pathlib import Path

PACKAGING_DIR = Path(__file__).resolve().parent
RUST_DIR = PACKAGING_DIR.parent
REPO_ROOT = RUST_DIR.parent
DIST_DIR = RUST_DIR / "dist"
CONFIG_PATH = PACKAGING_DIR / "package.toml"
DESKTOP_ENTRY = PACKAGING_DIR / "linux" / "chatui.desktop"
ICON = PACKAGING_DIR / "linux" / "chatui.svg"

# Zip timestamps cannot represent dates before 1980-01-01.
MIN_EPOCH = 315_532_800


class PackagingError(RuntimeError):
    """A packaging step failed with an actionable message."""


def load_config() -> dict:
    with CONFIG_PATH.open("rb") as handle:
        return tomllib.load(handle)


def read_version() -> str:
    manifest = RUST_DIR / "apps" / "desktop" / "Cargo.toml"
    with manifest.open("rb") as handle:
        return tomllib.load(handle)["package"]["version"]


def host_target_key() -> str:
    system = platform.system()
    machine = platform.machine().lower()
    if system == "Linux":
        arch = {
            "x86_64": "x86_64",
            "amd64": "x86_64",
            "aarch64": "aarch64",
            "arm64": "aarch64",
        }.get(machine)
        if arch:
            return f"linux-{arch}"
    elif system == "Darwin":
        arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64"}.get(machine)
        if arch:
            return f"macos-{arch}"
    elif system == "Windows":
        arch = {"amd64": "x86_64", "x86_64": "x86_64", "arm64": "aarch64"}.get(machine)
        if arch:
            return f"windows-{arch}"
    raise PackagingError(
        f"unsupported host {system}/{machine}; add a target to {CONFIG_PATH}"
    )


def source_date_epoch() -> int:
    value = os.environ.get("SOURCE_DATE_EPOCH")
    if value:
        try:
            return max(int(value), MIN_EPOCH)
        except ValueError as error:
            raise PackagingError(f"SOURCE_DATE_EPOCH is not an integer: {value!r}") from error
    try:
        result = subprocess.run(
            ["git", "log", "-1", "--format=%ct"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        return max(int(result.stdout.strip()), MIN_EPOCH)
    except (OSError, subprocess.CalledProcessError, ValueError):
        return MIN_EPOCH


def run(command: list[str], cwd: Path | None = None, env: dict | None = None) -> None:
    print("+ " + " ".join(command), flush=True)
    subprocess.run(command, cwd=cwd, env=env, check=True)


def require_tool(name: str, purpose: str) -> str:
    path = shutil.which(name)
    if not path:
        raise PackagingError(f"{name} is required for {purpose} but was not found on PATH")
    return path


def set_mtime(path: Path, epoch: int) -> None:
    os.utime(path, (epoch, epoch))


def write_text(path: Path, text: str, epoch: int, mode: int = 0o644) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    path.chmod(mode)
    set_mtime(path, epoch)


def copy_binary(source: Path, destination: Path, epoch: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(0o755)
    set_mtime(destination, epoch)


def readme_text(app: dict, version: str) -> str:
    return (
        f"{app['display_name']} {version}\n"
        "\n"
        "Run:\n"
        "  Linux:   ./chatui-desktop\n"
        "  macOS:   open ChatUI.app\n"
        "  Windows: chatui-desktop.exe\n"
        "\n"
        f"{app['description']}\n"
        "\n"
        "ChatUI stores conversations, settings, attachments, and provider API\n"
        "keys on this device. Provider requests go directly to the provider you\n"
        "configure. No browser engine is bundled.\n"
    )


def build_release() -> None:
    run(
        [
            "cargo",
            "build",
            "--release",
            "--locked",
            "-p",
            "chatui-desktop",
            "--manifest-path",
            str(RUST_DIR / "Cargo.toml"),
        ],
        cwd=RUST_DIR,
    )


def release_binary(app: dict, target: str) -> Path:
    name = app["binary"] + (".exe" if target.startswith("windows-") else "")
    path = RUST_DIR / "target" / "release" / name
    if not path.is_file():
        raise PackagingError(
            f"release binary not found at {path}; run without --skip-build first"
        )
    return path


def add_tar_file(tar: tarfile.TarFile, path: Path, arcname: str, epoch: int) -> None:
    info = tarfile.TarInfo(arcname)
    info.size = path.stat().st_size
    info.mtime = epoch
    info.mode = 0o755 if os.access(path, os.X_OK) else 0o644
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    with path.open("rb") as handle:
        tar.addfile(info, handle)


def make_tar_gz(
    app: dict, version: str, target: str, binary: Path, epoch: int
) -> Path:
    output = DIST_DIR / f"ChatUI-{version}-{target}.tar.gz"
    root = f"ChatUI-{version}-{target}"
    with tempfile.TemporaryDirectory(prefix="chatui-tar-") as temp:
        stage = Path(temp) / root
        stage.mkdir()
        copy_binary(binary, stage / app["binary"], epoch)
        shutil.copyfile(DESKTOP_ENTRY, stage / "chatui.desktop")
        shutil.copyfile(ICON, stage / "chatui.svg")
        write_text(stage / "README.txt", readme_text(app, version), epoch)
        for item in stage.iterdir():
            set_mtime(item, epoch)
        with output.open("wb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", mtime=epoch, filename="") as gz:
                with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as tar:
                    for item in sorted(stage.iterdir()):
                        add_tar_file(tar, item, f"{root}/{item.name}", epoch)
    return output


def make_zip(
    app: dict, version: str, target: str, binary: Path, epoch: int
) -> Path:
    output = DIST_DIR / f"ChatUI-{version}-{target}.zip"
    root = f"ChatUI-{version}-{target}"
    stamp = time.gmtime(epoch)[:6]
    with tempfile.TemporaryDirectory(prefix="chatui-zip-") as temp:
        stage = Path(temp) / root
        stage.mkdir()
        copy_binary(binary, stage / app["binary"], epoch)
        write_text(stage / "README.txt", readme_text(app, version), epoch)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for item in sorted(stage.iterdir()):
                info = zipfile.ZipInfo(f"{root}/{item.name}", date_time=stamp)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o755 if os.access(item, os.X_OK) else 0o644) << 16
                archive.writestr(info, item.read_bytes())
    return output


def deb_depends(binary: Path) -> str:
    """Return the Debian Depends line for the staged binary.

    Prefer dpkg-shlibdeps so the minimum library versions match the build host.
    Fall back to a conservative manual list when dpkg-shlibdeps is unavailable.
    """
    fallback = (
        "libgtk-3-0t64 | libgtk-3-0, libfontconfig1, libx11-6, libxi6, "
        "libxfixes3, libxrender1, libxkbcommon0, libwayland-client0, libgl1, libegl1"
    )
    if not shutil.which("dpkg-shlibdeps"):
        return fallback
    with tempfile.TemporaryDirectory(prefix="chatui-shlibdeps-") as temp:
        debian = Path(temp) / "debian"
        debian.mkdir()
        (debian / "control").write_text(
            "Source: chatui\n\nPackage: chatui\nArchitecture: any\n",
            encoding="utf-8",
        )
        result = subprocess.run(
            ["dpkg-shlibdeps", "-O", f"-e{binary}"],
            cwd=temp,
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            return fallback
        for line in result.stdout.splitlines():
            if line.startswith("shlibs:Depends="):
                return line.split("=", 1)[1].strip()
    return fallback


def deb_control(
    app: dict, version: str, architecture: str, installed_size: int, depends: str
) -> str:
    return (
        f"Package: {app['name']}\n"
        f"Version: {version}\n"
        "Section: utils\n"
        "Priority: optional\n"
        f"Architecture: {architecture}\n"
        f"Maintainer: {app['maintainer']}\n"
        f"Installed-Size: {installed_size}\n"
        f"Depends: {depends}\n"
        f"Homepage: {app['homepage']}\n"
        f"Description: {app['description']}\n"
        " ChatUI is a local-first, bring-your-own-key chat client for multiple\n"
        " model providers. Conversations, settings, attachments, and provider API\n"
        " keys stay on the user's device.\n"
    )


def deb_copyright(app: dict) -> str:
    return (
        "Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/\n"
        f"Upstream-Name: {app['display_name']}\n"
        f"Source: {app['homepage']}\n"
        "\n"
        "Files: *\n"
        f"Copyright: {', '.join(app['authors'])}\n"
        f"License: {app['license']}\n"
        " No license has been granted yet. This placeholder must be replaced when\n"
        " the project license is confirmed.\n"
    )


def make_deb(
    app: dict, version: str, target: str, binary: Path, epoch: int, config: dict
) -> Path:
    require_tool("dpkg-deb", "building the Linux .deb artifact")
    architecture = config["targets"][target]["deb_architecture"]
    output = DIST_DIR / f"{app['name']}_{version}_{architecture}.deb"
    with tempfile.TemporaryDirectory(prefix="chatui-deb-") as temp:
        stage = Path(temp) / f"{app['name']}_{version}_{architecture}"
        copy_binary(binary, stage / "usr" / "bin" / app["binary"], epoch)
        applications = stage / "usr/share/applications"
        applications.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(DESKTOP_ENTRY, applications / "chatui.desktop")
        icons = stage / "usr/share/icons/hicolor/scalable/apps"
        icons.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ICON, icons / "chatui.svg")
        write_text(stage / "usr/share/doc/chatui/copyright", deb_copyright(app), epoch)
        for path in stage.rglob("*"):
            set_mtime(path, epoch)
        installed_size = sum(
            path.stat().st_size for path in stage.rglob("*") if path.is_file()
        ) // 1024
        depends = deb_depends(stage / "usr" / "bin" / app["binary"])
        write_text(
            stage / "DEBIAN" / "control",
            deb_control(app, version, architecture, installed_size, depends),
            epoch,
        )
        set_mtime(stage / "DEBIAN", epoch)
        set_mtime(stage, epoch)
        env = dict(os.environ)
        env["SOURCE_DATE_EPOCH"] = str(epoch)
        run(
            ["dpkg-deb", "--root-owner-group", "--build", str(stage), str(output)],
            env=env,
        )
    return output


def info_plist(app: dict, version: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleDisplayName</key><string>{app['display_name']}</string>
  <key>CFBundleExecutable</key><string>{app['binary']}</string>
  <key>CFBundleIdentifier</key><string>{app['identifier']}</string>
  <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
  <key>CFBundleName</key><string>{app['display_name']}</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>{version}</string>
  <key>CFBundleVersion</key><string>{version}</string>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>CFBundleURLTypes</key>
  <array>
    <dict>
      <key>CFBundleURLName</key><string>{app['identifier']}</string>
      <key>CFBundleURLSchemes</key><array><string>chatui</string></array>
    </dict>
  </array>
</dict>
</plist>
"""


def make_app_bundle(
    app: dict, version: str, target: str, binary: Path, epoch: int
) -> Path:
    bundle = DIST_DIR / f"{app['display_name']}.app"
    if bundle.exists():
        shutil.rmtree(bundle)
    copy_binary(binary, bundle / "Contents" / "MacOS" / app["binary"], epoch)
    write_text(bundle / "Contents" / "Info.plist", info_plist(app, version), epoch)
    resources = bundle / "Contents" / "Resources"
    resources.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ICON, resources / "chatui.svg")
    for path in bundle.rglob("*"):
        set_mtime(path, epoch)
    identity = os.environ.get("APPLE_SIGNING_IDENTITY")
    if identity:
        run(
            [
                "codesign",
                "--force",
                "--deep",
                "--options",
                "runtime",
                "--sign",
                identity,
                str(bundle),
            ]
        )
    else:
        print(
            "warning: APPLE_SIGNING_IDENTITY is unset; the app bundle is unsigned. "
            "Signing and notarization credentials are external (B-03).",
            file=sys.stderr,
        )
    return bundle


def make_dmg(app: dict, version: str, target: str, bundle: Path, epoch: int) -> Path:
    require_tool("hdiutil", "building the macOS DMG artifact")
    output = DIST_DIR / f"ChatUI-{version}-{target}.dmg"
    run(
        [
            "hdiutil",
            "create",
            "-volname",
            app["display_name"],
            "-srcfolder",
            str(bundle),
            "-ov",
            "-format",
            "UDZO",
            str(output),
        ]
    )
    set_mtime(output, epoch)
    profile = os.environ.get("APPLE_NOTARY_PROFILE")
    if profile:
        run(
            [
                "xcrun",
                "notarytool",
                "submit",
                str(output),
                "--keychain-profile",
                profile,
                "--wait",
            ]
        )
    else:
        print(
            "warning: APPLE_NOTARY_PROFILE is unset; the DMG is not notarized. "
            "Notarization credentials are external (B-03).",
            file=sys.stderr,
        )
    return output


def make_app_zip(app: dict, version: str, target: str, bundle: Path, epoch: int) -> Path:
    output = DIST_DIR / f"ChatUI-{version}-{target}.app.zip"
    stamp = time.gmtime(epoch)[:6]
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(bundle.rglob("*")):
            if path.is_dir():
                continue
            arcname = str(path.relative_to(bundle.parent))
            info = zipfile.ZipInfo(arcname, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if os.access(path, os.X_OK) else 0o644) << 16
            archive.writestr(info, path.read_bytes())
    return output


def write_checksums() -> Path:
    output = DIST_DIR / "SHA256SUMS"
    lines = []
    for path in sorted(DIST_DIR.iterdir()):
        if not path.is_file() or path.name == output.name:
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        lines.append(f"{digest}  {path.name}\n")
    output.write_text("".join(lines), encoding="utf-8")
    return output


def clean_dist() -> None:
    if DIST_DIR.exists():
        shutil.rmtree(DIST_DIR)
    DIST_DIR.mkdir(parents=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="reuse the existing release binary instead of running cargo build",
    )
    parser.add_argument(
        "--target",
        help="target key from package.toml; defaults to the host target",
    )
    parser.add_argument(
        "--list-targets",
        action="store_true",
        help="print configured targets and formats, then exit",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="remove rust/dist/ before packaging",
    )
    args = parser.parse_args()

    config = load_config()
    if args.list_targets:
        for name, target in config["targets"].items():
            print(f"{name}: {', '.join(target['formats'])}")
        return 0

    target = args.target or host_target_key()
    if target not in config["targets"]:
        raise PackagingError(
            f"target {target!r} is not configured in {CONFIG_PATH}; "
            f"configured targets: {', '.join(config['targets'])}"
        )
    if target != host_target_key():
        raise PackagingError(
            f"target {target!r} does not match the host {host_target_key()!r}; "
            "cross-OS artifacts are built on their own runners in M3.5-11.4"
        )

    app = config["app"]
    version = read_version()
    epoch = source_date_epoch()

    if args.clean:
        clean_dist()
    DIST_DIR.mkdir(parents=True, exist_ok=True)

    if not args.skip_build:
        build_release()
    binary = release_binary(app, target)

    formats = config["targets"][target]["formats"]
    artifacts: list[Path] = []
    for fmt in formats:
        if fmt == "deb":
            artifacts.append(make_deb(app, version, target, binary, epoch, config))
        elif fmt == "tar.gz":
            artifacts.append(make_tar_gz(app, version, target, binary, epoch))
        elif fmt == "zip":
            artifacts.append(make_zip(app, version, target, binary, epoch))
        elif fmt == "app":
            bundle = make_app_bundle(app, version, target, binary, epoch)
            artifacts.append(make_app_zip(app, version, target, bundle, epoch))
        elif fmt == "dmg":
            bundle = DIST_DIR / f"{app['display_name']}.app"
            if not bundle.exists():
                bundle = make_app_bundle(app, version, target, binary, epoch)
            artifacts.append(make_dmg(app, version, target, bundle, epoch))
        else:
            raise PackagingError(f"unknown format {fmt!r} for target {target!r}")

    checksums = write_checksums()
    print("\nArtifacts:")
    for artifact in artifacts:
        print(f"  {artifact}")
    print(f"Checksums: {checksums}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PackagingError as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
    except subprocess.CalledProcessError as error:
        print(f"error: command failed with exit code {error.returncode}", file=sys.stderr)
        raise SystemExit(error.returncode)
