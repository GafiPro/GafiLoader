#!/usr/bin/env python3
"""Launch Minecraft 26.2 with Fabric and the pinned 1.21.11 mod under a virtual X server.

This is an end-to-end startup smoke test, not a claim that every feature of the mod works.
It downloads the official runtime dependencies/assets, applies the normal Fabric version-gate
override, starts the packaged Gafi Loader Java agent, and watches the real game log.
"""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from fetch_real_integration_fixtures import MANIFEST_URL, USER_AGENT, game_version, get_json

LOADER_VERSION = "0.20.1"
LAUNCH_TIMEOUT_SECONDS = 150
POLL_SECONDS = 1


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_file(url: str, destination: Path, expected_sha1: str | None = None) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file():
        if expected_sha1 is None or sha1_file(destination).lower() == expected_sha1.lower():
            return destination
    last_error: Exception | None = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
            digest = hashlib.sha1(payload).hexdigest()
            if expected_sha1 and digest.lower() != expected_sha1.lower():
                raise RuntimeError(f"SHA-1 mismatch for {url}: expected {expected_sha1}, received {digest}")
            temporary = destination.with_suffix(destination.suffix + ".part")
            temporary.write_bytes(payload)
            temporary.replace(destination)
            return destination
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"Unable to download {url}: {last_error}")


def rule_applies(rule: dict, features: dict[str, bool]) -> bool:
    os_rule = rule.get("os", {})
    current_os = "windows" if os.name == "nt" else "osx" if sys.platform == "darwin" else "linux"
    if os_rule.get("name") and os_rule["name"] != current_os:
        return False
    arch = platform.machine().lower()
    if os_rule.get("arch") and not re.search(os_rule["arch"], arch):
        return False
    if os_rule.get("version") and not re.search(os_rule["version"], platform.version()):
        return False
    for feature, expected in rule.get("features", {}).items():
        if features.get(feature, False) != expected:
            return False
    return True


def launcher_features() -> dict[str, bool]:
    return {
        "is_demo_user": False,
        "has_custom_resolution": True,
        "has_quick_plays_support": False,
        "is_quick_play_singleplayer": False,
        "is_quick_play_multiplayer": False,
        "is_quick_play_realms": False,
    }


def library_allowed(library: dict) -> bool:
    rules = library.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if rule_applies(rule, launcher_features()):
            allowed = rule.get("action") == "allow"
    return allowed


def maven_relative_path(coordinate: str) -> str:
    ext = "jar"
    if "@" in coordinate:
        coordinate, ext = coordinate.rsplit("@", 1)
    pieces = coordinate.split(":")
    if len(pieces) not in (3, 4):
        raise ValueError(f"Unsupported Maven coordinate: {coordinate}")
    group, artifact, version = pieces[:3]
    classifier = f"-{pieces[3]}" if len(pieces) == 4 else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{classifier}.{ext}"


def artifact_url(library: dict) -> tuple[str, str | None, str]:
    artifact = library.get("downloads", {}).get("artifact")
    if artifact and artifact.get("url"):
        path = artifact.get("path") or maven_relative_path(library["name"])
        return artifact["url"], artifact.get("sha1"), path

    path = maven_relative_path(library["name"])
    base_url = library.get("url") or "https://libraries.minecraft.net/"
    if not base_url.endswith("/"):
        base_url += "/"
    return base_url + path, library.get("sha1"), path


def extract_native_archive(archive: Path, destination: Path, excluded_prefixes: list[str]) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            name = entry.filename.replace("\\", "/")
            if entry.is_dir() or name.startswith("/") or ".." in Path(name).parts:
                continue
            if name.startswith("META-INF/") or any(name.startswith(prefix) for prefix in excluded_prefixes):
                continue
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                target.write_bytes(bundle.read(entry))


def collect_libraries(game: dict, fabric_profile: dict, libraries_root: Path, natives_dir: Path) -> list[Path]:
    classpath: list[Path] = []
    seen: set[Path] = set()
    native_entries: set[tuple[str, str]] = set()
    for library in [*game.get("libraries", []), *fabric_profile.get("libraries", [])]:
        if not library_allowed(library):
            continue
        try:
            url, sha1, relative = artifact_url(library)
        except (KeyError, ValueError):
            url, sha1, relative = "", None, ""
        if url and relative:
            artifact_path = fetch_file(url, libraries_root / relative, sha1)
            resolved = artifact_path.resolve()
            if resolved not in seen:
                seen.add(resolved)
                classpath.append(artifact_path)

        native_key = library.get("natives", {}).get("linux")
        if native_key:
            arch_value = "64" if platform.machine().lower() in ("x86_64", "amd64") else "32"
            native_key = native_key.replace("$" + "{arch}", arch_value)
            classifier = library.get("downloads", {}).get("classifiers", {}).get(native_key)
            if classifier and classifier.get("url"):
                native_relative = classifier.get("path")
                if not native_relative:
                    native_relative = maven_relative_path(library["name"] + ":" + native_key)
                native_path = libraries_root / native_relative
                item = (classifier["url"], str(native_path))
                if item not in native_entries:
                    native_entries.add(item)
                    fetch_file(classifier["url"], native_path, classifier.get("sha1"))
                    extract_native_archive(native_path, natives_dir, library.get("extract", {}).get("exclude", []))
    return classpath


def prepare_assets(game: dict, assets_dir: Path) -> str:
    asset_index = game.get("assetIndex")
    if not asset_index or not asset_index.get("url"):
        raise RuntimeError("Official Minecraft metadata has no asset index URL")
    index_path = fetch_file(
        asset_index["url"],
        assets_dir / "indexes" / f'{asset_index["id"]}.json',
        asset_index.get("sha1"),
    )
    index = json.loads(index_path.read_text(encoding="utf-8"))
    objects = index.get("objects", {})
    tasks: list[tuple[str, Path, str]] = []
    for object_name, metadata in objects.items():
        digest = metadata["hash"]
        destination = assets_dir / "objects" / digest[:2] / digest
        if destination.is_file() and destination.stat().st_size == metadata.get("size", destination.stat().st_size):
            continue
        tasks.append((
            f'https://resources.download.minecraft.net/{digest[:2]}/{digest}',
            destination,
            digest,
        ))

    print(f"Downloading {len(tasks)} missing Minecraft assets ({len(objects)} total indexed)...", flush=True)

    def download_object(task: tuple[str, Path, str]) -> None:
        url, destination, digest = task
        fetch_file(url, destination, digest)

    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
        futures = [executor.submit(download_object, task) for task in tasks]
        for index_no, future in enumerate(concurrent.futures.as_completed(futures), 1):
            future.result()
            if index_no % 1000 == 0:
                print(f"  downloaded {index_no}/{len(tasks)} assets", flush=True)

    if index.get("virtual"):
        virtual_root = assets_dir / "virtual" / "legacy"
        for object_name, metadata in objects.items():
            digest = metadata["hash"]
            source = assets_dir / "objects" / digest[:2] / digest
            destination = virtual_root / object_name
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists():
                try:
                    os.link(source, destination)
                except OSError:
                    shutil.copy2(source, destination)
    return asset_index["id"]


def flatten_game_arguments(game: dict, replacements: dict[str, str]) -> list[str]:
    arguments: list[str] = []
    for entry in game.get("arguments", {}).get("game", []):
        if isinstance(entry, str):
            values = [entry]
        elif isinstance(entry, dict) and "value" in entry:
            rules = entry.get("rules", [])
            allowed = not rules
            for rule in rules:
                if rule_applies(rule, launcher_features()):
                    allowed = rule.get("action") == "allow"
            if not allowed:
                continue
            raw = entry["value"]
            values = raw if isinstance(raw, list) else [raw]
        else:
            continue

        for value in values:
            if not isinstance(value, str):
                continue

            def replace(match: re.Match[str]) -> str:
                key = match.group(1)
                if key not in replacements:
                    raise RuntimeError("Unrecognized launcher argument placeholder: $" + "{" + key + "}")
                return replacements[key]

            arguments.append(re.sub(r"\$\{([^}]+)\}", replace, value))
    return arguments


def read_mod_id(mod_jar: Path) -> str:
    with zipfile.ZipFile(mod_jar) as jar:
        data = json.loads(jar.read("fabric.mod.json").decode("utf-8"))
    return data["id"]


def capture_runtime_diagnostics() -> str:
    """Capture process state and JVM thread stacks before timing out a hung client."""
    sections: list[str] = []
    try:
        processes = subprocess.run(
            ["ps", "-eo", "pid,ppid,stat,etime,%cpu,%mem,args"],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10, check=False,
        )
        sections.append("===== Process table =====\n" + processes.stdout)
    except (OSError, subprocess.TimeoutExpired) as error:
        sections.append(f"Could not read process table: {error}")

    try:
        listing = subprocess.run(
            ["jcmd", "-l"], text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=10, check=False,
        )
        sections.append("===== jcmd -l =====\n" + listing.stdout)
        pids: list[str] = []
        for line in listing.stdout.splitlines():
            match = re.match(r"^\s*(\d+)\s+(.*)$", line)
            if match and "jdk.jcmd" not in match.group(2):
                pids.append(match.group(1))
        for pid in pids[:5]:
            try:
                dump = subprocess.run(
                    ["jcmd", pid, "Thread.print", "-l"], text=True,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=20, check=False,
                )
                sections.append(f"===== JVM {pid} Thread.print -l (exit {dump.returncode}) =====\n" + dump.stdout)
            except (OSError, subprocess.TimeoutExpired) as error:
                sections.append(f"Could not capture JVM {pid} thread dump: {error}")
    except (OSError, subprocess.TimeoutExpired) as error:
        sections.append(f"Could not list JVMs with jcmd: {error}")
    return "\n\n".join(sections) + "\n"


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: run_fabric_client_smoke.py <fixture-dir> <gafi-loader-fat-jar> <fabric-loader-jar>", file=sys.stderr)
        return 2

    fixture_dir = Path(sys.argv[1]).resolve()
    gafi_jar = Path(sys.argv[2]).resolve()
    fabric_loader_download = Path(sys.argv[3]).resolve()
    target_game = Path(os.environ["GAFI_REAL_TARGET_GAME"]).resolve()
    mod_jar = Path(os.environ["GAFI_REAL_LEGACY_MOD"]).resolve()
    intermediary_mappings = Path(os.environ["GAFI_REAL_INTERMEDIARY_MAPPINGS"]).resolve()
    legacy_mappings = Path(os.environ["GAFI_REAL_LEGACY_MAPPINGS"]).resolve()
    for required in (target_game, mod_jar, intermediary_mappings, legacy_mappings, gafi_jar, fabric_loader_download):
        if not required.is_file():
            raise RuntimeError(f"Required launch artifact not found: {required}")

    print("Resolving official Minecraft 26.2 and Fabric Loader runtime...", flush=True)
    manifest = get_json(MANIFEST_URL)
    game = game_version(manifest, "26.2")
    profile_url = f"https://meta.fabricmc.net/v2/versions/loader/26.2/{LOADER_VERSION}/profile/json"
    fabric_profile = get_json(profile_url)
    if fabric_profile.get("inheritsFrom") not in (None, "26.2"):
        raise RuntimeError(f"Unexpected Fabric profile parent: {fabric_profile.get('inheritsFrom')}")
    main_class = fabric_profile.get("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient")

    launch_root = fixture_dir / "fabric-client-smoke"
    assets_dir = launch_root / "assets"
    libraries_root = launch_root / "libraries"
    natives_dir = launch_root / "natives"
    game_dir = launch_root / "game"
    mods_dir = game_dir / "mods"
    config_dir = game_dir / "config"
    mods_dir.mkdir(parents=True, exist_ok=True)
    config_dir.mkdir(parents=True, exist_ok=True)
    mod_destination = mods_dir / mod_jar.name
    if not mod_destination.exists() or sha1_file(mod_destination) != sha1_file(mod_jar):
        shutil.copy2(mod_jar, mod_destination)

    index_id = prepare_assets(game, assets_dir)
    classpath = collect_libraries(game, fabric_profile, libraries_root, natives_dir)
    # The agent JAR must not also sit on Fabric's ordinary game classpath: it bundles ASM,
    # and Fabric deliberately rejects duplicate ASM classes. -javaagent installs it separately.
    classpath.append(target_game)
    classpath = list(dict.fromkeys(str(path.resolve()) for path in classpath))
    if not classpath:
        raise RuntimeError("The resolved Minecraft/Fabric classpath is empty")

    agent_config = launch_root / "gafi-loader-agent.properties"
    agent_config.write_text(
        "\n".join([
            f"targetGameJar={target_game}",
            f"intermediaryMappings={intermediary_mappings}",
            f"legacyMappings={legacy_mappings}",
            "",
        ]),
        encoding="utf-8",
    )

    override_path = config_dir / "fabric_loader_dependencies.json"
    generation = subprocess.run(
        ["java", "-jar", str(gafi_jar), "--mods", str(mods_dir),
         "--generate-dependency-overrides", str(override_path)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=30,
        check=False,
    )
    print(generation.stdout, flush=True)
    if generation.returncode != 0:
        raise RuntimeError(f"Could not generate the Fabric override config (exit {generation.returncode})")

    replacements = {
        "auth_player_name": "GafiLoaderCI",
        "version_name": fabric_profile.get("id", f"fabric-loader-26.2-{LOADER_VERSION}"),
        "game_directory": str(game_dir),
        "assets_root": str(assets_dir),
        "assets_index_name": index_id,
        "auth_uuid": "00000000-0000-0000-0000-000000000000",
        "auth_access_token": "0",
        "auth_session": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
        "resolution_width": "854",
        "resolution_height": "480",
        "natives_directory": str(natives_dir),
        "launcher_name": "GafiLoaderCI",
        "launcher_version": "1.0",
        "library_directory": str(libraries_root),
        "classpath": os.pathsep.join(classpath),
        "classpath_separator": os.pathsep,
    }
    game_args = flatten_game_arguments(game, replacements)
    required_args = {
        "--username": "GafiLoaderCI",
        "--version": replacements["version_name"],
        "--gameDir": str(game_dir),
        "--assetsDir": str(assets_dir),
        "--assetIndex": index_id,
        "--uuid": replacements["auth_uuid"],
        "--accessToken": "0",
        "--userType": "legacy",
        "--versionType": "release",
    }
    for flag, value in required_args.items():
        if flag not in game_args:
            game_args.extend([flag, value])
    if "--width" not in game_args:
        game_args.extend(["--width", "854", "--height", "480"])

    java_executable = shutil.which("java")
    if not java_executable:
        raise RuntimeError("Java executable not found")
    command = [
        java_executable,
        "-Xms512M",
        "-Xmx3G",
        f"-Djava.library.path={natives_dir}",
        f"-Dorg.lwjgl.librarypath={natives_dir}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives_dir}",
        f"-Djna.tmpdir={natives_dir}",
        f"-Dio.netty.native.workdir={natives_dir}",
        f"-Duser.dir={game_dir}",
        f"-javaagent:{gafi_jar}={agent_config}",
        "-cp",
        os.pathsep.join(classpath),
        main_class,
        *game_args,
    ]
    xvfb = shutil.which("xvfb-run")
    if not xvfb:
        raise RuntimeError("xvfb-run is required for the real client startup smoke test")
    command = [xvfb, "-a", "-s", "-screen 0 1280x800x24", *command]
    log_path = game_dir / "logs" / "latest.log"
    console_path = launch_root / "client-console.log"
    env = os.environ.copy()
    env["LIBGL_ALWAYS_SOFTWARE"] = "1"
    env["MESA_GL_VERSION_OVERRIDE"] = "4.6"
    mod_id = read_mod_id(mod_destination).lower()

    print(f"Starting actual Fabric client with mod id '{mod_id}' (timeout {LAUNCH_TIMEOUT_SECONDS}s)...", flush=True)
    print("Launch main class: " + main_class, flush=True)
    runtime_diagnostics = ""
    with console_path.open("w", encoding="utf-8") as console:
        process = subprocess.Popen(
            command, cwd=game_dir, env=env, stdout=console, stderr=subprocess.STDOUT
        )
        deadline = time.monotonic() + LAUNCH_TIMEOUT_SECONDS
        started = time.monotonic()
        last_status = -15
        success_reason = None
        while time.monotonic() < deadline:
            return_code = process.poll()
            if return_code is not None:
                break
            log_text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
            combined = log_text.lower()
            if mod_id in combined and (
                "sound engine started" in combined
                or "started minecraft" in combined
                or "reloading resourcemanager" in combined
            ):
                if not any(marker in combined for marker in (
                    "mixinapplyerror", "mixin apply failed", "could not execute entrypoint",
                    "crash report", "fatal error",
                )):
                    success_reason = "legacy mod present in the client log after resource loading began"
                    break
            elapsed = int(time.monotonic() - started)
            if elapsed // 15 > last_status // 15:
                last_status = elapsed
                console.flush()
                log_state = f"{log_path.stat().st_size} bytes" if log_path.is_file() else "not created"
                print(f"Client still running after {elapsed}s; latest.log: {log_state}; "
                      f"console bytes: {console_path.stat().st_size}", flush=True)
            time.sleep(POLL_SECONDS)

        if process.poll() is None:
            print("Client startup deadline reached; collecting JVM diagnostics before stopping it...", flush=True)
            console.flush()
            runtime_diagnostics = capture_runtime_diagnostics()
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    console_text = console_path.read_text(encoding="utf-8", errors="replace")
    if runtime_diagnostics:
        with console_path.open("a", encoding="utf-8") as console:
            console.write("\n\n" + runtime_diagnostics)
        console_text += "\n\n" + runtime_diagnostics
    log_text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
    if not success_reason:
        print("===== Minecraft latest.log =====", flush=True)
        print(log_text[-30000:], flush=True)
        print("===== Client console =====", flush=True)
        print(console_text[-15000:], flush=True)
        raise RuntimeError(
            f"Fabric client did not reach the legacy-mod/resource-load milestone. "
            f"Process exit code: {process.returncode}; logs: {log_path}, {console_path}"
        )

    combined = (log_text + "\n" + console_text).lower()
    if any(marker in combined for marker in (
        "mixinapplyerror", "mixin apply failed", "could not execute entrypoint", "crash report",
    )):
        raise RuntimeError(f"Client log contains a fatal mod/bootstrap error despite marker: {success_reason}")

    print("FABRIC_CLIENT_REAL_MOD_SMOKE_OK", flush=True)
    print(f"mod_id={mod_id}", flush=True)
    print(f"success_reason={success_reason}", flush=True)
    print(f"game_log={log_path}", flush=True)
    print(f"console_log={console_path}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Fabric client smoke failed: {error}", file=sys.stderr, flush=True)
        raise
