package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remaps Mixin configuration/refmap JSON and class bytes fetched for Mixin metadata inspection.
 *
 * <p>Only recognized Mixin resources and class-byte reads are considered. Mapping is performed in
 * memory; no resource entry or mod JAR is modified on disk.</p>
 */
public final class MixinResourceRemapper {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern INTERNAL_CLASS = Pattern.compile(
            "net/minecraft/class_\\d+(?:\\$[A-Za-z0-9_$]+)*");
    private static final Pattern DOTTED_CLASS = Pattern.compile(
            "net\\.minecraft\\.class_\\d+(?:\\$[A-Za-z0-9_$]+)*");

    private static volatile IntermediaryMappings mappings;
    private static volatile CompatibilityScanner.AliasSet aliases =
            CompatibilityScanner.AliasSet.empty();
    private static volatile TargetApiIndex targetApi;
    private static final Set<String> STRUCTURALLY_SKIPPED_MIXINS = ConcurrentHashMap.newKeySet();

    private MixinResourceRemapper() {}

    static void configure(IntermediaryMappings intermediaryMappings,
                          CompatibilityScanner.AliasSet bytecodeAliases) {
        configure(intermediaryMappings, bytecodeAliases, null);
    }

    static void configure(IntermediaryMappings intermediaryMappings,
                          CompatibilityScanner.AliasSet bytecodeAliases,
                          TargetApiIndex targetApiIndex) {
        mappings = intermediaryMappings;
        aliases = bytecodeAliases == null
                ? CompatibilityScanner.AliasSet.empty() : bytecodeAliases;
        targetApi = targetApiIndex;
        STRUCTURALLY_SKIPPED_MIXINS.clear();
    }

    /**
     * Wraps Mixin JSON/refmap/class resource URLs so callers that use URL.openStream rather than
     * getResourceAsStream still receive the in-memory compatibility view.
     */
    public static URL transformUrl(String resourceName, URL original) {
        // Wrap Mixin JSON/refmaps and only those Mixin class URLs that already expose a path.
        // Fabric's union URLs with no path must remain untouched because Loader converts them to paths.
        if (original == null || mappings == null
                || (!isMixinResource(resourceName)
                    && (!isMixinClassResource(resourceName) || original.getPath() == null))) {
            return original;
        }
        try {
            return new URL(null, original.toExternalForm(), new URLStreamHandler() {
                @Override
                protected void parseURL(URL url, String spec, int start, int limit) {
                    setURL(url, original.getProtocol(), original.getHost(), original.getPort(),
                            original.getAuthority(), original.getUserInfo(), original.getPath(),
                            original.getQuery(), original.getRef());
                }

                @Override
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override
                        public void connect() {}

                        @Override
                        public InputStream getInputStream() throws IOException {
                            return MixinResourceRemapper.transform(resourceName, original.openStream());
                        }
                    };
                }
            });
        } catch (MalformedURLException exception) {
            System.err.println("[Gafi Loader] Could not wrap resource URL " + resourceName
                    + " for in-memory compatibility: " + exception.getMessage());
            return original;
        }
    }

    /**
     * Opens a legacy ClassTweaker/AccessWidener file remapped to the runtime namespace.
     * The source path is only read; the upstream mod or API JAR is never modified.
     */
    public static BufferedReader openClassTweakerReader(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("ClassTweaker path is required.");
        }

        IntermediaryMappings currentMappings = mappings;
        if (currentMappings == null) {
            return Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }

        String original = Files.readString(path, StandardCharsets.UTF_8);
        String remapped = remapClassTweakerText(original, currentMappings, aliases, path.toString());
        if (!remapped.equals(original)) {
            System.out.println("[Gafi Loader] Remapped ClassTweaker file in memory: " + path.getFileName());
        }
        return new BufferedReader(new StringReader(remapped));
    }

    private static String remapClassTweakerText(String original, IntermediaryMappings mappings,
                                                 CompatibilityScanner.AliasSet aliases,
                                                 String sourceName) throws IOException {
        String[] lines = original.split("\\R", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IOException("Empty ClassTweaker header in " + sourceName);
        }

        String[] header = lines[0].trim().split("\\s+");
        if (header.length != 3
                || !(header[0].equals("classTweaker") || header[0].equals("accessWidener"))
                || !(header[1].equals("v1") || header[1].equals("v2"))) {
            throw new IOException("Unsupported ClassTweaker header in " + sourceName
                    + ": " + lines[0]);
        }

        if (header[2].equals("official")) {
            return original;
        }
        if (!header[2].equals("intermediary")) {
            throw new IOException("Gafi Loader can only remap ClassTweaker files from intermediary "
                    + "to official; found namespace '" + header[2] + "' in " + sourceName);
        }

        header[2] = "official";
        lines[0] = String.join(" ", header);
        boolean classTweaker = header[0].equals("classTweaker");
        boolean versionTwo = header[1].equals("v2");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int commentIndex = line.indexOf('#');
            String code = commentIndex >= 0 ? line.substring(0, commentIndex) : line;
            String comment = commentIndex >= 0 ? line.substring(commentIndex) : "";
            String stripped = code.strip();
            if (stripped.isEmpty()) {
                continue;
            }

            String leading = code.substring(0, code.length() - code.stripLeading().length());
            String trailing = code.substring(code.stripTrailing().length());
            String[] tokens = stripped.split("\\s+");
            String directive = tokens[0];
            String operation = directive.startsWith("transitive-")
                    ? directive.substring("transitive-".length()) : directive;

            try {
                switch (operation) {
                    case "accessible", "extendable", "mutable" -> remapAccessEntry(
                            tokens, mappings, aliases, sourceName, i + 1);
                    case "inject-interface" -> {
                        if (!classTweaker || tokens.length != 3) {
                            throw entryError(sourceName, i + 1,
                                    "Expected inject-interface <className> <interfaceName>");
                        }
                        tokens[1] = remapClassName(tokens[1], mappings, aliases);
                        tokens[2] = remapText(tokens[2], mappings, aliases);
                    }
                    case "extend-enum" -> {
                        if (!classTweaker || !versionTwo || tokens.length != 3) {
                            throw entryError(sourceName, i + 1,
                                    "Expected ClassTweaker v2 extend-enum <className> <constantName>");
                        }
                        tokens[1] = remapClassName(tokens[1], mappings, aliases);
                    }
                    default -> throw entryError(sourceName, i + 1,
                            "Unsupported ClassTweaker directive '" + directive + "'");
                }
            } catch (IllegalArgumentException exception) {
                throw entryError(sourceName, i + 1, exception.getMessage());
            }

            lines[i] = leading + String.join(" ", tokens) + trailing + comment;
        }

        return String.join("\n", lines);
    }

    private static void remapAccessEntry(String[] tokens, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases,
                                         String sourceName, int lineNumber) throws IOException {
        if (tokens.length < 3) {
            throw entryError(sourceName, lineNumber, "Expected class, field, or method after access directive");
        }

        switch (tokens[1]) {
            case "class" -> {
                if (tokens.length != 3) {
                    throw entryError(sourceName, lineNumber, "Expected <access> class <className>");
                }
                tokens[2] = remapClassName(tokens[2], mappings, aliases);
            }
            case "field", "method" -> {
                if (tokens.length != 5) {
                    throw entryError(sourceName, lineNumber,
                            "Expected <access> " + tokens[1] + " <owner> <name> <descriptor>");
                }
                String oldOwner = tokens[2];
                CompatibilityScanner.SymbolKind kind = tokens[1].equals("method")
                        ? CompatibilityScanner.SymbolKind.METHOD : CompatibilityScanner.SymbolKind.FIELD;
                CompatibilityScanner.Reference normalized = mappings.normalize(
                        new CompatibilityScanner.Reference(kind, oldOwner, tokens[3], tokens[4]));
                CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
                if (!mapped.owner().equals(normalized.owner())) {
                    throw entryError(sourceName, lineNumber,
                            "Owner-changing member aliases require a dedicated ClassTweaker adapter");
                }
                // ClassTweaker keeps the explicitly specified owner class. Member name/descriptor
                // use mapped declarations, including the unambiguous inherited-member fallback.
                tokens[2] = remapClassName(oldOwner, mappings, aliases);
                tokens[3] = mapped.name();
                tokens[4] = mapped.descriptor();
            }
            default -> throw entryError(sourceName, lineNumber,
                    "Unsupported access target type '" + tokens[1] + "'");
        }
    }

    private static String remapClassName(String intermediaryName, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases) {
        return aliases.mapClass(mappings.mapClassName(intermediaryName));
    }

    private static IOException entryError(String sourceName, int lineNumber, String message) {
        return new IOException("Cannot remap ClassTweaker entry at " + sourceName + ":" + lineNumber
                + ": " + message);
    }

    /**
     * Called from Fabric's MixinServiceKnot.getResourceAsStream method by the runtime hook.
     * The returned stream is self-contained, so Fabric/Mixin can close it as usual.
     */
    public static InputStream transform(String resourceName, InputStream original) {
        IntermediaryMappings currentMappings = mappings;
        boolean classResource = isClassResource(resourceName);
        if (original == null || currentMappings == null
                || (!isMixinResource(resourceName) && !classResource
                    && !isPotentialClassResourceName(resourceName))) {
            return original;
        }
        if (resourceName != null && resourceName.contains("fabric-events-interaction-v0")) {
            System.out.println("[Gafi Loader] DEBUG intercepted interaction Mixin resource: " + resourceName);
        }

        final byte[] source;
        try {
            source = original.readAllBytes();
        } catch (IOException exception) {
            System.err.println("[Gafi Loader] Could not read Mixin resource " + resourceName
                    + " for in-memory remapping: " + exception.getMessage());
            return original;
        }
        try {
            original.close();
        } catch (IOException exception) {
            // The bytes have been captured; a close failure should not turn the replacement stream
            // into an already-consumed original stream.
            System.err.println("[Gafi Loader] Warning: failed to close original Mixin resource "
                    + resourceName + ": " + exception.getMessage());
        }

        // Some Knot/Mixin paths request a class by binary name without the ".class" suffix.
        // Identify bytecode by its CAFEBABE header rather than treating it as opaque resource data.
        if (!classResource && source.length >= 4
                && source[0] == (byte) 0xCA && source[1] == (byte) 0xFE
                && source[2] == (byte) 0xBA && source[3] == (byte) 0xBE) {
            classResource = true;
        }

        if (classResource) {
            return new ByteArrayInputStream(transformClassBytes(resourceName, source));
        }

        try {
            JsonElement root = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            AtomicBoolean changed = new AtomicBoolean(false);
            JsonElement remapped = remapJson(root, currentMappings, aliases, changed);
            if (resourceName.contains("fabric-particles-v1")) {
                System.out.println("[Gafi Loader] DEBUG particles config=" + GSON.toJson(remapped));
            }
            int prunedMixins = pruneStructurallyIncompatibleMixins(
                    remapped, resourceName, currentMappings, aliases, targetApi);
            if (prunedMixins > 0) {
                changed.set(true);
            }
            if (!changed.get()) {
                return new ByteArrayInputStream(source);
            }

            byte[] output = GSON.toJson(remapped).getBytes(StandardCharsets.UTF_8);
            System.out.println("[Gafi Loader] Remapped Mixin resource in memory: " + resourceName);
            return new ByteArrayInputStream(output);
        } catch (RuntimeException exception) {
            // A Mixin resource that is not valid JSON or contains an unsupported selector should
            // not silently become an empty/broken file. Preserve its exact bytes and report why.
            System.err.println("[Gafi Loader] Could not remap Mixin resource " + resourceName
                    + "; passing its original bytes through unchanged: " + exception.getMessage());
            return new ByteArrayInputStream(source);
        }
    }


    /**
     * Drops only mixin classes that are structurally impossible to apply: a normal class mixin
     * whose target has become an interface. Continuing with the rest of the config is safer than
     * aborting Fabric before launch, but it does not recreate the skipped mixin's behavior.
     */
    private static int pruneStructurallyIncompatibleMixins(
            JsonElement root, String resourceName, IntermediaryMappings mappings,
            CompatibilityScanner.AliasSet aliases, TargetApiIndex targetApi) {
        if (!(root instanceof JsonObject config) || targetApi == null) {
            return 0;
        }

        JsonElement packageValue = config.get("package");
        if (packageValue == null || !packageValue.isJsonPrimitive()
                || !packageValue.getAsJsonPrimitive().isString()) {
            return 0;
        }

        String mixinPackage = packageValue.getAsString().replace('.', '/');
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader targetLoader = fabricTargetClassLoader();
        List<String> uninspectableMixins = new ArrayList<>();

        int pruned = 0;
        for (String group : List.of("mixins", "client", "server")) {
            JsonElement groupValue = config.get(group);
            if (groupValue == null || !groupValue.isJsonArray()) {
                continue;
            }

            JsonArray retained = new JsonArray();
            boolean changedGroup = false;
            for (JsonElement entry : groupValue.getAsJsonArray()) {
                String incompatibility = null;
                String classResource = null;
                if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                    String mixinName = entry.getAsString();
                    classResource = mixinClassResource(mixinPackage, mixinName);
                    if (mixinName.contains("LivingEntity")) {
                        System.out.println("[Gafi Loader] DEBUG mixin entry=" + mixinName
                                + " resource=" + classResource + " package=" + mixinPackage);
                    }
                    if (classResource != null) {
                        boolean inspected = false;
                        try (InputStream input = openMixinClassResource(
                                classResource, contextLoader, targetLoader,
                                MixinResourceRemapper.class.getClassLoader(),
                                ClassLoader.getSystemClassLoader())) {
                            if (input != null) {
                                inspected = true;
                                MixinClassMetadata metadata = readMixinMetadata(input.readAllBytes());
                                if (metadata == null && mixinName.contains("LivingEntity")) {
                                    System.out.println("[Gafi Loader] DEBUG no @Mixin targets found for " + classResource);
                                }
                                if (metadata != null) {
                                    if (resourceName.contains("fabric-events-interaction-v0")
                                            || resourceName.contains("fabric-recipe-api-v1")) {
                                        System.out.println("[Gafi Loader] DEBUG inspected mixin metadata "
                                                + mixinName + " targets=" + metadata.targets()
                                                + " selectors=" + metadata.selectors()
                                                + " atTargets=" + metadata.atTargets());
                                    }
                                    if (mixinName.endsWith("LivingEntityMixin")) {
                                        System.out.println("[Gafi Loader] DEBUG mixin metadata " + mixinName
                                                + " targets=" + metadata.targets()
                                                + " selectors=" + metadata.selectors()
                                                + " atTargets=" + metadata.atTargets());
                                    }
                                    if (metadata.superName() != null
                                            && STRUCTURALLY_SKIPPED_MIXINS.contains(metadata.superName())) {
                                        incompatibility = "superclass Mixin " + metadata.superName()
                                                + " was skipped because its target is incompatible with Minecraft 26.2";
                                    }
                                    for (String targetName : metadata.targets()) {
                                        if (incompatibility != null) {
                                            break;
                                        }
                                        String normalized = targetName.replace('.', '/');
                                        if (normalized.startsWith("L") && normalized.endsWith(";")) {
                                            normalized = normalized.substring(1, normalized.length() - 1);
                                        }
                                        String mappedTarget = aliases.mapClass(mappings.mapClassName(normalized));
                                        if (!metadata.isInterface() && targetApi.isInterface(mappedTarget)) {
                                            incompatibility = "target " + mappedTarget
                                                    + " is an interface in Minecraft 26.2, but the mixin bytecode is a class";
                                            break;
                                        }
                                        if (!targetApi.hasClass(mappedTarget)) {
                                            continue;
                                        }

                                        // Some launch paths do not pass every Mixin class through
                                        // the class-byte hook. Preflight selectors at config-read time
                                        // as a second safety boundary: a stale required selector must
                                        // not crash the whole client. Skipping a Mixin is a fail-safe,
                                        // not an emulation of the omitted behavior.
                                        for (MixinSelector selector : metadata.selectors()) {
                                            String selectorIssue = MixinSelectorRemapper.incompatibilityReason(
                                                    selector.value(), metadata.targets(), mappings, aliases, targetApi);
                                            if (selectorIssue != null) {
                                                incompatibility = selectorIssue
                                                        + "; Mixin skipped to prevent a fatal injection failure";
                                                break;
                                            }
                                        }
                                        if (incompatibility != null) {
                                            break;
                                        }

                                        for (ShadowMember shadow : metadata.shadowMembers()) {
                                            String memberName = shadow.name();
                                            if (!shadow.prefix().isEmpty() && memberName.startsWith(shadow.prefix())) {
                                                memberName = memberName.substring(shadow.prefix().length());
                                            }
                                            CompatibilityScanner.SymbolKind kind = shadow.method()
                                                    ? CompatibilityScanner.SymbolKind.METHOD
                                                    : CompatibilityScanner.SymbolKind.FIELD;
                                            CompatibilityScanner.Reference normalizedShadow = mappings.normalize(
                                                    new CompatibilityScanner.Reference(kind, normalized,
                                                            memberName, shadow.descriptor()));
                                            CompatibilityScanner.Reference mappedShadow = aliases.normalize(normalizedShadow);
                                            boolean exists = shadow.method()
                                                    ? targetApi.methodAccess(mappedShadow.owner(), mappedShadow.name(),
                                                            mappedShadow.descriptor()) != null
                                                    : targetApi.fieldAccess(mappedShadow.owner(), mappedShadow.name(),
                                                            mappedShadow.descriptor()) != null;
                                            if (!exists) {
                                                String memberKind = shadow.method() ? "method" : "field";
                                                incompatibility = "@Shadow " + memberKind + " " + mappedShadow.owner() + "#"
                                                        + mappedShadow.name() + mappedShadow.descriptor()
                                                        + " is not present with this signature in Minecraft 26.2";
                                                break;
                                            }
                                        }
                                        if (incompatibility != null) {
                                            break;
                                        }
                                        for (AccessorTarget accessor : metadata.accessorTargets()) {
                                            CompatibilityScanner.SymbolKind kind = accessor.method()
                                                    ? CompatibilityScanner.SymbolKind.METHOD
                                                    : CompatibilityScanner.SymbolKind.FIELD;
                                            CompatibilityScanner.Reference normalizedAccessor = mappings.normalize(
                                                    new CompatibilityScanner.Reference(kind, normalized,
                                                            accessor.name(), accessor.descriptor()));
                                            CompatibilityScanner.Reference mappedAccessor = aliases.normalize(normalizedAccessor);
                                            boolean exists = accessor.method()
                                                    ? targetApi.methodAccess(mappedAccessor.owner(), mappedAccessor.name(),
                                                            mappedAccessor.descriptor()) != null
                                                    : targetApi.fieldAccess(mappedAccessor.owner(), mappedAccessor.name(),
                                                            mappedAccessor.descriptor()) != null;
                                            if (!exists) {
                                                String memberKind = accessor.method() ? "method" : "field";
                                                incompatibility = "@Accessor/@Invoker " + memberKind + " "
                                                        + mappedAccessor.owner() + "#" + mappedAccessor.name()
                                                        + mappedAccessor.descriptor()
                                                        + " is not present with this signature in Minecraft 26.2";
                                                break;
                                            }
                                        }
                                        if (incompatibility != null) {
                                            break;
                                        }
                                    }
                                    if (incompatibility == null) {
                                        for (MixinSelector selector : metadata.selectors()) {
                                            String selectorProblem = MixinSelectorRemapper.incompatibilityReason(
                                                    selector.value(), metadata.targets(), mappings, aliases, targetApi);
                                            if (resourceName.contains("fabric-recipe-api-v1")) {
                                                System.out.println("[Gafi Loader] DEBUG recipe selector "
                                                        + mixinName + " " + selector.value()
                                                        + " -> " + selectorProblem);
                                            }
                                            if (selectorProblem != null) {
                                                incompatibility = selectorProblem;
                                                break;
                                            }
                                        }
                                    }
                                    if (incompatibility == null) {
                                        for (MixinAtTarget anchor : metadata.atTargets()) {
                                            // Check the exact bytecode location, not merely whether
                                            // the named method exists. Redirects and other injector
                                            // callbacks can otherwise fail Mixin's required injection
                                            // count and crash the entire client during startup.
                                            String staleAnchor = injectionAnchorIncompatibility(
                                                    anchor, metadata.targets(), mappings, aliases, targetApi);
                                            if (staleAnchor != null) {
                                                incompatibility = staleAnchor
                                                        + "; this Mixin's behavior is not emulated";
                                                break;
                                            }
                                            String atValue = anchor.atValue();
                                            String mappedAnchor = LegacyClassAliases.mapDescriptor(
                remapText(anchor.target(), mappings, aliases), targetApi);
                                            if (atValue.equals("NEW")) {
                                                String methodName = MixinSelectorRemapper.normalizedMethodSelector(
                                                        anchor.methodSelector(), metadata.targets(), mappings, aliases);
                                                String allocationType = null;
                                                String constructorDescriptor = null;
                                                String displayTarget = mappedAnchor;
                                                if (anchor.target().startsWith("(")) {
                                                    String targetDescriptor = aliases.mapDescriptor(
                                                            mappings.mapDescriptor(anchor.target()));
                                                    Type allocation = Type.getMethodType(targetDescriptor).getReturnType();
                                                    if (allocation.getSort() == Type.OBJECT) {
                                                        allocationType = allocation.getInternalName();
                                                        constructorDescriptor = Type.getMethodDescriptor(
                                                                Type.VOID_TYPE, Type.getMethodType(targetDescriptor).getArgumentTypes());
                                                    }
                                                    displayTarget = targetDescriptor;
                                                } else if (mappedAnchor.startsWith("L")) {
                                                    int newOwnerEnd = mappedAnchor.indexOf(';');
                                                    if (newOwnerEnd > 1) {
                                                        String explicitOwner = mappedAnchor.substring(1, newOwnerEnd);
                                                        String explicitMember = mappedAnchor.substring(newOwnerEnd + 1);
                                                        if (explicitMember.startsWith("<init>(")) {
                                                            allocationType = explicitOwner;
                                                            constructorDescriptor = explicitMember.substring("<init>".length());
                                                        } else if (explicitMember.isBlank()) {
                                                            allocationType = explicitOwner;
                                                        }
                                                    }
                                                } else {
                                                    String normalizedAllocation = mappedAnchor.replace('.', '/');
                                                    if (normalizedAllocation.startsWith("net/minecraft/")) {
                                                        allocationType = normalizedAllocation;
                                                    }
                                                }
                                                if (allocationType != null) {
                                                    for (String rawTarget : metadata.targets()) {
                                                        String normalizedTarget = rawTarget.replace('.', '/');
                                                        if (normalizedTarget.startsWith("L") && normalizedTarget.endsWith(";")) {
                                                            normalizedTarget = normalizedTarget.substring(1, normalizedTarget.length() - 1);
                                                        }
                                                        String mappedTarget = aliases.mapClass(
                                                                mappings.mapClassName(normalizedTarget));
                                                        if (!targetApi.hasClass(mappedTarget)
                                                                || !targetApi.hasMethodNamed(mappedTarget, methodName)) {
                                                            continue;
                                                        }
                                                        boolean hasAllocation = constructorDescriptor != null
                                                                ? targetApi.hasNewExpression(mappedTarget, methodName,
                                                                        allocationType, constructorDescriptor)
                                                                : targetApi.hasNewExpressionOfType(mappedTarget, methodName, allocationType);
                                                        if (!hasAllocation) {
                                                            incompatibility = "@At(value=\"NEW\", target=\""
                                                                    + displayTarget + "\") has no matching object "
                                                                    + "allocation in Minecraft 26.2 method "
                                                                    + mappedTarget + "#" + methodName
                                                                    + "; this Mixin's behavior is not emulated";
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (incompatibility != null) {
                                                    break;
                                                }
                                                continue;
                                            }

                                            // Verify explicit invocation/field anchors against the target game.
                                            // A stale @At target otherwise reaches Mixin and can crash the whole client.
                                            if (!(atValue.equals("INVOKE") || atValue.equals("INVOKE_ASSIGN")
                                                    || atValue.equals("FIELD")) || !mappedAnchor.startsWith("L")) {
                                                continue;
                                            }
                                            int ownerEnd = mappedAnchor.indexOf(';');
                                            if (ownerEnd <= 1 || ownerEnd + 1 >= mappedAnchor.length()) {
                                                continue;
                                            }
                                            String anchorOwner = mappedAnchor.substring(1, ownerEnd);
                                            if (!anchorOwner.startsWith("net/minecraft/")
                                                    || !targetApi.hasClass(anchorOwner)) {
                                                continue;
                                            }
                                            String member = mappedAnchor.substring(ownerEnd + 1);
                                            if (atValue.equals("FIELD")) {
                                                int separator = member.indexOf(':');
                                                if (separator <= 0) {
                                                    continue;
                                                }
                                                String fieldName = member.substring(0, separator);
                                                String fieldDescriptor = member.substring(separator + 1);
                                                if (targetApi.fieldAccess(anchorOwner, fieldName, fieldDescriptor) == null) {
                                                    incompatibility = "@At(value=\"FIELD\", target=\""
                                                            + mappedAnchor + "\") references a field absent from "
                                                            + "Minecraft 26.2; this Mixin's behavior is not emulated";
                                                    break;
                                                }
                                                String targetMethodName = MixinSelectorRemapper.normalizedMethodSelector(
                                                        anchor.methodSelector(), metadata.targets(), mappings, aliases);
                                                for (String rawTarget : metadata.targets()) {
                                                    String normalizedTarget = rawTarget.replace('.', '/');
                                                    if (normalizedTarget.startsWith("L") && normalizedTarget.endsWith(";")) {
                                                        normalizedTarget = normalizedTarget.substring(1, normalizedTarget.length() - 1);
                                                    }
                                                    String mappedTarget = aliases.mapClass(mappings.mapClassName(normalizedTarget));
                                                    if (targetApi.hasMethodNamed(mappedTarget, targetMethodName)
                                                            && !targetApi.hasFieldAccessInNamedMethod(mappedTarget, targetMethodName,
                                                                    anchorOwner, fieldName, fieldDescriptor)) {
                                                        incompatibility = "@At(value=\"FIELD\", target=\""
                                                                + mappedAnchor + "\") field access is absent from target method "
                                                                + mappedTarget + "#" + targetMethodName
                                                                + " in Minecraft 26.2; this Mixin's behavior is not emulated";
                                                        break;
                                                    }
                                                }
                                                if (incompatibility != null) {
                                                    break;
                                                }
                                            } else {
                                                int descriptorStart = member.indexOf('(');
                                                if (descriptorStart <= 0) {
                                                    continue;
                                                }
                                                String methodName = member.substring(0, descriptorStart);
                                                String descriptor = member.substring(descriptorStart);
                                                if (targetApi.methodAccess(anchorOwner, methodName, descriptor) == null) {
                                                    incompatibility = "@At(value=\"" + atValue + "\", target=\""
                                                            + mappedAnchor + "\") references a method absent from "
                                                            + "Minecraft 26.2; this Mixin's behavior is not emulated";
                                                    break;
                                                }
                                                String targetMethodName = MixinSelectorRemapper.normalizedMethodSelector(
                                                        anchor.methodSelector(), metadata.targets(), mappings, aliases);
                                                for (String rawTarget : metadata.targets()) {
                                                    String normalizedTarget = rawTarget.replace('.', '/');
                                                    if (normalizedTarget.startsWith("L") && normalizedTarget.endsWith(";")) {
                                                        normalizedTarget = normalizedTarget.substring(1, normalizedTarget.length() - 1);
                                                    }
                                                    String mappedTarget = aliases.mapClass(mappings.mapClassName(normalizedTarget));
                                                    if (targetApi.hasMethodNamed(mappedTarget, targetMethodName)
                                                            && !targetApi.hasInvocationInNamedMethod(mappedTarget, targetMethodName,
                                                                    anchorOwner, methodName, descriptor)) {
                                                        incompatibility = "@At(value=\"" + atValue + "\", target=\""
                                                                + mappedAnchor + "\") invocation is absent from target method "
                                                                + mappedTarget + "#" + targetMethodName
                                                                + " in Minecraft 26.2; this Mixin's behavior is not emulated";
                                                        break;
                                                    }
                                                }
                                                if (incompatibility != null) {
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (IOException | RuntimeException exception) {
                            System.err.println("[Gafi Loader] Could not inspect mixin structure for "
                                    + mixinName + " in " + resourceName + ": " + exception.getMessage());
                        }
                        if (mixinName.contains("LivingEntity")) {
                            System.out.println("[Gafi Loader] DEBUG mixin inspection result=" + mixinName
                                    + " inspected=" + inspected + " incompatibility=" + incompatibility);
                        }
                        if (!inspected) {
                            uninspectableMixins.add(classResource);
                        }
                    }
                }

                if (incompatibility != null) {
                    System.err.println("[Gafi Loader] Skipping structurally incompatible mixin "
                            + entry.getAsString() + " from " + resourceName + ": "
                            + incompatibility + ". The skipped behavior is not emulated.");
                    changedGroup = true;
                    if (classResource != null) {
                        String internalName = classResource.endsWith(".class")
                                ? classResource.substring(0, classResource.length() - ".class".length())
                                : classResource;
                        STRUCTURALLY_SKIPPED_MIXINS.add(internalName);
                    }
                    pruned++;
                } else {
                    retained.add(entry);
                }
            }

            if (changedGroup) {
                config.add(group, retained);
            }
        }
        if (pruned > 0) {
            System.out.println("[Gafi Loader] Removed " + pruned
                    + " structurally incompatible mixin entry/entries from " + resourceName);
        }
        if (!uninspectableMixins.isEmpty()) {
            List<String> examples = uninspectableMixins.subList(0, Math.min(3, uninspectableMixins.size()));
            System.err.println("[Gafi Loader] Could not resolve " + uninspectableMixins.size()
                    + " Mixin class resource(s) while validating " + resourceName
                    + "; structural compatibility checks may be incomplete. Examples: "
                    + String.join(", ", examples));
        }
        return pruned;
    }

    /**
     * Gets the launcher's actual game classloader when Fabric is present. The Java agent's
     * system classloader is not necessarily the Knot classloader that can read classes from mods.
     */
    private static ClassLoader fabricTargetClassLoader() {
        try {
            ClassLoader ownLoader = MixinResourceRemapper.class.getClassLoader();
            Class<?> launcherBase = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncherBase", false, ownLoader);
            Object launcher = launcherBase.getMethod("getLauncher").invoke(null);
            if (launcher == null) {
                return null;
            }
            Object targetLoader = launcher.getClass().getMethod("getTargetClassLoader").invoke(launcher);
            return targetLoader instanceof ClassLoader classLoader ? classLoader : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            return null;
        }
    }

    /**
     * Resolves a Mixin class from the target game/mod classpath first, then tries the explicitly
     * supplied fallback classloaders. Returning null means the bytes truly weren't available from
     * any supported classpath route.
     */
    static InputStream openMixinClassResource(String resourceName, ClassLoader... candidates) {
        Set<ClassLoader> tried = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ClassLoader candidate : candidates) {
            if (candidate == null || !tried.add(candidate)) {
                continue;
            }
            InputStream stream = candidate.getResourceAsStream(resourceName);
            if (stream != null) {
                return stream;
            }
        }
        return ClassLoader.getSystemResourceAsStream(resourceName);
    }

    private static String mixinClassResource(String mixinPackage, String mixinName) {
        if (mixinName == null || mixinName.isBlank() || mixinName.startsWith("@")) {
            return null;
        }

        String packagePath = mixinPackage == null ? "" : mixinPackage.replace('.', '/');
        String packageName = packagePath.replace('/', '.');
        String normalized;
        if (!packageName.isBlank() && mixinName.startsWith(packageName + ".")) {
            // Fully-qualified dotted class name.
            normalized = mixinName.substring(packageName.length() + 1).replace('.', '/');
        } else if (mixinName.startsWith("/")) {
            normalized = mixinName.substring(1).replace('.', '/');
        } else if (mixinName.contains("/")) {
            normalized = mixinName.replace('.', '/');
            if (!packagePath.isBlank() && !normalized.startsWith(packagePath + "/")) {
                // Slash-separated subpackage path relative to the config package.
                normalized = packagePath + "/" + normalized;
            }
        } else {
            // Relative names may contain dots to denote subpackages, e.g. ingredient.SomeMixin.
            normalized = mixinName.replace('.', '/');
            if (!packagePath.isBlank()) {
                normalized = packagePath + "/" + normalized;
            }
        }
        return normalized.endsWith(".class") ? normalized : normalized + ".class";
    }

    private static MixinClassMetadata readMixinMetadata(byte[] bytes) {
        int[] access = {0};
        String[] superNameHolder = {null};
        List<String> targets = new ArrayList<>();
        List<ShadowMember> shadowMembers = new ArrayList<>();
        List<AccessorTarget> accessorTargets = new ArrayList<>();
        List<MixinSelector> selectors = new ArrayList<>();
        List<MixinAtTarget> atTargets = readMixinAtTargets(bytes);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int classAccess, String name, String signature,
                              String superName, String[] interfaces) {
                access[0] = classAccess;
                superNameHolder[0] = superName;
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!name.equals("value") && !name.equals("targets")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (name.equals("value") && value instanceof Type type
                                        && type.getSort() == Type.OBJECT) {
                                    targets.add(type.getInternalName());
                                } else if (name.equals("targets") && value instanceof String target) {
                                    targets.add(target);
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int fieldAccess, String name, String descriptor,
                                           String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            private String prefix = "shadow$";

                            @Override
                            public void visit(String attribute, Object annotationValue) {
                                if (attribute.equals("prefix") && annotationValue instanceof String stringValue) {
                                    prefix = stringValue;
                                }
                            }

                            @Override
                            public void visitEnd() {
                                shadowMembers.add(new ShadowMember(false, name, descriptor, prefix));
                            }
                        };
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int methodAccess, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                private String prefix = "shadow$";

                                @Override
                                public void visit(String attribute, Object annotationValue) {
                                    if (attribute.equals("prefix") && annotationValue instanceof String stringValue) {
                                        prefix = stringValue;
                                    }
                                }

                                @Override
                                public void visitEnd() {
                                    shadowMembers.add(new ShadowMember(true, name, descriptor, prefix));
                                }
                            };
                        }
                        if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                            return accessorMetadataVisitor(accessorTargets, name, descriptor, false);
                        }
                        if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
                            return accessorMetadataVisitor(accessorTargets, name, descriptor, true);
                        }
                        return selectorMetadataVisitor(selectors, annotationDescriptor);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (targets.isEmpty()) {
            return null;
        }
        return new MixinClassMetadata((access[0] & Opcodes.ACC_INTERFACE) != 0,
                List.copyOf(targets), List.copyOf(shadowMembers), List.copyOf(selectors),
                List.copyOf(accessorTargets), superNameHolder[0], atTargets);
    }

    private static AnnotationVisitor accessorMetadataVisitor(List<AccessorTarget> members,
                                                             String methodName,
                                                             String methodDescriptor,
                                                             boolean invoker) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            private String explicitName;

            @Override
            public void visit(String attribute, Object value) {
                if (attribute.equals("value") && value instanceof String stringValue
                        && !stringValue.isBlank()) {
                    explicitName = stringValue;
                }
            }

            @Override
            public void visitEnd() {
                String targetName = explicitName == null
                        ? inferredAccessorName(methodName, invoker) : explicitName;
                String memberDescriptor = invoker ? methodDescriptor
                        : inferredAccessorDescriptor(methodDescriptor);
                if (targetName != null && memberDescriptor != null) {
                    members.add(new AccessorTarget(invoker, targetName, memberDescriptor));
                }
            }
        };
    }

    private static String inferredAccessorDescriptor(String methodDescriptor) {
        org.objectweb.asm.Type methodType = org.objectweb.asm.Type.getMethodType(methodDescriptor);
        org.objectweb.asm.Type[] arguments = methodType.getArgumentTypes();
        if (arguments.length == 0 && methodType.getReturnType().getSort() != org.objectweb.asm.Type.VOID) {
            return methodType.getReturnType().getDescriptor();
        }
        if (arguments.length == 1 && methodType.getReturnType().getSort() == org.objectweb.asm.Type.VOID) {
            return arguments[0].getDescriptor();
        }
        return null;
    }

    private static String inferredAccessorName(String methodName, boolean invoker) {
        String[] prefixes = invoker ? new String[]{"call", "invoke"}
                : new String[]{"get", "is", "set"};
        for (String prefix : prefixes) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()
                    && Character.isUpperCase(methodName.charAt(prefix.length()))) {
                String property = methodName.substring(prefix.length());
                if (property.length() > 1 && Character.isUpperCase(property.charAt(0))
                        && Character.isUpperCase(property.charAt(1))) {
                    return property;
                }
                return Character.toLowerCase(property.charAt(0)) + property.substring(1);
            }
        }
        return methodName;
    }

    /**
     * Collects method selectors together with their nested @At anchors. Mixin's generic annotation
     * visitor callbacks otherwise flatten these into unrelated strings, which is insufficient for
     * proving whether a NEW-expression anchor still exists in the target method.
     */
    private static List<MixinAtTarget> readMixinAtTargets(byte[] bytes) {
        List<MixinAtTarget> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        boolean injection = annotationDescriptor.startsWith(
                                "Lorg/spongepowered/asm/mixin/injection/")
                                || annotationDescriptor.startsWith("Lcom/llamalad7/mixinextras/injector/");
                        if (!injection) {
                            return null;
                        }

                        List<String> methodSelectors = new ArrayList<>();
                        List<PendingAtTarget> pendingTargets = new ArrayList<>();
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String elementName, Object value) {
                                if (elementName.equals("method") && value instanceof String selector) {
                                    methodSelectors.add(selector);
                                }
                            }

                            @Override
                            public AnnotationVisitor visitArray(String elementName) {
                                if (elementName.equals("method")) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String ignored, Object value) {
                                            if (value instanceof String selector) {
                                                methodSelectors.add(selector);
                                            }
                                        }
                                    };
                                }
                                if (elementName.equals("at")) {
                                    // MixinExtras injectors declare At[] rather than a single At.
                                    // Their nested annotations are delivered through visitArray/visitAnnotation,
                                    // so handle this form as well as the standard single-At form below.
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String ignored,
                                                                                 String nestedDescriptor) {
                                            if (!nestedDescriptor.equals(
                                                    "Lorg/spongepowered/asm/mixin/injection/At;")) {
                                                return null;
                                            }
                                            PendingAtTarget pending = new PendingAtTarget();
                                            pendingTargets.add(pending);
                                            return new AnnotationVisitor(Opcodes.ASM9) {
                                                @Override
                                                public void visit(String atElement, Object value) {
                                                    if (!(value instanceof String stringValue)) {
                                                        return;
                                                    }
                                                    if (atElement.equals("value")) {
                                                        pending.atValue = stringValue;
                                                    } else if (atElement.equals("target")) {
                                                        pending.target = stringValue;
                                                    }
                                                }
                                            };
                                        }
                                    };
                                }
                                return null;
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String elementName,
                                                                      String nestedDescriptor) {
                                if (!elementName.equals("at")
                                        || !nestedDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/At;")) {
                                    return null;
                                }
                                PendingAtTarget pending = new PendingAtTarget();
                                pendingTargets.add(pending);
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String atElement, Object value) {
                                        if (!(value instanceof String stringValue)) {
                                            return;
                                        }
                                        if (atElement.equals("value")) {
                                            pending.atValue = stringValue;
                                        } else if (atElement.equals("target")) {
                                            pending.target = stringValue;
                                        }
                                    }
                                };
                            }

                            @Override
                            public void visitEnd() {
                                if (methodSelectors.isEmpty()) {
                                    return;
                                }
                                for (PendingAtTarget pending : pendingTargets) {
                                    if (pending.target == null || pending.atValue == null) {
                                        continue;
                                    }
                                    for (String methodSelector : methodSelectors) {
                                        result.add(new MixinAtTarget(
                                                name, methodSelector, pending.atValue, pending.target));
                                    }
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return List.copyOf(result);
    }

    private static final class PendingAtTarget {
        private String atValue;
        private String target;
    }

    private static AnnotationVisitor selectorMetadataVisitor(List<MixinSelector> selectors,
                                                              String annotationDescriptor) {
        boolean atAnnotation = annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/At;");
        boolean injectionAnnotation = annotationDescriptor.startsWith(
                "Lorg/spongepowered/asm/mixin/injection/")
                || annotationDescriptor.startsWith("Lcom/llamalad7/mixinextras/injector/");
        if (!atAnnotation && !injectionAnnotation) {
            return null;
        }
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String stringValue
                        && ((atAnnotation && name.equals("target"))
                        || (injectionAnnotation && name.equals("method")))) {
                    selectors.add(new MixinSelector(stringValue));
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                if (!injectionAnnotation || !name.equals("method")) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String ignored, Object value) {
                        if (value instanceof String stringValue) {
                            selectors.add(new MixinSelector(stringValue));
                        }
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
                return selectorMetadataVisitor(selectors, nestedDescriptor);
            }
        };
    }

    private record ShadowMember(boolean method, String name, String descriptor, String prefix) {}
    private record AccessorTarget(boolean method, String name, String descriptor) {}
    private record MixinSelector(String value) {}
    private record MixinAtTarget(String mixinMethodName, String methodSelector, String atValue, String target) {}
    private record MixinClassMetadata(boolean isInterface, List<String> targets,
                                      List<ShadowMember> shadowMembers, List<MixinSelector> selectors,
                                      List<AccessorTarget> accessorTargets, String superName,
                                      List<MixinAtTarget> atTargets) {}

    static boolean isMixinResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".json")
                && (lower.contains("mixin") || lower.contains("refmap"));
    }

    private static boolean isMixinClassResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".class")
                && (lower.contains("/mixin/") || lower.endsWith("mixin.class")
                    || lower.contains("mixin$"));
    }

    static boolean isClassResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        return normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".class");
    }

    private static boolean isPotentialClassResourceName(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        if (normalized.endsWith(".class") || normalized.endsWith(".json")
                || normalized.endsWith(".png") || normalized.endsWith(".ogg")
                || normalized.endsWith(".properties") || normalized.endsWith(".txt")) {
            return false;
        }
        int separator = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('.'));
        if (separator < 0 || separator + 1 >= normalized.length()) {
            return false;
        }
        return Character.isUpperCase(normalized.charAt(separator + 1));
    }

    /**
     * Removes only injector callback methods whose explicit injection anchors are absent from the
     * configured target game. This also covers dynamically registered Mixins that do not appear in
     * a static JSON mixin list and therefore cannot be pruned at the resource boundary.
     */
    private static byte[] pruneIncompatibleInjectionMethods(byte[] original,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            TargetApiIndex targetApi) {
        if (original == null || mappings == null || targetApi == null) {
            return original;
        }
        MixinClassMetadata metadata = readMixinMetadata(original);
        if (metadata == null) {
            return original;
        }

        Set<String> methodsToRemove = new java.util.HashSet<>();
        Map<String, String> reasons = new java.util.LinkedHashMap<>();
        for (MixinAtTarget anchor : metadata.atTargets()) {
            String reason = injectionAnchorIncompatibility(
                    anchor, metadata.targets(), mappings, aliases, targetApi);
            if (reason != null) {
                methodsToRemove.add(anchor.mixinMethodName());
                reasons.putIfAbsent(anchor.mixinMethodName(), reason);
            }
        }

        // Some injectors use the default @At("HEAD") and therefore have no explicit At metadata.
        // Validate their method selectors too, so changed constructors/methods are removed before
        // Mixin's required-injection check can abort the entire client.
        collectIncompatibleInjectorMethods(original, metadata.targets(), mappings, aliases,
                targetApi, methodsToRemove, reasons);
        if (methodsToRemove.isEmpty()) {
            return original;
        }

        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (methodsToRemove.contains(name)) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        for (Map.Entry<String, String> entry : reasons.entrySet()) {
            System.err.println("[Gafi Loader] Removed incompatible Mixin injector method "
                    + entry.getKey() + " from " + reader.getClassName() + ": "
                    + entry.getValue() + ". Its behavior is not emulated.");
        }
        return writer.toByteArray();
    }

    private static void collectIncompatibleInjectorMethods(byte[] original, List<String> targets,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            TargetApiIndex targetApi, Set<String> methodsToRemove, Map<String, String> reasons) {
        if (targetApi == null) {
            return;
        }
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    private void checkSelector(String selector) {
                        String reason = MixinSelectorRemapper.incompatibilityReason(
                                selector, targets, mappings, aliases, targetApi);
                        if (reason != null) {
                            methodsToRemove.add(methodName);
                            reasons.putIfAbsent(methodName, reason
                                    + "; this injector's behavior is not emulated");
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!annotationDescriptor.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                                && !annotationDescriptor.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                            return null;
                        }
                        if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/At;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String name, Object value) {
                                if (name.equals("method") && value instanceof String selector) {
                                    checkSelector(selector);
                                }
                            }

                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                if (!name.equals("method")) {
                                    return null;
                                }
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String selector) {
                                            checkSelector(selector);
                                        }
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static String injectionAnchorIncompatibility(MixinAtTarget anchor, List<String> rawTargets,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            TargetApiIndex targetApi) {
        String methodName = MixinSelectorRemapper.normalizedMethodSelector(
                anchor.methodSelector(), rawTargets, mappings, aliases);
        String normalizedSelector = MixinSelectorRemapper.normalizedMethodSelectorWithDescriptor(
                anchor.methodSelector(), rawTargets, mappings, aliases, targetApi);
        String atValue = anchor.atValue();
        String mappedAnchor = LegacyClassAliases.mapDescriptor(
                remapText(anchor.target(), mappings, aliases), targetApi);

        // Validate the injector's target method independently of its @At point. HEAD/RETURN
        // injectors can be stale even when their injection point itself has no member selector.
        int selectorDescriptorStart = normalizedSelector == null ? -1 : normalizedSelector.indexOf('(');
        String selectorName = selectorDescriptorStart > 0
                ? normalizedSelector.substring(0, selectorDescriptorStart) : normalizedSelector;
        String selectorDescriptor = selectorDescriptorStart > 0
                ? normalizedSelector.substring(selectorDescriptorStart) : null;
        for (String rawTarget : rawTargets) {
            String normalizedTarget = rawTarget.replace('.', '/');
            if (normalizedTarget.startsWith("L") && normalizedTarget.endsWith(";")) {
                normalizedTarget = normalizedTarget.substring(1, normalizedTarget.length() - 1);
            }
            String target = aliases.mapClass(mappings.mapClassName(normalizedTarget));
            if (!targetApi.hasClass(target)) {
                if (target.startsWith("net/minecraft/")) {
                    return "Mixin target class " + target + " is absent from Minecraft 26.2";
                }
                continue;
            }
            boolean selectorExists = selectorDescriptor == null
                    ? targetApi.hasMethodNamed(target, selectorName)
                    : targetApi.methodAccess(target, selectorName, selectorDescriptor) != null;
            if (!selectorExists) {
                return "target selector " + normalizedSelector + " does not resolve on "
                        + target + " in Minecraft 26.2";
            }
        }

        if (atValue.equals("NEW")) {
            String allocatedType = null;
            String constructorDescriptor = null;
            String display = mappedAnchor;
            if (anchor.target().startsWith("(")) {
                try {
                    String descriptor = aliases.mapDescriptor(mappings.mapDescriptor(anchor.target()));
                    Type allocation = Type.getMethodType(descriptor).getReturnType();
                    if (allocation.getSort() == Type.OBJECT) {
                        allocatedType = allocation.getInternalName();
                        constructorDescriptor = Type.getMethodDescriptor(
                                Type.VOID_TYPE, Type.getMethodType(descriptor).getArgumentTypes());
                    }
                    display = descriptor;
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            } else if (mappedAnchor.startsWith("L")) {
                int ownerEnd = mappedAnchor.indexOf(';');
                if (ownerEnd > 1) {
                    String owner = mappedAnchor.substring(1, ownerEnd);
                    String member = mappedAnchor.substring(ownerEnd + 1);
                    if (member.startsWith("<init>(")) {
                        allocatedType = owner;
                        constructorDescriptor = member.substring("<init>".length());
                    } else if (member.isBlank()) {
                        allocatedType = owner;
                    }
                }
            } else {
                String normalized = mappedAnchor.replace('.', '/');
                if (normalized.startsWith("net/minecraft/")) {
                    allocatedType = normalized;
                }
            }
            if (allocatedType == null || !allocatedType.startsWith("net/minecraft/")) {
                return null;
            }
            for (String rawTarget : rawTargets) {
                String normalizedTarget = rawTarget.replace('.', '/');
                if (normalizedTarget.startsWith("L") && normalizedTarget.endsWith(";")) {
                    normalizedTarget = normalizedTarget.substring(1, normalizedTarget.length() - 1);
                }
                String target = aliases.mapClass(mappings.mapClassName(normalizedTarget));
                if (!targetApi.hasClass(target) || !targetApi.hasMethodNamed(target, methodName)) {
                    continue;
                }
                boolean found = constructorDescriptor == null
                        ? targetApi.hasNewExpressionOfType(target, methodName, allocatedType)
                        : targetApi.hasNewExpression(target, methodName, allocatedType, constructorDescriptor);
                if (!found) {
                    return "@At(value=\"NEW\", target=\"" + display + "\") has no matching allocation in "
                            + target + "#" + methodName + " in Minecraft 26.2";
                }
            }
            return null;
        }

        if (!(atValue.equals("INVOKE") || atValue.equals("INVOKE_ASSIGN")
                || atValue.equals("FIELD")) || !mappedAnchor.startsWith("L")) {
            return null;
        }
        int ownerEnd = mappedAnchor.indexOf(';');
        if (ownerEnd <= 1 || ownerEnd + 1 >= mappedAnchor.length()) {
            return null;
        }
        String anchorOwner = mappedAnchor.substring(1, ownerEnd);
        // Injection anchors can call library methods (for example DataFixerUpper), so only
        // validate global member existence when the owner belongs to the indexed game JAR.
        // The instruction itself can still be checked inside a Minecraft target method.
        boolean anchorOwnerIsGameClass = anchorOwner.startsWith("net/minecraft/");
        String member = mappedAnchor.substring(ownerEnd + 1);
        boolean field = atValue.equals("FIELD");
        String memberName;
        String descriptor;
        if (field) {
            int separator = member.indexOf(':');
            if (separator <= 0) {
                return null;
            }
            memberName = member.substring(0, separator);
            descriptor = member.substring(separator + 1);
            if (anchorOwnerIsGameClass
                    && targetApi.fieldAccess(anchorOwner, memberName, descriptor) == null) {
                return "@At(value=\"FIELD\", target=\"" + mappedAnchor
                        + "\") references a missing field in Minecraft 26.2";
            }
        } else {
            int descriptorStart = member.indexOf('(');
            if (descriptorStart <= 0) {
                return null;
            }
            memberName = member.substring(0, descriptorStart);
            descriptor = member.substring(descriptorStart);
            if (anchorOwnerIsGameClass
                    && targetApi.methodAccess(anchorOwner, memberName, descriptor) == null) {
                return "@At(value=\"" + atValue + "\", target=\"" + mappedAnchor
                        + "\") references a missing method in Minecraft 26.2";
            }
        }

        for (String rawTarget : rawTargets) {
            String normalizedTarget = rawTarget.replace('.', '/');
            if (normalizedTarget.startsWith("L") && normalizedTarget.endsWith(";")) {
                normalizedTarget = normalizedTarget.substring(1, normalizedTarget.length() - 1);
            }
            String target = aliases.mapClass(mappings.mapClassName(normalizedTarget));
            if (!targetApi.hasClass(target)) {
                if (target.startsWith("net/minecraft/")) {
                    return "Mixin target class " + target + " is absent from Minecraft 26.2";
                }
                continue;
            }
            if (!targetApi.hasMethodNamed(target, methodName)) {
                return "target method " + target + "#" + methodName
                        + " is absent from Minecraft 26.2";
            }
            boolean found = field
                    ? targetApi.hasFieldAccessInNamedMethod(target, methodName,
                            anchorOwner, memberName, descriptor)
                    : targetApi.hasInvocationInNamedMethod(target, methodName,
                            anchorOwner, memberName, descriptor);
            if (!found) {
                return "@At(value=\"" + atValue + "\", target=\"" + mappedAnchor
                        + "\") is absent from target method " + target + "#" + methodName
                        + " in Minecraft 26.2";
            }
        }
        return null;
    }

    /**
     * Normalize legacy bytecode when Mixin asks Fabric for class metadata. This is separate from
     * Instrumentation: Mixin can inspect a class before the JVM invokes the Java-agent transformer.
     * Returning the input on failure is intentional; the regular definition-time transformer will
     * still run and report any hard failure through the normal class-loading path.
     */
    public static byte[] transformClassBytes(String requestedClassName, byte[] original) {
        IntermediaryMappings currentMappings = mappings;
        if (original == null || currentMappings == null) {
            return original;
        }

        try {
            ClassReader reader = new ClassReader(original);
            String actualClassName = reader.getClassName();
            if (skipClassResource(actualClassName)) {
                return original;
            }

            // Mixin may register some entries dynamically, bypassing the static JSON pruning
            // hook. Remove only callback methods with proven-stale injection anchors before Mixin
            // inspects the class metadata.
            byte[] pruned = pruneIncompatibleInjectionMethods(original, currentMappings, aliases, targetApi);
            byte[] shadowRemapped = remapMixinShadowMembers(pruned, currentMappings, aliases, targetApi);
            // Mixin selector strings are ordinary annotation strings, so ASM's standard
            // class remapper will not normalize them. Convert the supported selector attributes
            // first, then remap the surrounding descriptors and Type values as usual.
            byte[] selectorsRemapped = MixinSelectorRemapper.remap(
                    shadowRemapped, currentMappings, aliases, targetApi);
            byte[] bytecodeInput = selectorsRemapped == null ? shadowRemapped : selectorsRemapped;
            byte[] remapped = IntermediaryBytecodeRemapper.remap(
                    bytecodeInput, currentMappings, aliases, targetApi);
            byte[] result = remapped != null ? remapped
                    : selectorsRemapped == null ? shadowRemapped : selectorsRemapped;
            byte[] overwriteAdjusted = addRequiredOverwriteAnnotations(result);
            if (overwriteAdjusted != original || remapped != null
                    || selectorsRemapped != null || shadowRemapped != pruned || pruned != original) {
                System.out.println("[Gafi Loader] Normalized legacy bytecode for Mixin inspection: "
                        + requestedClassName + " (" + actualClassName + ")");
            }
            return overwriteAdjusted;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Gafi Loader] Could not normalize class bytes for Mixin inspection: "
                    + requestedClassName + "; original bytes retained: " + exception.getMessage());
            return original;
        }
    }

    /**
     * Mixin's class-node provider is the path used by some loader versions to read Mixin
     * bytecode without calling the resource-stream hook. Serialize and normalize through the
     * target loader's own ASM classes so the returned ClassNode remains type-compatible.
     */
    public static Object transformClassNode(String requestedClassName, Object originalNode) {
        if (requestedClassName == null || originalNode == null || mappings == null) {
            return originalNode;
        }
        if (requestedClassName.contains("MinecraftServerMixin")) {
            System.out.println("[Gafi Loader] DEBUG ClassNode hook reached: " + requestedClassName);
        }
        try {
            ClassLoader loader = originalNode.getClass().getClassLoader();
            Class<?> visitorClass = Class.forName("org.objectweb.asm.ClassVisitor", false, loader);
            Class<?> writerClass = Class.forName("org.objectweb.asm.ClassWriter", false, loader);
            Object writer = writerClass.getConstructor(int.class).newInstance(0);
            originalNode.getClass().getMethod("accept", visitorClass).invoke(originalNode, writer);
            byte[] originalBytes = (byte[]) writerClass.getMethod("toByteArray").invoke(writer);
            byte[] transformedBytes = transformClassBytes(requestedClassName, originalBytes);
            if (requestedClassName.contains("MinecraftServerMixin")) {
                System.out.println("[Gafi Loader] DEBUG ClassNode bytes changed="
                        + (transformedBytes != originalBytes) + " length=" + originalBytes.length);
            }
            if (transformedBytes == originalBytes) {
                return originalNode;
            }

            Class<?> readerClass = Class.forName("org.objectweb.asm.ClassReader", false, loader);
            Object reader = readerClass.getConstructor(byte[].class).newInstance((Object) transformedBytes);
            Object transformedNode = originalNode.getClass().getConstructor().newInstance();
            readerClass.getMethod("accept", visitorClass, int.class).invoke(reader, transformedNode, 0);
            return transformedNode;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            System.err.println("[Gafi Loader] Could not normalize Mixin ClassNode " + requestedClassName
                    + "; original node retained: " + exception.getMessage());
            return originalNode;
        }
    }

    /**
     * Remaps @Shadow member declarations against their @Mixin targets. Normal bytecode remapping
     * sees the mixin class as the owner, but a shadow name belongs to the target game class.
     * Without this pass intermediary shadow names such as method_123 remain unchanged even when
     * the target's official method is known.
     */
    static byte[] remapMixinShadowMembers(byte[] original,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            TargetApiIndex targetApi) {
        if (original == null || mappings == null || targetApi == null) {
            return original;
        }
        MixinClassMetadata metadata = readMixinMetadata(original);
        if (metadata == null || metadata.shadowMembers().isEmpty() || metadata.targets().isEmpty()) {
            return original;
        }
        if (new ClassReader(original).getClassName().contains("MinecraftServerMixin")) {
            System.out.println("[Gafi Loader] DEBUG shadow metadata targets=" + metadata.targets()
                    + " members=" + metadata.shadowMembers());
        }

        Map<String, String> methodRenames = new java.util.HashMap<>();
        Map<String, String> fieldRenames = new java.util.HashMap<>();
        for (ShadowMember shadow : metadata.shadowMembers()) {
            String originalName = shadow.name();
            boolean hadPrefix = !shadow.prefix().isEmpty() && originalName.startsWith(shadow.prefix());
            String lookupName = hadPrefix ? originalName.substring(shadow.prefix().length()) : originalName;
            String resolvedName = null;
            boolean ambiguous = false;
            CompatibilityScanner.SymbolKind kind = shadow.method()
                    ? CompatibilityScanner.SymbolKind.METHOD : CompatibilityScanner.SymbolKind.FIELD;
            for (String rawTarget : metadata.targets()) {
                String owner = rawTarget.replace('.', '/');
                if (owner.startsWith("L") && owner.endsWith(";")) {
                    owner = owner.substring(1, owner.length() - 1);
                }
                CompatibilityScanner.Reference normalized = mappings.normalize(
                        new CompatibilityScanner.Reference(kind, owner, lookupName, shadow.descriptor()));
                CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
                String mappedOwner = LegacyClassAliases.mapClass(mapped.owner(), targetApi);
                String mappedDescriptor = LegacyClassAliases.mapDescriptor(mapped.descriptor(), targetApi);
                boolean exists = shadow.method()
                        ? targetApi.methodAccess(mappedOwner, mapped.name(), mappedDescriptor) != null
                        : targetApi.fieldAccess(mappedOwner, mapped.name(), mappedDescriptor) != null;
                if (!exists) {
                    ambiguous = true;
                    break;
                }
                if (resolvedName != null && !resolvedName.equals(mapped.name())) {
                    ambiguous = true;
                    break;
                }
                resolvedName = mapped.name();
            }
            if (ambiguous || resolvedName == null || resolvedName.equals(lookupName)) {
                continue;
            }
            String replacementName = hadPrefix ? shadow.prefix() + resolvedName : resolvedName;
            String key = originalName + shadow.descriptor();
            if (shadow.method()) {
                methodRenames.put(key, replacementName);
            } else {
                fieldRenames.put(key, replacementName);
            }
        }
        if (methodRenames.isEmpty() && fieldRenames.isEmpty()) {
            return original;
        }

        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                              String signature, Object value) {
                String mappedName = fieldRenames.getOrDefault(name + descriptor, name);
                return super.visitField(access, mappedName, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String mappedName = methodRenames.getOrDefault(name + descriptor, name);
                return super.visitMethod(access, mappedName, descriptor, signature, exceptions);
            }
        }, 0);
        System.out.println("[Gafi Loader] Remapped " + (methodRenames.size() + fieldRenames.size())
                + " @Shadow member declaration(s) for " + metadata.targets());
        return writer.toByteArray();
    }

    /**
     * In older game versions a Mixin can add a method that did not yet exist on its target.
     * If a newer target adds that exact signature, Mixin treats the unannotated method as an
     * accidental overwrite and rejects it. Mark the collision as @Overwrite in memory when the
     * same method signature exists on every declared target and staticness matches. The source
     * Mixin class and its containing JAR remain untouched.
     */
    private static byte[] addRequiredOverwriteAnnotations(byte[] original) {
        if (original == null || targetApi == null) {
            return original;
        }
        MixinClassMetadata metadata = readMixinMetadata(original);
        if (metadata == null || metadata.targets().isEmpty()) {
            return original;
        }

        Set<String> overwriteCandidates = new java.util.HashSet<>();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals("<init>") || name.equals("<clinit>")) {
                    return null;
                }
                boolean everyTargetDeclaresMethod = true;
                for (String target : metadata.targets()) {
                    String normalized = target.replace('.', '/');
                    if (normalized.startsWith("L") && normalized.endsWith(";")) {
                        normalized = normalized.substring(1, normalized.length() - 1);
                    }
                    normalized = LegacyClassAliases.mapClass(normalized, targetApi);
                    Integer targetAccess = targetApi.declaredMethodAccess(normalized, name, descriptor);
                    if (targetAccess == null
                            || ((targetAccess ^ access) & Opcodes.ACC_STATIC) != 0) {
                        everyTargetDeclaresMethod = false;
                        break;
                    }
                }
                if (everyTargetDeclaresMethod) {
                    overwriteCandidates.add(name + descriptor);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (overwriteCandidates.isEmpty()) {
            return original;
        }
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!overwriteCandidates.contains(name + descriptor)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean overwrite;
                    private boolean shadow;
                    private boolean injector;

                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                            overwrite = true;
                        } else if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/Shadow;")) {
                            shadow = true;
                        } else if (annotationDescriptor.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                                || annotationDescriptor.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                            injector = true;
                        }
                        return super.visitAnnotation(annotationDescriptor, visible);
                    }

                    @Override
                    public void visitEnd() {
                        if (!overwrite && !shadow && !injector) {
                            AnnotationVisitor annotation = super.visitAnnotation(
                                    "Lorg/spongepowered/asm/mixin/Overwrite;", true);
                            if (annotation != null) {
                                annotation.visitEnd();
                            }
                            changed.set(true);
                            System.out.println("[Gafi Loader] Preserved legacy-added Mixin method as @Overwrite: "
                                    + reader.getClassName() + "#" + name + descriptor);
                        }
                        super.visitEnd();
                    }
                };
            }
        }, 0);
        return changed.get() ? writer.toByteArray() : original;
    }

    private static boolean skipClassResource(String internalName) {
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")
                || internalName.startsWith("net/minecraft/")
                || internalName.startsWith("dev/gafipro/gafiloader/")
                || internalName.startsWith("org/objectweb/asm/");
    }

    private static JsonElement remapJson(JsonElement element, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases,
                                         AtomicBoolean changed) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return element;
            }
            String oldValue = primitive.getAsString();
            String newValue = remapText(oldValue, mappings, aliases);
            if (!newValue.equals(oldValue)) {
                changed.set(true);
                return new JsonPrimitive(newValue);
            }
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                output.add(remapJson(child, mappings, aliases, changed));
            }
            return output;
        }
        JsonObject output = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String oldKey = entry.getKey();
            String newKey = remapText(oldKey, mappings, aliases);
            if (!newKey.equals(oldKey)) {
                changed.set(true);
            }
            if (output.has(newKey)) {
                throw new IllegalArgumentException("Mixin JSON keys collide after mapping: " + newKey);
            }
            output.add(newKey, remapJson(entry.getValue(), mappings, aliases, changed));
        }
        return output;
    }

    private static String remapText(String input, IntermediaryMappings mappings,
                                    CompatibilityScanner.AliasSet aliases) {
        if (input == null || input.isEmpty()
                || (!input.contains("net/minecraft/") && !input.contains("net.minecraft."))) {
            return input;
        }

        StringBuilder output = new StringBuilder(input.length() + 16);
        int cursor = 0;
        while (cursor < input.length()) {
            if (input.startsWith("Lnet/minecraft/", cursor)) {
                int ownerStart = cursor + 1;
                int ownerEnd = input.indexOf(';', ownerStart);
                if (ownerEnd > ownerStart) {
                    String owner = input.substring(ownerStart, ownerEnd);
                    int memberStart = ownerEnd + 1;
                    int nameEnd = memberStart;
                    while (nameEnd < input.length() && isMemberNameCharacter(input.charAt(nameEnd))) {
                        nameEnd++;
                    }

                    if (nameEnd > memberStart && nameEnd < input.length()
                            && (input.charAt(nameEnd) == '(' || input.charAt(nameEnd) == ':')) {
                        String name = input.substring(memberStart, nameEnd);
                        char separator = input.charAt(nameEnd);
                        int descriptorStart = nameEnd;
                        int descriptorEnd = separator == '('
                                ? methodDescriptorEnd(input, descriptorStart)
                                : fieldDescriptorEnd(input, descriptorStart + 1);
                        if (descriptorEnd > descriptorStart) {
                            String descriptor = separator == ':'
                                    ? input.substring(descriptorStart + 1, descriptorEnd)
                                    : input.substring(descriptorStart, descriptorEnd);
                            CompatibilityScanner.SymbolKind kind = separator == ':'
                                    ? CompatibilityScanner.SymbolKind.FIELD
                                    : CompatibilityScanner.SymbolKind.METHOD;
                            CompatibilityScanner.Reference normalized = mappings.normalize(
                                    new CompatibilityScanner.Reference(kind, owner, name, descriptor));
                            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
                            output.append('L').append(mapped.owner()).append(';')
                                    .append(mapped.name());
                            if (separator == ':') {
                                output.append(':').append(mapped.descriptor());
                            } else {
                                output.append(mapped.descriptor());
                            }
                            cursor = descriptorEnd;
                            continue;
                        }
                    }

                    output.append('L').append(mapClass(owner, mappings, aliases)).append(';');
                    cursor = ownerEnd + 1;
                    continue;
                }
            }

            Matcher internalMatcher = INTERNAL_CLASS.matcher(input);
            internalMatcher.region(cursor, input.length());
            if (internalMatcher.lookingAt()) {
                String oldClass = internalMatcher.group();
                output.append(mapClass(oldClass, mappings, aliases));
                cursor = internalMatcher.end();
                continue;
            }

            Matcher dottedMatcher = DOTTED_CLASS.matcher(input);
            dottedMatcher.region(cursor, input.length());
            if (dottedMatcher.lookingAt()) {
                String oldClass = dottedMatcher.group().replace('.', '/');
                output.append(mapClass(oldClass, mappings, aliases).replace('/', '.'));
                cursor = dottedMatcher.end();
                continue;
            }

            output.append(input.charAt(cursor));
            cursor++;
        }
        return output.toString();
    }

    private static String mapClass(String internalName, IntermediaryMappings mappings,
                                   CompatibilityScanner.AliasSet aliases) {
        String mappedName = mappings.mapClassName(internalName);
        return aliases.normalize(new CompatibilityScanner.Reference(
                CompatibilityScanner.SymbolKind.CLASS, mappedName, null, null)).owner();
    }

    private static boolean isMemberNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$'
                || value == '<' || value == '>';
    }

    private static int methodDescriptorEnd(String value, int start) {
        if (start >= value.length() || value.charAt(start) != '(') {
            return -1;
        }
        int cursor = start + 1;
        while (cursor < value.length() && value.charAt(cursor) != ')') {
            int next = typeDescriptorEnd(value, cursor, false);
            if (next <= cursor) {
                return -1;
            }
            cursor = next;
        }
        if (cursor >= value.length() || value.charAt(cursor) != ')') {
            return -1;
        }
        return typeDescriptorEnd(value, cursor + 1, true);
    }

    private static int fieldDescriptorEnd(String value, int start) {
        return typeDescriptorEnd(value, start, false);
    }

    private static int typeDescriptorEnd(String value, int start, boolean allowVoid) {
        int cursor = start;
        while (cursor < value.length() && value.charAt(cursor) == '[') {
            cursor++;
        }
        if (cursor >= value.length()) {
            return -1;
        }
        char type = value.charAt(cursor);
        if ("BCDFIJSZ".indexOf(type) >= 0 || (allowVoid && type == 'V')) {
            return cursor + 1;
        }
        if (type == 'L') {
            int end = value.indexOf(';', cursor + 1);
            return end < 0 ? -1 : end + 1;
        }
        return -1;
    }
}
