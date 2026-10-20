# Compatibility pipeline and known boundaries

This document records what the current tools actually do and the order in which the runtime compatibility work should be built. The Java agent now has a prototype for runtime Intermediary remapping; real-mod compatibility is not yet proven.

## Why names must be normalized first

Minecraft 1.21.11 is still obfuscated, while the post-1.21.11 line uses unobfuscated game code. Comparing the raw 1.21.11 class names with 26.2 would therefore produce a large amount of noise. The API comparator reads the official 1.21.11 Mojang ProGuard mappings and converts legacy class names, member names, and descriptors into the official names used by the target JAR.

Mojang method mappings can carry both the old method's line range and the obfuscated line range. The parser normalizes that metadata before passing the mappings to Mapping-IO; it does not alter class/member names or descriptors.

## Current analysis commands

The target JAR always belongs to Minecraft 26.2:

- `--game`: 26.2 JAR, the launch target.
- `--legacy-game`: 1.21.11 JAR, used only as the old-API baseline.
- `--legacy-mappings`: official 1.21.11 client mapping text.
- `--mods`: JAR or directory of mod JARs to inspect.
- `--aliases`: explicitly reviewed symbol aliases for the mod scanner.
- `--api-report` and `--report`: separate JSON outputs for the two analyses.

The API comparison distinguishes missing classes and members from possible renames or descriptor changes. Candidate matches are evidence for review, not patches and not proof of semantic equivalence. It also distinguishes static/instance dispatch changes from safe-shape rename candidates; a candidate with changed invocation kind is never exported as a regular method alias.

To generate a Fabric dependency override for a directory containing only selected legacy Fabric mods (without requiring a game JAR), run:

```bash
./gradlew run --args="--mods /path/to/selected-legacy-mods --generate-dependency-overrides /path/to/.minecraft/config/fabric_loader_dependencies.json"
```

This updates the Fabric Loader dependency configuration, not the mod JARs. It removes only the selected mod IDs' `minecraft` requirement; the mods' other dependencies and runtime behavior are unchanged.

## Runtime compatibility roadmap

1. **API inventory** — compare the old game API against 26.2 and preserve a machine-readable report.
2. **Namespace normalization** — identify each legacy mod's namespace (Intermediary, Mojang names, or Yarn names) and remap its Minecraft references before comparing them with 26.2. Production Fabric mods commonly use Intermediary, so raw-name scanning alone is not enough.
3. **Compatibility rules** — express reviewed class/member mappings and descriptor adaptations in a deterministic rule format.
4. **Runtime namespace bridge (prototype implemented)** — the Java agent remaps direct Intermediary class/member/descriptor references in loaded bytecode to Mojang names in memory; the stored mod JAR is never rewritten. It also hooks Fabric's Mixin service resource reader for Mixin config/refmap JSON and its `getClassBytes` methods for class bytes that Mixin requests during metadata inspection. Those references can therefore be normalized before Mixin parses legacy bytecode/selectors, as well as during JVM class definition. The hook validates its expected Fabric Loader method signatures and fails clearly if they are absent. Arbitrary reflection strings and unrelated resource formats are still outside scope. When reviewed bytecode aliases are configured, `targetGameJar` is required and each alias target is checked against that API index; the transformer rejects static/instance opcode mismatches.
5. **Compatibility bridges** — apply safe, reviewed game-side method aliases and adapters for differences that are not pure namespace changes. Do not guess at changed semantics.
6. **Fabric dependency gate** — for only the selected legacy mod IDs, generate the documented `fabric_loader_dependencies.json` override that removes their `minecraft` version constraint. This lets Fabric attempt resolution; it is not a compatibility fix by itself.
7. **Fabric and Mixin integration** — the prototype now normalizes common Mixin config/refmap strings and direct references in class bytes that Mixin requests for metadata inspection. Remaining work is validating real mod refmaps and injection targets against Fabric 26.2, adapting changed Mixin injection points/semantics, handling other dependency constraints, and handling Fabric API changes separately from Minecraft class references.
8. **Runtime regression tests** — boot 26.2, load representative 1.21.11 mods, and test the behavior they actually modify.

No stage should be marked complete solely because a JAR passes a structural scan. A successful game launch and real mod behavior tests are required before claiming compatibility.
