# Game-side compatibility (1.21.11 -> 26.2)

## Non-negotiable invariant

Gafi Loader must not rewrite, remap, repackage, or otherwise mutate legacy mod JARs. Compatibility belongs on the Minecraft 26.2 side.

## What this means technically

This is not a normal Fabric mod feature. A regular mod cannot make arbitrary missing JVM classes appear under names expected by already-compiled mods before those mods are linked, nor can it make Fabric accept a legacy mod whose metadata and references target another Minecraft generation. The integration point must therefore be the loader / launch pipeline, with compatibility classes and method bridges supplied to the 26.2 runtime.

The implementation must preserve every original legacy JAR byte-for-byte. The experimental Java agent now applies reviewed method bridges to Minecraft classes in memory, before class definition; it also remaps direct mod bytecode references and recognized Mixin config/refmap JSON at read time. The offline utility can instead write a separate patched copy of the game JAR. Neither route rewrites mod JARs. Rules must be scoped to the exact 1.21.11 -> 26.2 pair.

## Compatibility strategy

1. Compare the official 1.21.11 and 26.2 game artifacts and their mapping namespaces.
2. Generate a candidate symbol report (classes, fields, methods, descriptors).
3. For pure renames with equivalent descriptors and compatible visibility, generate game-side aliases/bridges.
4. For removed classes or changed descriptors, require an explicit adapter whose behavior is reviewed and tested. Never guess argument values or pretend a name alias fixes semantic changes.
5. Inject/install those compatibility changes before affected game classes are defined and before legacy mods link to them. The prototype Java agent does this for configured method aliases; it does not yet change Fabric's mod metadata resolver.
6. Keep 26.2 as the launched game version and leave every mod JAR unchanged.

## Important JVM constraints

- A method bridge must match the old JVM descriptor and the old static/instance invocation kind exactly; Java source overloads alone do not solve descriptor or dispatch changes. Gafi Loader rejects rules whose declared STATIC/INSTANCE kind does not match the current target method.
- A field alias is not generally equivalent to a method bridge. Direct field reads/writes use field owner, name and descriptor in bytecode; a second field may diverge from the real state. Field compatibility needs a proven storage/transform strategy, not a blind duplicate.
- A class alias implemented as a subclass only works when inheritance, constructors, access and runtime type checks remain compatible. It is not a universal replacement for a removed class.
- Mixins and mods that depend on old class layouts, injection points, Fabric API callbacks, or changed game behavior need targeted adapters and integration tests.
- Because 26.1+ changed the game's naming/mapping situation, mappings must be normalized before differences are classified. Do not assume matching names imply matching behavior.

## Acceptance criteria

- Launches Minecraft 26.2.
- A test legacy mod's original JAR hash is identical before and after launch.
- The runtime supplies the required compatibility bridge before a legacy reference resolves, without modifying the legacy mod JAR.
- The legacy mod resolves and its target feature works in-game.
- A current 26.2 mod continues to load.
- Unsupported transformations fail with a precise diagnostic rather than silently producing a broken game.

## Current status

Implemented prototype pieces:
- API scanner and mapping normalization.
- Review-only method candidate TSV export. Candidate records are intentionally distinct from executable patch rules.
- Offline game-JAR copy patcher for explicit same-descriptor method aliases.
- Java agent that performs those aliases in memory before a target class is defined.
- Verified structural bridges injected into Minecraft 26.2 class bytes at definition time for the removed `Minecraft.setScreen(Screen)`, `DisplaySlot.teamColorToSlot(ChatFormatting)`, and `ReadOnlyScoreInfo.safeFormatValue(ReadOnlyScoreInfo, NumberFormat)` APIs. These adapters activate only when the configured 26.2 JAR contains the required replacement field/methods; original JARs remain unchanged.
- Java agent configuration that loads the official 1.21.11 Mojang mappings and Fabric Intermediary artifact, then remaps direct legacy class/member/descriptors in loaded bytecode to 26.2 names without editing the mod JAR.
- Hooks at Fabric's `MixinServiceKnot.getResourceAsStream` and `getClassBytes` boundaries: known Mixin config/refmap JSON is remapped in memory, and class bytes requested by Mixin for metadata inspection have their direct Intermediary references normalized before Mixin consumes them. The hooks validate the expected method signatures. CI also downloads the released Fabric Loader 0.20.1 JAR and applies the patcher to its actual `MixinServiceKnot` class. That verifies the binary hook points, but is not an actual Fabric 26.2 game launch.
- Generator for Fabric's documented dependency-override configuration, scoped to an explicitly selected set of legacy mod JARs.
- Regression tests using synthetic JARs/classes for bytecode delegation, Intermediary owner/member/descriptor remapping in a separate JVM, preservation of source bytes, and dependency-config merging.

Not yet implemented or verified:
- A reviewed alias catalogue generated from the official 1.21.11 and 26.2 game artifacts.
- Broad legacy-mod resolution. The dependency-override generator removes only the selected mods' `minecraft` version constraint; it does not make bytecode compatible or adapt other dependencies.
- Full end-to-end launch against the official 26.2 runtime with a production 1.21.11 mod. CI scans a real `Small Items Mod 1.21.11` JAR and checks that the structural adapters can be built against the official target API, but does not launch the full game or verify the mod feature in-game.
- Missing-class aliases, semantic descriptor conversions, arbitrary reflection-string and unrelated-resource remapping, full Mixin/Fabric API adaptation, and game-behavior tests. The resource hook adapts names/selectors only; it does not port injection semantics.
- End-to-end launch with an actual 1.21.11 mod alongside a 26.2 mod. Remaining game-wide API differences include the `TimerQueue` serialization redesign and removed loot score-provider APIs; they need targeted compatibility work and are not claimed to be adapted.

Therefore this is an early runtime-bridge prototype, not a claim that arbitrary legacy mods already load.
