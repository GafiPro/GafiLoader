# Gafi Loader

**Two versions. One Minecraft.**

Gafi Loader is an experimental compatibility project targeting **Minecraft Java Edition 26.2** as the game that launches, with the goal of making mods built for **Minecraft 1.21.11** work alongside 26.2 mods without modifying the original mod JARs.

> **Current status: experimental runtime-bridge milestone.** Gafi Loader has an API scanner, an offline patcher for a copy of the 26.2 game JAR, and a Java agent that injects explicitly reviewed same-descriptor method aliases into game classes as they load. It also remaps direct Intermediary bytecode references at class definition and during Mixin metadata inspection, rewrites recognized Mixin configuration/refmap JSON in memory, and generates Fabric dependency overrides for selected legacy mods. The original mod JARs are not rewritten. The agent also has target-verified in-memory structural bridges for the removed `Minecraft.setScreen(Screen)`, `DisplaySlot.teamColorToSlot(ChatFormatting)`, and `ReadOnlyScoreInfo.safeFormatValue(...)` APIs. This is **not yet a complete 1.21.11 mod loader**: no real-mod end-to-end launch is verified, and the `TimerQueue` serialization redesign, removed loot score-provider APIs, arbitrary reflection strings, and Fabric API behavior changes remain unsupported.

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

The build produces a self-contained executable JAR at
`build/libs/gafi-loader-0.1.0-SNAPSHOT.jar`. It can be used as a Java agent before
Minecraft starts defining classes.

### Run

Scan mod references against the 26.2 target JAR:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --mods /path/to/mods --report build/mod-scan.json"
~~~

Compare the obfuscated 1.21.11 client JAR with the unobfuscated 26.2 target and export review-only method rename candidates:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --legacy-game /path/to/1.21.11-client.jar --legacy-mappings /path/to/1.21.11-client-mappings.txt --api-report build/api-diff.json --alias-candidates build/method-alias-candidates.tsv"
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

The optional `--alias-candidates` output is a **review-only TSV**. It includes only same-descriptor method name candidates and labels them `CANDIDATE` (never `METHOD_ALIAS`), so it cannot be fed directly to the patcher. A unique candidate is still just a name/descriptor match, not proof of equivalent behavior. Check the real 26.2 implementation before manually promoting any row into `compatibility-rules.tsv`.

### Reading the report

The scanner reports missing classes, methods, and fields referenced by the mod bytecode. A missing symbol is a **candidate incompatibility**, not proof that a mod will crash: references may be optional, dynamically reached, or depend on namespaces/mappings that have not yet been normalized. Conversely, a clean report is not proof of runtime compatibility. The report is intended to guide the next stage: namespace normalization, old-to-new symbol mapping, and tested compatibility transformations.

The API comparator also checks whether methods and fields changed between static and instance form. Those differences are reported separately rather than exported as ordinary rename candidates, because the wrong invocation kind can crash at linkage time.

## Fabric dependency-version overrides

Fabric Loader supports a config file named `fabric_loader_dependencies.json` to override a mod's
declared dependency requirements. Gafi Loader can generate or merge the relevant entries for the
legacy Fabric mod JARs you select; it does not rewrite their contents. This only removes those mods'
required `minecraft` version constraint so Fabric will attempt to load them. It does **not** make
the code compatible by itself. See the [official Fabric dependency override guide](https://docs.fabricmc.net/players/troubleshooting/dependency-overrides).

Put only the 1.21.11 Fabric mods that you explicitly want to try in a separate directory, then run:

~~~bash
./gradlew run --args="--mods /path/to/selected-legacy-mods --generate-dependency-overrides /path/to/.minecraft/config/fabric_loader_dependencies.json"
~~~

This mode does not require `--game`. If the config already exists, Gafi Loader preserves other mod
overrides and adds the selected IDs. It stops rather than silently overwriting a conflicting rule for
the same mod. Back up your config before changing it. Only use this for mods you intend to test against
26.2; removing the version check is not proof they are compatible.

## Experimental Intermediary bytecode remapping

Production Fabric mods from 1.21.11 commonly reference classes and members in Fabric's
**Intermediary** namespace. Gafi Loader can now translate direct class, field, method and descriptor
references into the runtime's Mojang names **in memory as classes are defined**. The original mod JAR
is not rewritten. The same agent also supports optional game-side method aliases.

Download the immutable 1.21.11 mapping inputs:
- [Fabric Intermediary 1.21.11](https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.11/intermediary-1.21.11-v2.jar)
- [Official Mojang 1.21.11 client mappings](https://piston-data.mojang.com/v1/objects/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt)

Copy `gafi-loader-agent.example.properties` and update the mapping paths. Paths can be absolute or
relative to the properties file:

~~~properties
intermediaryMappings=intermediary-1.21.11-v2.jar
legacyMappings=1.21.11-client-mappings.txt
# Required for target-verified structural adapters and recommended for alias validation:
targetGameJar=/path/to/.minecraft/versions/26.2/26.2.jar
# Optional: review-only, same-descriptor CLASS / METHOD / FIELD aliases applied to mod bytecode:
# bytecodeAliases=runtime-bytecode-aliases.tsv
# Optional: game-side method bridges for verified API differences:
# rules=compatibility-rules.tsv
~~~

Build Gafi Loader, then pass the properties file as the Java agent argument in the Minecraft
installation's JVM arguments:

~~~text
-javaagent:/path/to/gafi-loader-0.1.0-SNAPSHOT.jar=/path/to/gafi-loader-agent.properties
~~~

The mappings are specifically for legacy 1.21.11 Intermediary bytecode. They do not support Yarn-named
development builds or arbitrary mapping namespaces. This prototype remaps direct JVM references,
descriptors, handles, signatures and bytecode type references. The `targetGameJar` setting points to the actual 26.2 client JAR. It enables target-verified structural adapters, including the in-memory rewrite of legacy `Minecraft.setScreen(Screen)` calls to `Minecraft.gui.setScreen(Screen)` and the `DisplaySlot.teamColorToSlot` / `ReadOnlyScoreInfo.safeFormatValue` compatibility bridges, only when the target APIs required by each adapter exist. The optional `bytecodeAliases` file uses the same tab-separated `CLASS`, `METHOD` and `FIELD` format as `aliases.example.tsv`; rules are applied after Intermediary normalization and let you describe reviewed, same-descriptor renames from the 1.21.11 named symbols to 26.2 runtime symbols. Gafi Loader checks that each target class/member actually exists in the supplied 26.2 JAR and rejects aliases that change static/instance call shape. Descriptor-changing rules are rejected.
The agent hooks `MixinServiceKnot.getResourceAsStream` for recognized Mixin config/refmap JSON and `getClassBytes` for bytecode that Mixin requests during metadata inspection. That lets Gafi Loader normalize direct class/member references before Mixin reads the old bytecode, in addition to the regular JVM definition-time transformation. The hooks are pinned to the current Fabric Loader signatures and fail clearly if the expected methods are missing. CI downloads the released Fabric Loader 0.20.1 JAR and applies the patcher to its actual `MixinServiceKnot` class, checking that both hooks are present. This validates the binary interception points; it is not a full Minecraft 26.2 launch. The resource adapter also recognizes `.class` resources fetched through that boundary. This remains a narrow bridge: it does **not** rewrite arbitrary reflection strings or unrelated resource formats, nor does it adapt changed injection points, Mixin semantics, Fabric API callbacks, or game behavior. A remapped class linking successfully is not proof that its feature works in 26.2.

## Experimental game-side method aliases

The runtime agent injects a compatibility method into a Minecraft class before that class is
defined. It never rewrites the original mod JAR or the Minecraft JAR. Rules must be based on
methods verified in the actual 26.2 runtime; do not copy speculative names from a comparison report.

1. Create a tab-separated rules file, for example by copying `compatibility-rules.example.tsv`.
2. Add one row for each verified method alias:

~~~text
METHOD_ALIAS<TAB>runtime/owner/Class<TAB>currentMethod<TAB>(I)I<TAB>legacyMethod<TAB>STATIC
~~~

Replace the placeholders with a real internal class name, current method, exact JVM descriptor,
old method name, and the target method's invocation kind (STATIC or INSTANCE). In the actual
file, put literal tab characters between columns. The declared invocation kind must match the real
26.2 target method; a mismatch is rejected before a patched copy is produced. Class, target method,
descriptor, dispatch kind, and alias signature are validated when the affected class is transformed.

Build with `./gradlew clean test build`, then add the following option to the Minecraft install's
JVM arguments (adjust both paths):

~~~text
-javaagent:/path/to/gafi-loader-0.1.0-SNAPSHOT.jar=/path/to/compatibility-rules.tsv
~~~

The agent must be active before Minecraft/Fabric starts. A patch rule is not a compatibility guarantee:
it only restores a method entry point where calling the current method with the old descriptor is
actually equivalent. Keep a backup of the launch profile and remove the JVM argument to disable it.

For an offline copy of the 26.2 game JAR instead, use:

~~~bash
./gradlew run --args="--game /path/to/26.2-client.jar --patch-game build/minecraft-26.2-gafi.jar --patch-rules compatibility-rules.tsv"
~~~

The original game JAR is not overwritten. The offline copy operation does not itself configure the
Minecraft launcher; the Java-agent route is the live in-memory integration.

## Current limitations

- The API comparator uses official 1.21.11 Mojang mappings to normalize the old game JAR. It reports class/member differences and possible renames, but does not yet infer or apply semantic replacements.
- The mod scanner can normalize production Intermediary references when the 1.21.11 Intermediary mapping artifact and official Mojang mappings are supplied. It does not yet normalize Yarn-named development JARs or every custom namespace. Explicit TSV aliases can normalize known API changes, but each rule must be verified against the 26.2 API.
- The scanner currently focuses on Minecraft-owned symbols; Fabric Loader and Fabric API compatibility require separate analysis.
- The Java agent is an experimental runtime patching mechanism, not a replacement for Fabric Loader. It must be enabled in the launcher's JVM arguments and needs reviewed rules for the exact runtime class names/descriptors.
- The dependency override generator relaxes the Minecraft version gate only for selected mods. It does not relax their other dependencies or establish runtime compatibility.
- The API comparator can export same-descriptor method name candidates for manual review. Candidate rows are intentionally not executable `METHOD_ALIAS` rules.
- The runtime agent remaps direct 1.21.11 Intermediary references and recognized Mixin config/refmap JSON in memory. When bytecode aliases are enabled, it validates target symbols against the configured 26.2 game JAR and checks static/instance call-site shape. It still cannot conjure missing legacy classes or adapt changed semantics, arbitrary reflection strings, unrelated resource formats, or Fabric API differences.
- Current bridge rules support methods only when the current target method exists with the exact same descriptor. The example rules file intentionally contains no guessed Minecraft API mappings.
- Missing/removed classes, semantic descriptor conversions, arbitrary reflection/resource references, changes to Mixin injection semantics, and Fabric API changes remain unsupported unless separately adapted. Direct field and method renames can be covered by reviewed `bytecodeAliases` rules only when the JVM descriptor remains compatible.
- A successful scanner result or agent startup is not proof that a real 1.21.11 mod works in-game.
- A 26.2 JAR from the official launcher and real mod JARs are required for end-to-end validation.

## Development approach

Every compatibility rule should have a regression test and a clear explanation of the symbols/behavior it adapts. The compatibility engine must fail clearly when it cannot safely adapt a legacy reference rather than silently claiming support.

## License

MIT. See LICENSE.
