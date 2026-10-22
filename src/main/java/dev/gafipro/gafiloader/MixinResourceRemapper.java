package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.objectweb.asm.ClassReader;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
                || (!isMixinResource(resourceName) && !classResource)) {
            return original;
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

        if (classResource) {
            return new ByteArrayInputStream(transformClassBytes(resourceName, source));
        }

        try {
            JsonElement root = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            AtomicBoolean changed = new AtomicBoolean(false);
            JsonElement remapped = remapJson(root, currentMappings, aliases, changed);
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
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }

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
                if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                    String mixinName = entry.getAsString();
                    String classResource = mixinClassResource(mixinPackage, mixinName);
                    if (classResource != null) {
                        try (InputStream input = loader.getResourceAsStream(classResource)) {
                            if (input != null) {
                                MixinClassMetadata metadata = readMixinMetadata(input.readAllBytes());
                                if (metadata != null) {
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
                                            if (selectorProblem != null) {
                                                incompatibility = selectorProblem;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (IOException | RuntimeException exception) {
                            System.err.println("[Gafi Loader] Could not inspect mixin structure for "
                                    + mixinName + " in " + resourceName + ": " + exception.getMessage());
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
        return pruned;
    }

    private static String mixinClassResource(String mixinPackage, String mixinName) {
        if (mixinName == null || mixinName.isBlank() || mixinName.startsWith("@")) {
            return null;
        }
        String normalized = mixinName.replace('.', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        } else if (!normalized.contains("/") && mixinName.indexOf('.') < 0) {
            normalized = mixinPackage.isBlank() ? normalized : mixinPackage + "/" + normalized;
        } else if (!normalized.contains("/") && !mixinPackage.isBlank()) {
            normalized = mixinPackage + "/" + normalized;
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
                List.copyOf(accessorTargets), superNameHolder[0]);
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
    private record MixinClassMetadata(boolean isInterface, List<String> targets,
                                      List<ShadowMember> shadowMembers, List<MixinSelector> selectors,
                                      List<AccessorTarget> accessorTargets, String superName) {}

    static boolean isMixinResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".json")
                && (lower.contains("mixin") || lower.contains("refmap"));
    }

    static boolean isClassResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        return normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".class");
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

            // Mixin selector strings are ordinary annotation strings, so ASM's standard
            // class remapper will not normalize them. Convert the supported selector attributes
            // first, then remap the surrounding descriptors and Type values as usual.
            byte[] selectorsRemapped = MixinSelectorRemapper.remap(original, currentMappings, aliases);
            byte[] bytecodeInput = selectorsRemapped == null ? original : selectorsRemapped;
            byte[] remapped = IntermediaryBytecodeRemapper.remap(
                    bytecodeInput, currentMappings, aliases, targetApi);
            if (remapped == null && selectorsRemapped == null) {
                return original;
            }
            System.out.println("[Gafi Loader] Normalized legacy bytecode for Mixin inspection: "
                    + requestedClassName + " (" + actualClassName + ")");
            return remapped == null ? selectorsRemapped : remapped;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Gafi Loader] Could not normalize class bytes for Mixin inspection: "
                    + requestedClassName + "; original bytes retained: " + exception.getMessage());
            return original;
        }
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
