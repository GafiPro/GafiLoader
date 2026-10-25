# Diagnosing the Fabric API 1.21.11 version gate on Minecraft 26.2

## Purpose

This procedure isolates a metadata/dependency rejection from a binary/API incompatibility. It does **not** make 1.21.11 bytecode compatible with 26.2 and is not a substitute for the GafiLoader runtime compatibility layer.

## Why this matters

Minecraft 1.21.11 is the last obfuscated release; 26.1 and later are unobfuscated and use Mojang names. A legacy Fabric mod can therefore pass metadata validation and still fail later because its bytecode references old Intermediary/Yarn names, removed methods, changed descriptors, mixin targets, or Fabric API contracts. See the [Fabric mapping migration guide](https://docs.fabricmc.net/develop/porting/mappings/) and [dependency override documentation](https://docs.fabricmc.net/players/troubleshooting/dependency-overrides).

## Isolate the metadata gate

1. Back up the instance and its worlds before testing.
2. Confirm the exact failing mod ID and the dependency error in `logs/latest.log`. Do not assume the failing ID is `fabric-api`; a dependency of Fabric API may be the actual rejection.
3. If the log specifically says a mod's declared `minecraft` dependency excludes `26.2`, Fabric Loader supports dependency overrides in `config/fabric_loader_dependencies.json`. Use that only as a temporary diagnostic, and override only the named mod's `depends.minecraft` entry.
4. Launch once and capture the **first** subsequent error. If the game proceeds past dependency resolution but fails with `ClassNotFoundException`, `NoClassDefFoundError`, `NoSuchMethodError`, a Mixin injection failure, or a linkage error, the metadata gate was not the only problem. Restore the original config after the test.

Do not remove all dependency constraints or broadly override every mod. That can hide real dependency conflicts and make the crash harder to diagnose.

## GafiLoader implementation requirements

A real fix needs separate stages:

- Read and validate `fabric.mod.json` metadata without modifying the source JAR.
- Resolve legacy Minecraft/Fabric API dependencies under an explicit compatibility policy, reporting any override.
- Normalize old bytecode references using the correct 1.21.11 mapping namespace.
- Apply only reviewed, tested symbol aliases and runtime shims.
- Validate mixin targets and descriptors before claiming a mod is supported.
- Keep metadata acceptance separate from binary compatibility in logs and reports.

The current repository's compatibility scanner is a diagnostic tool; it is not yet a Fabric Loader integration and does not launch legacy mods. A generated installer must not be advertised as a working legacy-mod loader until a real 26.2 instance passes an end-to-end test with the Fabric API 1.21.11 JAR.
