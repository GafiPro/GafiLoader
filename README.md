# Gafi Loader

**Two versions. One Minecraft.**

Gafi Loader is an experimental compatibility project targeting **Minecraft Java Edition 26.2** as the game that launches, with the goal of making mods built for **Minecraft 1.21.11** work alongside 26.2 mods without modifying the original mod JARs.

> **Current status: launch-time dependency-gate workaround + compatibility analysis.** After you run the GafiLoader application once, it patches only the dedicated `gafiloader-26.2` version profile to run a small bootstrap before Fabric Loader. On every launch, the bootstrap scans the actual game directory and removes the declared Minecraft-version gate for mods whose metadata targets **1.21.11**. This lets Fabric get past that *first* rejection; it does **not** recreate removed Minecraft/Fabric APIs or guarantee that a legacy mod will finish loading.

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

The EXE creates a separate Minecraft Launcher installation named **GafiLoader 26.2**. It derives the version JSON from an already installed Fabric profile targeting Minecraft 26.2, writes it under `.minecraft/versions/gafiloader-26.2/`, and registers `gafiloader-26.2` in `launcher_profiles.json` or `launcher_profiles_microsoft_store.json` (whichever exists). The source Fabric profile, vanilla profiles, original mod JARs, and launcher executable are not modified. Close the Minecraft Launcher completely before running GafiLoader, then reopen it to refresh the Installations list.

If no Fabric profile for Minecraft 26.2 exists yet, GafiLoader stops with an explicit message and makes no new profile/loader files. Install Fabric for Minecraft 26.2 once using the [official Fabric installation instructions](https://wiki.fabricmc.net/install), close the launcher, and rerun GafiLoader. The bootstrap's scope is still the metadata rejection; API, mappings, and Mixin compatibility remain separate work.

## Automatic pre-launch version-gate override

Fabric Loader reads `config/fabric_loader_dependencies.json` during mod resolution. The GafiLoader bootstrap now creates/merges the override file **before it calls Fabric Loader**, which avoids the previous ordering problem where running the utility separately was required.

1. Fully close Minecraft Launcher. Run the GafiLoader EXE once. It creates `.minecraft/versions/gafiloader-26.2/gafiloader-26.2.json` from an existing Fabric 26.2 version, installs a small bootstrap JAR in `.minecraft/libraries/`, sets the bootstrap as the entrypoint, and registers a **new launcher installation** named `GafiLoader 26.2`. If that dedicated version already exists, setup updates it without duplicating the launcher entry. When modifying an existing GafiLoader version JSON, the prior version JSON is backed up as `gafiloader-26.2.json.gafiloader-backup`.
2. Reopen the official Minecraft Launcher and find **GafiLoader 26.2** in the **Installations** tab; select it on the Play screen. Do not launch the vanilla 26.2 profile if you expect GafiLoader's preflight to run.
3. Before Fabric resolves mods, the bootstrap reads the real `--gameDir` launch argument, scans that instance's `mods` folder (including nested API modules), and updates `config/fabric_loader_dependencies.json`. The original mods are never modified.

The override removes only the declared `minecraft` dependency for metadata that advertises 1.21.11; it leaves Fabric Loader, other required mods, and conflict rules alone. Existing overrides are merged and the previous config is backed up as `fabric_loader_dependencies.json.bak`. This follows the [official Fabric dependency override format](https://docs.fabricmc.net/players/troubleshooting/dependency-overrides/).

The GafiLoader setup application does **not** pre-write this shared config. The launch bootstrap snapshots the existing file, writes overrides before Fabric's mod resolution, then restores the original bytes when the game exits or the JVM shuts down, but only if the file still exactly matches the version GafiLoader generated. If another process or the user changes the file during the session, GafiLoader leaves that changed file untouched and logs a warning. A force-terminated process or power loss can prevent shutdown cleanup, so this remains a safety measure rather than a guarantee against every abrupt termination.

**Important limitation:** this makes Fabric get past the early “wrong Minecraft version” gate for detected 1.21.11 mods. It does not make old bytecode, removed classes/methods, Fabric API behavior, mappings, Mixins, or access wideners compatible with 26.2. A later crash is a separate compatibility problem. If the bootstrap cannot write the override config, it stops before launching Fabric and reports the config path rather than silently continuing into the same incompatibility screen.

## Legacy bytecode remapping preparation

The project now has an experimental remapping command for mods whose metadata declares Minecraft 1.21.11. It maps the old Intermediary namespace through the 1.21.11 Fabric Intermediary artifact and official Mojang mappings into the named namespace used by the 26.2 game. It creates a **separate output directory**, keeps original JARs unchanged, copies mods not marked as 1.21.11 without remapping, and remaps nested JARs under `META-INF/jars/` in legacy bundles such as Fabric API.

~~~powershell
.gradlew.bat run --args="--remap-legacy-mods --mods C:/path/to/original-mods --intermediary-mappings C:/path/to/intermediary-1.21.11-v2.jar --legacy-mappings C:/path/to/1.21.11-client-mappings.txt --game C:/path/to/26.2-client.jar --output C:/path/to/remapped-mods"
~~~

This command is an experimental **bytecode preparation stage**, not a runtime loader and not proof that Fabric API 1.21.11 works on 26.2. Name remapping cannot recreate removed methods, changed method semantics, incompatible mixin injections, access wideners, or Fabric Loader internals. The output can still fail at launch; use the scanner/API report to identify missing symbols and add only reviewed aliases whose descriptors remain compatible. Do not put original and remapped copies together in the same active mods folder.

The launch-time bootstrap handles the metadata override automatically. Bytecode remapping is still a separate experimental step, and is not automatically applied by the launch bootstrap.

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
- The installer changes only the dedicated `gafiloader-26.2` version JSON so its bootstrap runs before Fabric Loader. It leaves the launcher executable, Fabric Loader JARs, and mod JARs untouched. The metadata gate is bypassed, but full runtime compatibility is not yet complete.
- A 26.2 JAR from the official launcher and real mod JARs are required for end-to-end validation.

## Development approach

Every compatibility rule should have a regression test and a clear explanation of the symbols/behavior it adapts. The compatibility engine must fail clearly when it cannot safely adapt a legacy reference rather than silently claiming support.

## License

MIT. See LICENSE.
