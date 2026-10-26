#!/usr/bin/env python3
"""Fetch pinned public artifacts used by the real-mod CI integration test.

This downloads the official Mojang client JAR for Minecraft 26.2, the official
1.21.11 Mojang mappings, Fabric Intermediary 1.21.11, and one small, released
Fabric mod compiled for 1.21.11. The mod JAR is only read by tests; it is never
rewritten or repackaged.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

USER_AGENT = "GafiPro-GafiLoader/0.1 (real-artifact-compatibility-test)"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
INTERMEDIARY_URL = (
    "https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.11/"
    "intermediary-1.21.11-v2.jar"
)
# Small Items Mod 1.21.11, Modrinth version ID verified from its version page.
MODRINTH_VERSION_ID = "oO030Ff9"
MODRINTH_VERSION_URL = f"https://api.modrinth.com/v2/version/{MODRINTH_VERSION_ID}"
# Fabric API 0.141.6 for Minecraft 1.21.11, pinned to its public Modrinth version ID.
FABRIC_API_VERSION_ID = "6qAuTtLR"
FABRIC_API_VERSION_URL = f"https://api.modrinth.com/v2/version/{FABRIC_API_VERSION_ID}"


def request_bytes(url: str, attempts: int = 3) -> bytes:
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=90) as response:
                return response.read()
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            if attempt + 1 < attempts:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"Unable to download {url}: {last_error}")


def get_json(url: str) -> dict | list:
    return json.loads(request_bytes(url).decode("utf-8"))


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path, expected_sha1: str | None = None) -> dict:
    if destination.is_file():
        actual = sha1_file(destination)
        if expected_sha1 is None or actual.lower() == expected_sha1.lower():
            return {"url": url, "path": str(destination), "sha1": actual, "cached": True}

    data = request_bytes(url)
    actual = hashlib.sha1(data).hexdigest()
    if expected_sha1 and actual.lower() != expected_sha1.lower():
        raise RuntimeError(
            f"SHA-1 mismatch for {url}: expected {expected_sha1}, received {actual}"
        )
    temporary = destination.with_suffix(destination.suffix + ".part")
    temporary.write_bytes(data)
    temporary.replace(destination)
    return {"url": url, "path": str(destination), "sha1": actual, "cached": False}


def game_version(manifest: dict, version_id: str) -> dict:
    entry = next((version for version in manifest["versions"] if version["id"] == version_id), None)
    if entry is None:
        raise RuntimeError(f"Mojang's official manifest does not list Minecraft {version_id}")
    metadata = get_json(entry["url"])
    if not isinstance(metadata, dict):
        raise RuntimeError(f"Invalid Mojang metadata for Minecraft {version_id}")
    return metadata


def main() -> int:
    output = Path(sys.argv[1] if len(sys.argv) > 1 else "build/real-artifact-test").resolve()
    output.mkdir(parents=True, exist_ok=True)

    manifest = get_json(MANIFEST_URL)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("versions"), list):
        raise RuntimeError("Invalid official Mojang version manifest")

    target_version = game_version(manifest, "26.2")
    legacy_version = game_version(manifest, "1.21.11")

    target_download = target_version.get("downloads", {}).get("client")
    legacy_download = legacy_version.get("downloads", {}).get("client")
    legacy_mappings = legacy_version.get("downloads", {}).get("client_mappings")
    if not target_download or not target_download.get("url"):
        raise RuntimeError("Official Minecraft 26.2 metadata has no client JAR download")
    if not legacy_download or not legacy_download.get("url"):
        raise RuntimeError("Official Minecraft 1.21.11 metadata has no client JAR download")
    if not legacy_mappings or not legacy_mappings.get("url"):
        raise RuntimeError("Official Minecraft 1.21.11 metadata has no client mappings")

    artifacts = {}
    artifacts["target_game"] = download(
        target_download["url"], output / "minecraft-26.2-client.jar", target_download.get("sha1")
    )
    artifacts["legacy_game"] = download(
        legacy_download["url"], output / "minecraft-1.21.11-client.jar", legacy_download.get("sha1")
    )
    artifacts["legacy_mappings"] = download(
        legacy_mappings["url"], output / "minecraft-1.21.11-client-mappings.txt",
        legacy_mappings.get("sha1")
    )
    artifacts["intermediary_mappings"] = download(
        INTERMEDIARY_URL, output / "intermediary-1.21.11-v2.jar"
    )

    mod_version = get_json(MODRINTH_VERSION_URL)
    if not isinstance(mod_version, dict):
        raise RuntimeError("Invalid Modrinth version metadata")
    if "1.21.11" not in mod_version.get("game_versions", []):
        raise RuntimeError("Pinned Modrinth mod version is not marked for Minecraft 1.21.11")
    if "fabric" not in [loader.lower() for loader in mod_version.get("loaders", [])]:
        raise RuntimeError("Pinned Modrinth mod version is not marked for Fabric")
    files = mod_version.get("files", [])
    mod_file = next((file for file in files if file.get("primary")), files[0] if files else None)
    if not mod_file or not mod_file.get("url"):
        raise RuntimeError("Pinned Modrinth version has no downloadable mod JAR")
    mod_sha1 = mod_file.get("hashes", {}).get("sha1")
    artifacts["legacy_mod"] = download(
        mod_file["url"], output / "small-items-mod-1.21.11.jar", mod_sha1
    )

    fabric_api_version = get_json(FABRIC_API_VERSION_URL)
    if not isinstance(fabric_api_version, dict) or fabric_api_version.get("id") != FABRIC_API_VERSION_ID:
        raise RuntimeError("Invalid pinned Modrinth Fabric API version metadata")
    if "1.21.11" not in fabric_api_version.get("game_versions", []):
        raise RuntimeError("Pinned Fabric API version is not marked for Minecraft 1.21.11")
    if "fabric" not in [loader.lower() for loader in fabric_api_version.get("loaders", [])]:
        raise RuntimeError("Pinned Fabric API version is not marked for Fabric")
    api_files = fabric_api_version.get("files", [])
    api_file = next((file for file in api_files if file.get("primary")), api_files[0] if api_files else None)
    if not api_file or not api_file.get("url"):
        raise RuntimeError("Pinned Fabric API version has no downloadable JAR")
    artifacts["fabric_api"] = download(
        api_file["url"], output / "fabric-api-0.141.6+1.21.11.jar",
        api_file.get("hashes", {}).get("sha1")
    )

    provenance = {
        "test_scope": "official Minecraft 26.2 client, Fabric Loader, Fabric API and a production 1.21.11 mod",
        "warning": (
            "The CI exercises startup/loading only; it does not prove the selected mod's "
            "in-game feature or universal legacy-mod compatibility."
        ),
        "modrinth_version_id": MODRINTH_VERSION_ID,
        "mod_name": mod_version.get("name"),
        "mod_version_number": mod_version.get("version_number"),
        "minecraft_target_version": target_version.get("id"),
        "minecraft_legacy_version": legacy_version.get("id"),
        "artifacts": artifacts,
    }
    provenance_file = output / "fixture-metadata.json"
    provenance_file.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")

    env_file = os.environ.get("GITHUB_ENV")
    if env_file:
        variables = {
            "GAFI_REAL_TARGET_GAME": artifacts["target_game"]["path"],
            "GAFI_REAL_LEGACY_GAME": artifacts["legacy_game"]["path"],
            "GAFI_REAL_LEGACY_MOD": artifacts["legacy_mod"]["path"],
            "GAFI_REAL_FABRIC_API": artifacts["fabric_api"]["path"],
            "GAFI_REAL_INTERMEDIARY_MAPPINGS": artifacts["intermediary_mappings"]["path"],
            "GAFI_REAL_LEGACY_MAPPINGS": artifacts["legacy_mappings"]["path"],
            "GAFI_REAL_FIXTURE_METADATA": str(provenance_file),
        }
        with open(env_file, "a", encoding="utf-8") as stream:
            for key, value in variables.items():
                stream.write(f"{key}={value}\n")

    print(f"Prepared real compatibility fixture: {mod_version.get('name')} {mod_version.get('version_number')}")
    for name, artifact in artifacts.items():
        size = Path(artifact["path"]).stat().st_size
        print(f"  {name}: {size:,} bytes, SHA-1 {artifact['sha1']}")
    print(f"  provenance: {provenance_file}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"real-artifact fixture error: {error}", file=sys.stderr)
        raise
