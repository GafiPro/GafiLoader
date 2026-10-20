# Compatibility pipeline and known boundaries

This document records what the current tools actually do and the order in which the runtime compatibility work should be built.

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

The API comparison distinguishes missing classes and members from possible renames or descriptor changes. Candidate matches are evidence for review, not patches and not proof of semantic equivalence.

## Runtime compatibility roadmap

1. **API inventory** — compare the old game API against 26.2 and preserve a machine-readable report.
2. **Namespace normalization** — identify each legacy mod's namespace (Intermediary, Mojang names, or Yarn names) and remap its Minecraft references before comparing them with 26.2. Production Fabric mods commonly use Intermediary, so raw-name scanning alone is not enough.
3. **Compatibility rules** — express reviewed class/member mappings and descriptor adaptations in a deterministic rule format.
4. **Bytecode adaptation** — apply safe, well-scoped transformations during loading or ahead of execution. Do not rewrite the original mod JAR.
5. **Fabric and Mixin integration** — handle loader metadata, Mixin targets/injections, and Fabric API changes separately from Minecraft class references.
6. **Runtime regression tests** — boot 26.2, load representative 1.21.11 mods, and test the behavior they actually modify.

No stage should be marked complete solely because a JAR passes a structural scan. A successful game launch and real mod behavior tests are required before claiming compatibility.
