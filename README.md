# Gafi Loader

**Two versions. One Minecraft.**

Gafi Loader is an experimental compatibility project targeting **Minecraft Java Edition 26.2** as the game that launches, with the goal of making mods built for **Minecraft 1.21.11** work alongside 26.2 mods without modifying the original mod JARs.

> **Current status: experimental launch-time compatibility pipeline.** After setup, the dedicated `gafiloader-26.2` profile runs a bootstrap before Fabric Loader. On every launch it scans the active mods directory, applies Fabric dependency overrides for detected 1.21.11 mods, loads the 1.21.11 Intermediary/Mojang mappings, and prepares staged copies when a supported transformation is needed. The current automatic pass remaps legacy class/descriptor references and Mixin selectors/shadow/accessor declarations, rewrites Intermediary ClassTweaker resources, redirects the known `EntityType.PLAYER` reference, and adapts selected Fabric model-loading classes. This is still incomplete: it does not implement every removed or behaviorally changed Minecraft/Fabric API, and passing preflight is not proof that a mod will load or work in-game.

## Goals

- Keep Minecraft **26.2** as the launched game version.
- Target only legacy mods built for **1.21.11**; no general promise to support every historical version.
- Never rewrite the original mod JARs.
- Prefer compatibility transformations prepared during discovery/start-up rather than repeated work during gameplay.
- Automate API-difference discovery, and add only tested compatibility shims.
- Keep the Fabric ecosystem as the base rather than implementing a new mod ecosystem from scratch.

## Windows build artifact

Every push to `main` runs tests on Windows and Linux. The Windows job is configured to publish two GitHub Actions artifacts: `GafiLoader-Installer-Windows-x64` (an installer EXE with a bundled Java runtime) and `GafiLoader-Windows-x64` (the portable application image).

To retrieve them, open the repository's **Actions** tab, open the latest successful **Build and test** run, and download the artifact from its Artifacts section. The installer uses a stable upgrade identifier and an increasing build version so future installers produced by this workflow can upgrade installations produced by the same workflow.

The Windows installer EXE only installs the GafiLoader application (including its bundled Java runtime and Start Menu shortcut). After it finishes, launch **GafiLoader** from the Windows Start Menu once; that application creates a separate Minecraft Launcher installation named **GafiLoader 26.2**. It derives the version JSON from an already installed Fabric profile targeting Minecraft 26.2, writes it under `.minecraft/versions/gafiloader-26.2/`, and registers `gafiloader-26.2` in `launcher_profiles_microsoft_store.json` for the Microsoft Store launcher, or `launcher_profiles.json` for the standard launcher. Before changing the profile, it validates the embedded bootstrap JAR's manifest, version, and required classes. The source Fabric profile, vanilla profiles, original mod JARs, and launcher executable are not modified. Close the Minecraft Launcher completely before running GafiLoader, then reopen it to refresh the Installations list. Setup diagnostics are saved at `.minecraft/logs/gafiloader-setup.log`. The setup log also records how the Minecraft data root was selected. By default GafiLoader prefers `%APPDATA%\.minecraft`; if your launcher uses another data root, set the `GAFILOADER_MINECRAFT_DIR` environment variable to that directory before running the app. When the default root has no valid launcher profile file, it checks a short list of well-known fallback locations rather than silently writing to a guessed root.

Before registering the installation, GafiLoader validates that the dedicated version inherits from Minecraft 26.2, checks the bootstrap JAR's manifest/version/required class-file headers, and rechecks the launcher profile files before committing its changes so it will not knowingly overwrite a profile that changed during setup. After a successful registration, it removes only orphaned old bootstrap folders under `libraries/dev/gafipro/gafiloader-bootstrap/` that are not referenced by any readable version JSON or GafiLoader backup. It preserves referenced versions, backups, mods, worlds, unrelated libraries, and all original Fabric/vanilla profiles. On Windows, the installer uses a stable per-user upgrade identifier, so a newly built installer upgrades the existing GafiLoader application installation; do not manually delete its running program folder.

If no Fabric profile for Minecraft 26.2 exists yet, GafiLoader stops with an explicit message and makes no new profile/loader files. Install Fabric for Minecraft 26.2 once using the [official Fabric installation instructions](https://wiki.fabricmc.net/install), close the launcher, and rerun GafiLoader. The bootstrap then performs the automatic compatibility preparation described below. Keep the original 1.21.11 JARs in the active mods folder; GafiLoader stages transformed copies under the dedicated game directory when required.

## Automatic pre-launch version-gate override

The bootstrap deliberately does **not** embed ASM. Minecraft 26.2 already supplies ASM on the game classpath; bundling another copy causes Fabric Loader's classpath validation to abort with a duplicate-ASM error. The compatibility transformer uses the ASM version supplied by the launched game.


Fabric Loader reads `config/fabric_loader_dependencies.json` during mod resolution. The GafiLoader bootstrap now creates/merges the override file **before it calls Fabric Loader**, which avoids the previous ordering problem where running the utility separately was required.

1. Install the application using `GafiLoader-Installer-Windows-x64.exe` (the installer does not itself register a Minecraft profile). Fully close Minecraft Launcher, then launch **GafiLoader** from the Windows Start Menu. The application creates `.minecraft/versions/gafiloader-26.2/gafiloader-26.2.json` from an existing Fabric 26.2 version, installs a small bootstrap JAR in `.minecraft/libraries/`, sets the bootstrap as the entrypoint, and registers a **new launcher installation** named `GafiLoader 26.2`. Wait for the console to report success before closing it. If that dedicated version already exists, setup updates it without duplicating the launcher entry. When modifying an existing GafiLoader version JSON, the prior version JSON is backed up as `gafiloader-26.2.json.gafiloader-backup`.
2. Reopen the official Minecraft Launcher and find **GafiLoader 26.2** in the **Installations** tab; select it on the Play screen. Do not launch the vanilla 26.2 profile if you expect GafiLoader's preflight to run.
3. Before Fabric resolves mods, the bootstrap reads the real `--gameDir` launch argument and honors Fabric's `fabric.modsFolder` setting when configured. If the launcher omits `--gameDir`, it checks the GafiLoader entry in `launcher_profiles.json` / `launcher_profiles_microsoft_store.json` for its configured `gameDir`, then checks for a recognizable `GafiLoader 26.2` game folder before falling back to `.minecraft`. It scans that mods folder (including nested API modules) and validates the generated `config/fabric_loader_dependencies.json` before handing control to Fabric. The original mods are never modified. A persistent preflight report is written to `logs/gafiloader-preflight.log` inside the active game directory; it records the actual paths, Java version, scan counts, overrides, and errors.

The launcher's **data root** (which stores `versions/` and `libraries/`) remains separate from the per-installation **game directory** (which stores `mods/`, `config/`, `saves/`, and logs). GafiLoader detects the latter for dependency overrides and preflight logs instead of assuming it is always `.minecraft`.

The override removes only the declared `minecraft` dependency for metadata that advertises 1.21.11; it leaves Fabric Loader, other required mods, and conflict rules alone. Existing overrides are merged and the previous config is backed up as `fabric_loader_dependencies.json.bak`. This follows the [official Fabric dependency override format](https://docs.fabricmc.net/players/troubleshooting/dependency-overrides/).

The GafiLoader setup application does **not** pre-write this shared config. The launch bootstrap snapshots the existing file, writes overrides before Fabric's mod resolution, then restores the original bytes when the game exits or the JVM shuts down, but only if the file still exactly matches the version GafiLoader generated. If another process or the user changes the file during the session, GafiLoader leaves that changed file untouched and logs a warning. A force-terminated process or power loss can prevent shutdown cleanup, so this remains a safety measure rather than a guarantee against every abrupt termination.

**Legacy ClassTweaker namespace bridge:** on Minecraft 26.2 the runtime namespace is `official`, while 1.21.11 Fabric API modules may contain ClassTweaker resources in `intermediary`. When such a resource is found, the bootstrap retrieves Mojang's 1.21.11 client mappings and Fabric's 1.21.11 Intermediary mappings, verifies the Mojang mapping SHA-1, caches both under `.gafiloader/mappings/1.21.11`, and rewrites the ClassTweaker header, class names, member names and bytecode descriptors in nested staged JAR copies. Original mod JARs are never altered. If mappings cannot be retrieved or a target has no mapping, preflight stops with a diagnostic instead of passing an invalid namespace to Fabric.

**Important limitation:** passing preflight only means that the metadata and transformations currently implemented were prepared. The remapper handles known 1.21.11 namespace references and supported Mixin metadata patterns; the API adapters are targeted, not general. Missing classes, changed descriptors, changed method behavior, unsupported Mixin patterns, access rules, Fabric internals, and resource-based selectors can still make a mod incompatible. A preflight failure stops before Fabric starts and is recorded in `logs/gafiloader-preflight.log`; a successful preflight is not a runtime compatibility guarantee.

## Legacy bytecode remapping preparation

The project now has an experimental remapping command for mods whose metadata declares Minecraft 1.21.11. It maps the old Intermediary namespace through the 1.21.11 Fabric Intermediary artifact and official Mojang mappings into the named namespace used by the 26.2 game. It creates a **separate output directory**, keeps original JARs unchanged, copies mods not marked as 1.21.11 without remapping, and remaps nested JARs under `META-INF/jars/` in legacy bundles such as Fabric API.

~~~powershell
.gradlew.bat run --args="--remap-legacy-mods --mods C:/path/to/original-mods --intermediary-mappings C:/path/to/intermediary-1.21.11-v2.jar --legacy-mappings C:/path/to/1.21.11-client-mappings.txt --game C:/path/to/26.2-client.jar --output C:/path/to/remapped-mods"
~~~

This command is a separate experimental **TinyRemapper preparation tool** for producing an entire remapped output directory. It is not the same path as the launch bootstrap's targeted automatic compatibility pass, and it is not proof that Fabric API 1.21.11 works on 26.2. Neither namespace remapping nor the automatic Mixin adapter can recreate removed methods, changed method semantics, unsupported injections, access rules, or Fabric Loader internals. The output can still fail at launch; use the scanner/API report to identify missing symbols and add only reviewed aliases whose descriptors and behavior are compatible. Do not put original and manually remapped copies together in the same active mods directory.

The launch-time bootstrap also prepares staged copies under `.gafiloader/compat-mods` when a supported transformation is detected, including top-level and nested JARs. The automatic pass includes:

- Intermediary-to-Mojang class and descriptor remapping for detected 1.21.11 mods.
- Mixin class targets, selector strings, `@Shadow`/`@Overwrite` declarations, inferred `@Accessor`/`@Invoker` names, and supported `@At` targets.
- Intermediary ClassTweaker/access-widener resource conversion into the runtime namespace.
- A targeted rewrite of `EntityType.PLAYER` (`class_1299.field_6097`) to `EntityTypes.PLAYER`.
- A module-scoped Fabric model-loading adapter that ports selected 1.21.11 `BlockModel` hooks to the 26.2 `CuboidModel` implementation.

The original mod JARs remain byte-for-byte unchanged; modified archive copies have obsolete signature files removed. Compatibility preparation now rejects unresolvable legacy class mappings and archives that exceed the configured scan-depth or size limits, rather than silently launching with a partially transformed legacy archive. These adapters cover known cases only. Other removed members, changed descriptors or semantics, unhandled Mixin patterns, resource-based selectors, registries, and Fabric API behavior still require separate compatibility work and real-game testing. The broader TinyRemapper command above remains a separate experimental manual tool.

## Compatibility scanner

The scanner indexes classes, methods, and fields from a Minecraft 26.2 JAR, inspects mod JAR bytecode, and reports references to net/minecraft symbols that are missing from the target index.

### Requirements

- JDK 25
- Gradle 9.5.1 or newer compatible with the project

### Build and test

~~~bash
./gradlew clean test
./gradlew build
~~~

### Run

Scan mod references against the 26.2 target JAR:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --mods /path/to/mods --report build/mod-scan.json"
~~~

To also detect removed or descriptor-changed references to the **public Fabric API**, pass the Fabric API JAR built for Minecraft 26.2. The scanner inspects classes in legacy mod JARs and their nested `META-INF/jars/` modules, and indexes the target API archive's nested modules as well as classes at its root:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --mods /path/to/1.21.11-mods --target-api /path/to/fabric-api-26.2.jar --report build/mod-scan.json"
~~~

Repeat `--target-api` to add more target API JARs. This checks static class/method/field references in `net.fabricmc.fabric.api`; it is still a diagnostic, not a patcher, and cannot prove mixins, runtime behavior, or private Fabric implementation details are compatible. The report records which target API JARs were indexed.

Compare the obfuscated 1.21.11 client JAR with the unobfuscated 26.2 target:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --legacy-game /path/to/1.21.11-client.jar --legacy-mappings /path/to/1.21.11-client-mappings.txt --api-report build/api-diff.json"
~~~

You can use both `--mods` and `--legacy-game` in the same invocation to run both reports. On Windows, use `gradlew.bat` instead of `./gradlew` and quote paths containing spaces. The `--mods` argument accepts one JAR or a directory containing JARs. Reviewed aliases can be supplied with `--aliases aliases.tsv`; see [aliases.example.tsv](aliases.example.tsv) for the tab-separated format.

Scan production Fabric mods compiled in the 1.21.11 Intermediary namespace. This composes the official Mojang mappings with Fabric's Intermediary mappings so the old symbols can be compared to 26.2 names:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --mods /path/to/mods --intermediary-mappings /path/to/intermediary-1.21.11-v2.jar --legacy-mappings /path/to/1.21.11-client-mappings.txt --report build/mod-scan.json"
~~~

The Intermediary mapping artifact is available from [Fabric Maven](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.11/intermediary-1.21.11-v2.jar). The `--legacy-mappings` input is still the official Mojang client mappings text for 1.21.11. Supplying both mapping files lets Gafi Loader translate a mod's Intermediary class, member, and descriptor references into Mojang names before checking the 26.2 target. Explicit TSV aliases are applied after this namespace normalization.

The mappings file must be the **official Mojang client mappings for 1.21.11**, in ProGuard text format. Minecraft 1.21.11 is obfuscated while 26.1+ is unobfuscated, so comparing the raw class names would produce misleading results. Fabric documents this version boundary and the move to unobfuscated names in its [mapping migration guide](https://docs.fabricmc.net/develop/porting/mappings/).

~~~text
Gafi Loader compatibility scanner

Usage:
  --game <26.2-game.jar>       Required target game JAR
  --mods <jar-or-directory>    Required legacy mod JAR or directory of JARs
  --report <report.json>       Optional machine-readable report
  --help                       Show help
~~~

### Reading the report

The scanner reports missing classes, methods, and fields referenced by the mod bytecode. A missing symbol is a **candidate incompatibility**, not proof that a mod will crash: references may be optional, dynamically reached, or depend on namespaces/mappings that have not yet been normalized. Conversely, a clean report is not proof of runtime compatibility. The report is intended to guide the next stage: namespace normalization, old-to-new symbol mapping, and tested compatibility transformations.

## Current limitations

- The API comparator uses official 1.21.11 Mojang mappings to normalize the old game JAR. It reports class/member differences and possible renames, but does not yet infer or apply semantic replacements.
- The mod scanner can normalize production Intermediary references when the 1.21.11 Intermediary mapping artifact and official Mojang mappings are supplied. It does not yet normalize Yarn-named development JARs or every custom namespace. Explicit TSV aliases can normalize known API changes, but each rule must be verified against the 26.2 API.
- The scanner checks Minecraft-owned symbols and can also check public `net.fabricmc.fabric.api` symbols when a target API JAR is supplied. It does not model Fabric Loader internals or private API implementation details.
- The installer changes only the dedicated `gafiloader-26.2` version JSON so its bootstrap runs before Fabric Loader. It leaves the launcher executable, Fabric Loader JARs, and original mod JARs untouched. The bootstrap creates staged copies when supported transformations are needed, but full runtime compatibility is not yet complete.
- A 26.2 JAR from the official launcher and real mod JARs are required for end-to-end validation.

## Development approach

Every compatibility rule should have a regression test and a clear explanation of the symbols/behavior it adapts. The compatibility engine must fail clearly when it cannot safely adapt a legacy reference rather than silently claiming support.

## License

MIT. See LICENSE.
