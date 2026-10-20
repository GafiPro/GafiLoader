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

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --mods /path/to/mods"
~~~

On Windows, use gradlew.bat instead of ./gradlew and quote paths containing spaces. The --mods argument can be one JAR or a directory containing JARs. An optional JSON report can be saved with --report report.json. Reviewed aliases can be supplied with --aliases aliases.tsv; see [aliases.example.tsv](aliases.example.tsv) for the tab-separated format.

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

- Direct symbol comparison cannot infer arbitrary class or member renames between versions. Explicit TSV aliases can normalize known changes, but each rule must be verified against the 26.2 API.
- The scanner currently focuses on Minecraft-owned symbols; Fabric Loader and Fabric API compatibility require separate analysis.
- It does not modify the launcher, Fabric Loader, or mod JARs and does not yet make legacy mods load.
- A 26.2 JAR from the official launcher and real mod JARs are required for end-to-end validation.

## Development approach

Every compatibility rule should have a regression test and a clear explanation of the symbols/behavior it adapts. The compatibility engine must fail clearly when it cannot safely adapt a legacy reference rather than silently claiming support.

## License

MIT. See LICENSE.
