# Gafi Loader

**Two versions. One Minecraft.**

Gafi Loader is an experimental compatibility project targeting **Minecraft Java Edition 26.2** as the game that launches, with the goal of making mods built for **Minecraft 1.21.11** work alongside 26.2 mods without modifying the original mod JARs.

> **Current status: compatibility-analysis milestone.** The repository was empty at project start. This first implementation is a command-line scanner that compares Minecraft API references in legacy mod JARs with the 26.2 game JAR. It is a development tool, **not yet a loader capable of loading 1.21.11 mods**. That distinction is intentional: compatibility rules should be based on measured differences and repeatable tests, not guesses.

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

This packaging step is for the current diagnostic utility; it is **not a finished Minecraft compatibility loader**. Do not use the EXE as evidence that Fabric API 1.21.11 is now supported by Minecraft 26.2.

## Bypass the Fabric metadata version gate (diagnostic step)

Fabric Loader supports `config/fabric_loader_dependencies.json` to override a mod's declared dependencies. Gafi Loader can generate this file for mods whose `fabric.mod.json` explicitly declares Minecraft **1.21.11**. It scans top-level mod JARs and nested JARs (including Fabric API modules), leaves the original JARs untouched, merges entries into an existing override file rather than replacing unrelated overrides, saves an existing config as `fabric_loader_dependencies.json.bak`, and writes the replacement atomically.

~~~powershell
.\gradlew.bat run --args="--prepare-legacy-overrides --mods C:/Users/you/AppData/Roaming/.minecraft/mods --config C:/Users/you/AppData/Roaming/.minecraft/config/fabric_loader_dependencies.json"
~~~

Replace `you` with your Windows account folder, and use the actual game directory if the 26.2 instance has a separate game directory. The command requires the project's JDK 25 development setup; this is not yet exposed as a button in the GafiLoader installer.

Run this only against the mods folder for the **26.2 Fabric instance**. Close the game first. The generator only removes the declared `minecraft` dependency gate for mod IDs that advertise 1.21.11; it does not remove requirements for Fabric Loader or other mods. The format follows the [official Fabric dependency override documentation](https://docs.fabricmc.net/players/troubleshooting/dependency-overrides/).

**Important:** this is a diagnostic workaround, not the compatibility layer. It may get past the initial “wrong Minecraft version” rejection, but it does not make 1.21.11 bytecode, mixins, mappings, or Fabric API calls compatible with 26.2. If startup proceeds further and then crashes, that is a separate binary/API compatibility failure which requires a runtime adapter and testing. The generator does not claim that Fabric API 1.21.11 will successfully run on 26.2.

## Legacy bytecode remapping preparation

The project now has an experimental remapping command for mods whose metadata declares Minecraft 1.21.11. It maps the old Intermediary namespace through the 1.21.11 Fabric Intermediary artifact and official Mojang mappings into the named namespace used by the 26.2 game. It creates a **separate output directory**, keeps original JARs unchanged, copies mods not marked as 1.21.11 without remapping, and remaps nested JARs under `META-INF/jars/` in legacy bundles such as Fabric API.

~~~powershell
.gradlew.bat run --args="--remap-legacy-mods --mods C:/path/to/original-mods --intermediary-mappings C:/path/to/intermediary-1.21.11-v2.jar --legacy-mappings C:/path/to/1.21.11-client-mappings.txt --game C:/path/to/26.2-client.jar --output C:/path/to/remapped-mods"
~~~

This command is an experimental **bytecode preparation stage**, not a runtime loader and not proof that Fabric API 1.21.11 works on 26.2. Name remapping cannot recreate removed methods, changed method semantics, incompatible mixin injections, access wideners, or Fabric Loader internals. The output can still fail at launch; use the scanner/API report to identify missing symbols and add only reviewed aliases whose descriptors remain compatible. Do not put original and remapped copies together in the same active mods folder.

The separate metadata override step may still be needed for Fabric Loader's Minecraft version dependency check. Run it against the mod directory used by the 26.2 instance and its actual `config/fabric_loader_dependencies.json` path. Bypassing the metadata gate and remapping bytecode solve different problems.

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

To also detect removed or descriptor-changed references to the **public Fabric API**, pass the Fabric API JAR built for Minecraft 26.2. Gafi Loader indexes its nested `META-INF/jars/` modules as well as classes at the archive root:

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
- The scanner currently focuses on Minecraft-owned symbols; Fabric Loader and Fabric API compatibility require separate analysis.
- It does not modify the launcher, Fabric Loader, or mod JARs and does not yet make legacy mods load.
- A 26.2 JAR from the official launcher and real mod JARs are required for end-to-end validation.

## Development approach

Every compatibility rule should have a regression test and a clear explanation of the symbols/behavior it adapts. The compatibility engine must fail clearly when it cannot safely adapt a legacy reference rather than silently claiming support.

## License

MIT. See LICENSE.
