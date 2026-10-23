package dev.gafipro.gafiloader;

import java.io.IOException;
import java.io.Reader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime Java agent for in-memory 1.21.11 Intermediary-to-26.2 name remapping and method aliases.
 *
 * <p>Recommended launch form:
 * {@code -javaagent:/path/to/gafi-loader.jar=/path/to/gafi-loader-agent.properties}</p>
 * A plain TSV argument remains supported for the legacy alias-only mode.
 */
public final class GameSideCompatibilityAgent {
    private GameSideCompatibilityAgent() {}

    public static void premain(String agentArguments, Instrumentation instrumentation) throws IOException {
        if (agentArguments == null || agentArguments.isBlank()) {
            throw new IllegalArgumentException(
                    "Gafi Loader requires an agent properties file or a legacy rules TSV.");
        }

        Path argumentPath = Path.of(agentArguments).toAbsolutePath().normalize();
        AgentSetup setup = loadSetup(argumentPath);
        GameJarPatcher.RuntimeRules rules = setup.rules();
        IntermediaryMappings intermediaryMappings = setup.intermediaryMappings();
        CompatibilityScanner.AliasSet bytecodeAliases = setup.bytecodeAliases();
        TargetApiIndex targetApi = setup.targetApi();
        LongAdder remappedClasses = new LongAdder();
        LongAdder aliasedClasses = new LongAdder();
        MixinResourceRemapper.configure(intermediaryMappings, bytecodeAliases, targetApi);

        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) throws IllegalClassFormatException {
                if (className == null) {
                    return null;
                }
                if (KnotClassLoaderPatcher.TARGET_CLASS.equals(className)) {
                    System.out.println("[Gafi Loader] DEBUG transformer received KnotClassLoader bytes.");
                }

                try {
                    // Game classes are already in 26.2's runtime namespace. They may still receive
                    // explicitly configured aliases and reviewed structural bridges before definition.
                    byte[] transformed = GameJarPatcher.transformClassForRuntime(
                            className, classfileBuffer, rules);
                    byte[] bridgeInput = transformed == null ? classfileBuffer : transformed;
                    byte[] bridged = LegacyApiBridgePatcher.transformClassForRuntime(
                            className, bridgeInput, targetApi);
                    if (bridged != null) {
                        transformed = bridged;
                        aliasedClasses.increment();
                        System.out.println("[Gafi Loader] Added in-memory legacy API bridge to " + className);
                    } else if (transformed != null) {
                        aliasedClasses.increment();
                        System.out.println("[Gafi Loader] Added legacy method aliases to " + className);
                    }

                    // Mixin configs and refmaps are read as resources, before a mod's classes
                    // reach Instrumentation. Hook Fabric's Mixin service at that resource boundary.
                    if (intermediaryMappings != null
                            && MixinServiceKnotPatcher.TARGET_CLASS.equals(className)) {
                        byte[] hookInput = transformed == null ? classfileBuffer : transformed;
                        byte[] hooked = MixinServiceKnotPatcher.transform(className, hookInput);
                        System.out.println("[Gafi Loader] Enabled in-memory Mixin resource remapping.");
                        return hooked;
                    }

                    if (intermediaryMappings != null
                            && KnotLauncherPatcher.TARGET_CLASS.equals(className)) {
                        byte[] hookInput = transformed == null ? classfileBuffer : transformed;
                        byte[] hooked = KnotLauncherPatcher.transform(className, hookInput);
                        System.out.println("[Gafi Loader] Enabled in-memory Knot launcher resource remapping.");
                        return hooked;
                    }

                    // KnotClassLoader also serves Mixin JSON/resources directly. Hooking only
                    // MixinServiceKnot misses configurations loaded through this classloader path.
                    if (intermediaryMappings != null
                            && KnotClassLoaderPatcher.TARGET_CLASS.equals(className)) {
                        byte[] hookInput = transformed == null ? classfileBuffer : transformed;
                        byte[] hooked = KnotClassLoaderPatcher.transform(className, hookInput);
                        System.out.println("[Gafi Loader] Enabled in-memory Knot resource remapping.");
                        return hooked;
                    }

                    // Fabric reads legacy ClassTweaker files before its regular game launch.
                    // Normalize those files in memory, before Fabric validates the header namespace.
                    if (intermediaryMappings != null
                            && ClassTweakerPatcher.TARGET_CLASS.equals(className)) {
                        byte[] hookInput = transformed == null ? classfileBuffer : transformed;
                        byte[] hooked = ClassTweakerPatcher.transform(className, hookInput);
                        System.out.println("[Gafi Loader] Enabled in-memory ClassTweaker namespace remapping.");
                        return hooked;
                    }

                    if (intermediaryMappings == null) {
                        return transformed;
                    }

                    // Mixin selectors such as @Inject(method = "method_123") are ordinary
                    // annotation strings, not JVM member references. Normalize them during class
                    // definition as well as at MixinServiceKnot's resource boundary: the service
                    // hook does not see every route by which Knot defines Mixin classes.
                    byte[] mappingInput = transformed == null ? classfileBuffer : transformed;
                    byte[] selectorRemapped = MixinSelectorRemapper.remap(
                            mappingInput, intermediaryMappings, bytecodeAliases, targetApi);
                    if (selectorRemapped != null) {
                        mappingInput = selectorRemapped;
                        transformed = selectorRemapped;
                        remappedClasses.increment();
                        System.out.println("[Gafi Loader] Normalized legacy Mixin selectors at class definition: "
                                + className);
                    }

                    if (skipIntermediaryScan(className)) {
                        return transformed;
                    }
                    byte[] remapped = IntermediaryBytecodeRemapper.remap(
                            mappingInput, intermediaryMappings, bytecodeAliases, targetApi);
                    if (remapped != null) {
                        remappedClasses.increment();
                    }
                    if (remapped != null) {
                        return remapped;
                    }
                    return transformed;
                } catch (IOException | RuntimeException exception) {
                    System.err.println("[Gafi Loader] FAILED to transform " + className + ".");
                    exception.printStackTrace(System.err);
                    IllegalClassFormatException failure = new IllegalClassFormatException(exception.getMessage());
                    failure.initCause(exception);
                    throw failure;
                }
            }
        }, instrumentation.isRetransformClassesSupported());

        // Some Fabric launcher classes can be loaded before the first application class is
        // transformed. Retransform KnotClassLoader if it is already loaded so its resource hook
        // is still installed; otherwise the normal transformer will patch it on first load.
        if (intermediaryMappings != null) {
            System.out.println("[Gafi Loader] DEBUG retransformation supported="
                    + instrumentation.isRetransformClassesSupported());
            boolean knotAlreadyLoaded = false;
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (loadedClass.getName().equals(KnotClassLoaderPatcher.TARGET_CLASS.replace('/', '.'))) {
                    knotAlreadyLoaded = true;
                    System.out.println("[Gafi Loader] DEBUG KnotClassLoader already loaded; modifiable="
                            + instrumentation.isModifiableClass(loadedClass));
                }
            }
            if (!knotAlreadyLoaded) {
                System.out.println("[Gafi Loader] DEBUG KnotClassLoader not loaded when transformer was installed.");
            }
        }
        if (intermediaryMappings != null && instrumentation.isRetransformClassesSupported()) {
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (loadedClass.getName().equals(
                        KnotClassLoaderPatcher.TARGET_CLASS.replace('/', '.'))
                        && instrumentation.isModifiableClass(loadedClass)) {
                    try {
                        instrumentation.retransformClasses(loadedClass);
                        System.out.println("[Gafi Loader] Installed Knot resource hook on an already-loaded class.");
                    } catch (UnmodifiableClassException exception) {
                        throw new IOException("Could not retransform already-loaded KnotClassLoader", exception);
                    }
                    break;
                }
            }
        }

        // The JVM may load the main Knot launcher class before premain. Retransform it as
        // well, because MixinServiceKnot delegates config reads to Knot.getResourceAsStream.
        if (intermediaryMappings != null && instrumentation.isRetransformClassesSupported()) {
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (loadedClass.getName().equals(
                        KnotLauncherPatcher.TARGET_CLASS.replace('/', '.'))
                        && instrumentation.isModifiableClass(loadedClass)) {
                    try {
                        instrumentation.retransformClasses(loadedClass);
                        System.out.println("[Gafi Loader] Installed Knot launcher resource hook on an already-loaded class.");
                    } catch (UnmodifiableClassException exception) {
                        throw new IOException("Could not retransform already-loaded Knot launcher", exception);
                    }
                    break;
                }
            }
        }

        // MixinServiceKnot may also be initialized by the launcher before premain.
        // Retransform it when possible so JSON/refmap resource reads receive the compatibility hook.
        if (intermediaryMappings != null && instrumentation.isRetransformClassesSupported()) {
            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (loadedClass.getName().equals(
                        MixinServiceKnotPatcher.TARGET_CLASS.replace('/', '.'))
                        && instrumentation.isModifiableClass(loadedClass)) {
                    try {
                        instrumentation.retransformClasses(loadedClass);
                        System.out.println("[Gafi Loader] Installed Mixin service resource hook on an already-loaded class.");
                    } catch (UnmodifiableClassException exception) {
                        throw new IOException("Could not retransform already-loaded MixinServiceKnot", exception);
                    }
                    break;
                }
            }
        }

        System.out.println("[Gafi Loader] Runtime game-side compatibility agent active.");
        System.out.println("[Gafi Loader] Config: " + setup.configPath());
        System.out.println("[Gafi Loader] Game-side method alias rules: " + rules.ruleCount());
        if (intermediaryMappings != null) {
            System.out.println("[Gafi Loader] Intermediary remapping enabled: "
                    + intermediaryMappings.classCount() + " classes, "
                    + intermediaryMappings.memberCount() + " members indexed.");
            System.out.println("[Gafi Loader] Mixin resource remapping enabled at the Fabric Mixin-service boundary.");
        } else {
            System.out.println("[Gafi Loader] Intermediary remapping disabled.");
        }
        System.out.println("[Gafi Loader] Original Minecraft and mod JARs will not be modified.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Set<String> applied = rules.appliedRules();
            if (applied.size() < rules.ruleCount()) {
                System.err.println("[Gafi Loader] Warning: only " + applied.size() + " of "
                        + rules.ruleCount() + " configured aliases were applied. "
                        + "Some target classes may not have been loaded in this run.");
            }
            System.out.println("[Gafi Loader] Classes with method aliases: " + aliasedClasses.sum());
            System.out.println("[Gafi Loader] Classes remapped from Intermediary: " + remappedClasses.sum());
        }, "gafi-loader-compatibility-summary"));
    }

    private static boolean skipIntermediaryScan(String className) {
        return className.startsWith("java/")
                || className.startsWith("javax/")
                || className.startsWith("jdk/")
                || className.startsWith("sun/")
                || className.startsWith("com/sun/")
                || className.startsWith("net/minecraft/")
                || className.startsWith("dev/gafipro/gafiloader/")
                || className.startsWith("org/objectweb/asm/")
                || className.startsWith("org/spongepowered/asm/")
                || className.startsWith("net/fabricmc/mappingio/")
                || className.startsWith("net/fabricmc/tinyremapper/")
                || className.startsWith("com/google/gson/")
                || className.startsWith("org/junit/")
                || className.startsWith("org/slf4j/");
    }

    private static AgentSetup loadSetup(Path argumentPath) throws IOException {
        if (!Files.isRegularFile(argumentPath)) {
            throw new IllegalArgumentException("Gafi Loader agent config does not exist: " + argumentPath);
        }

        if (argumentPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".tsv")) {
            return new AgentSetup(argumentPath, GameJarPatcher.loadRuntimeRules(argumentPath),
                    null, CompatibilityScanner.AliasSet.empty(), null);
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(argumentPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        Path base = argumentPath.getParent();
        Path rulesPath = resolveConfiguredPath(properties, "rules", base);
        Path targetGameJarPath = resolveConfiguredPath(properties, "targetGameJar", base);
        Path intermediaryPath = resolveConfiguredPath(properties, "intermediaryMappings", base);
        Path legacyMappingsPath = resolveConfiguredPath(properties, "legacyMappings", base);
        Path bytecodeAliasesPath = resolveConfiguredPath(properties, "bytecodeAliases", base);

        boolean hasIntermediary = intermediaryPath != null;
        boolean hasLegacyMappings = legacyMappingsPath != null;
        if (hasIntermediary != hasLegacyMappings) {
            throw new IOException("Agent config must set both intermediaryMappings and legacyMappings.");
        }
        if (bytecodeAliasesPath != null && !hasIntermediary) {
            throw new IOException("Agent config property 'bytecodeAliases' requires intermediaryMappings and legacyMappings.");
        }
        if (bytecodeAliasesPath != null && targetGameJarPath == null) {
            throw new IOException("Agent config property 'bytecodeAliases' also requires targetGameJar "
                    + "so aliases can be checked against the actual Minecraft 26.2 API.");
        }

        GameJarPatcher.RuntimeRules rules = rulesPath == null
                ? GameJarPatcher.emptyRuntimeRules() : GameJarPatcher.loadRuntimeRules(rulesPath);
        CompatibilityScanner.AliasSet bytecodeAliases = bytecodeAliasesPath == null
                ? CompatibilityScanner.AliasSet.empty()
                : CompatibilityScanner.AliasSet.load(bytecodeAliasesPath);
        TargetApiIndex targetApi = targetGameJarPath == null ? null : TargetApiIndex.load(targetGameJarPath);
        if (hasIntermediary) {
            bytecodeAliases = bytecodeAliases.withBuiltInCompatibilityAliases(targetApi);
        }
        if (targetApi != null && (bytecodeAliasesPath != null || hasIntermediary)) {
            bytecodeAliases.validateAgainstTarget(targetApi);
        }
        IntermediaryMappings mappings = null;
        if (hasIntermediary) {
            MojangMappings mojang = MojangMappings.load(legacyMappingsPath);
            mappings = IntermediaryMappings.load(intermediaryPath, mojang);
        }
        if (rules.ruleCount() == 0 && mappings == null) {
            throw new IOException("Agent config must define rules, intermediary mappings, or both.");
        }
        return new AgentSetup(argumentPath, rules, mappings, bytecodeAliases, targetApi);
    }

    private static Path resolveConfiguredPath(Properties properties, String key, Path base)
            throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        Path configured = Path.of(value.trim());
        if (!configured.isAbsolute()) {
            configured = base.resolve(configured);
        }
        configured = configured.normalize().toAbsolutePath();
        if (!Files.isRegularFile(configured)) {
            throw new IOException("Agent config property '" + key + "' does not point to a file: " + configured);
        }
        return configured;
    }

    private record AgentSetup(Path configPath, GameJarPatcher.RuntimeRules rules,
                              IntermediaryMappings intermediaryMappings,
                              CompatibilityScanner.AliasSet bytecodeAliases,
                              TargetApiIndex targetApi) {}
}
