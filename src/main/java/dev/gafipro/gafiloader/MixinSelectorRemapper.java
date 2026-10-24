package dev.gafipro.gafiloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remaps selector strings stored in Mixin annotations before Mixin parses the old bytecode.
 * ASM's normal ClassRemapper remaps descriptors and Type annotation values, but does not rewrite
 * arbitrary strings such as @Inject(method = "method_123(Lnet/minecraft/class_1;)V").
 *
 * <p>This adapter only touches recognized selector-bearing annotation attributes. It neither
 * rewrites arbitrary string constants nor modifies the source class/JAR.</p>
 */
final class MixinSelectorRemapper {
    private static final int API = Opcodes.ASM9;
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String SPONGE_INJECTION_PREFIX =
            "Lorg/spongepowered/asm/mixin/injection/";
    private static final String MIXINEXTRAS_INJECTOR_PREFIX =
            "Lcom/llamalad7/mixinextras/injector/";

    private MixinSelectorRemapper() {}

    /** @return transformed bytes, or null when no selector/class string was changed */
    static byte[] remap(byte[] original, IntermediaryMappings mappings,
                        CompatibilityScanner.AliasSet aliases) {
        return remap(original, mappings, aliases, null);
    }

    static byte[] remap(byte[] original, IntermediaryMappings mappings,
                        CompatibilityScanner.AliasSet aliases, TargetApiIndex targetApi) {
        if (original == null || mappings == null || aliases == null) {
            return null;
        }

        List<String> targets = readMixinTargets(original);
        if (targets.isEmpty()) {
            return null;
        }

        AtomicBoolean changed = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return wrap(super.visitAnnotation(descriptor, visible), descriptor, targets,
                        mappings, aliases, changed, targetApi, null);
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                              String signature, Object value) {
                org.objectweb.asm.FieldVisitor field =
                        super.visitField(access, name, descriptor, signature, value);
                return new org.objectweb.asm.FieldVisitor(API, field) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return wrap(super.visitAnnotation(annotationDescriptor, visible), annotationDescriptor,
                                targets, mappings, aliases, changed, targetApi, descriptor);
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor, boolean visible) {
                        return wrap(super.visitTypeAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed, targetApi, descriptor);
                    }
                };
            }

            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor method =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                return new org.objectweb.asm.MethodVisitor(API, method) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return wrap(super.visitAnnotation(annotationDescriptor, visible), annotationDescriptor,
                                targets, mappings, aliases, changed, targetApi, descriptor);
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor, boolean visible) {
                        return wrap(super.visitTypeAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed, targetApi, null);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor,
                                                                       boolean visible) {
                        return wrap(super.visitParameterAnnotation(parameter, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed, targetApi, descriptor);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                        return wrap(super.visitInsnAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed, targetApi, descriptor);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitTryCatchAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                        return wrap(super.visitTryCatchAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed, targetApi, descriptor);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, org.objectweb.asm.Label[] start,
                            org.objectweb.asm.Label[] end, int[] index, String annotationDescriptor,
                            boolean visible) {
                        return wrap(super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index,
                                annotationDescriptor, visible), annotationDescriptor, targets,
                                mappings, aliases, changed, targetApi, descriptor);
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return changed.get() ? writer.toByteArray() : null;
    }

    private static List<String> readMixinTargets(byte[] original) {
        List<String> targets = new ArrayList<>();
        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(API) {
                    @Override
                    public void visit(String name, Object value) {
                        if (name.equals("targets") && value instanceof String target) {
                            addTarget(targets, target);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!name.equals("value") && !name.equals("targets")) {
                            return null;
                        }
                        return new AnnotationVisitor(API) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (name.equals("value") && value instanceof Type type
                                        && type.getSort() == Type.OBJECT) {
                                    addTarget(targets, type.getInternalName());
                                } else if (name.equals("targets") && value instanceof String target) {
                                    addTarget(targets, target);
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return List.copyOf(targets);
    }

    private static void addTarget(List<String> targets, String target) {
        if (target == null || target.isBlank()) {
            return;
        }
        String normalized = target.replace('.', '/');
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!targets.contains(normalized)) {
            targets.add(normalized);
        }
    }

    private static AnnotationVisitor wrap(AnnotationVisitor delegate, String annotationDescriptor,
                                          List<String> targets, IntermediaryMappings mappings,
                                          CompatibilityScanner.AliasSet aliases,
                                          AtomicBoolean changed, TargetApiIndex targetApi,
                                          String enclosingMethodDescriptor) {
        if (delegate == null) {
            return null;
        }
        return new AnnotationVisitor(API, delegate) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String stringValue) {
                    String mapped = mapStringValue(annotationDescriptor, name, stringValue,
                            targets, mappings, aliases, enclosingMethodDescriptor, targetApi);
                    if (!mapped.equals(stringValue)) {
                        changed.set(true);
                    }
                    super.visit(name, mapped);
                } else {
                    super.visit(name, value);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor array = super.visitArray(name);
                if (array == null) {
                    return null;
                }
                return new AnnotationVisitor(API, array) {
                    @Override
                    public void visit(String ignored, Object value) {
                        if (value instanceof String stringValue) {
                            String mapped = mapStringValue(annotationDescriptor, name, stringValue,
                                    targets, mappings, aliases, enclosingMethodDescriptor, targetApi);
                            if (!mapped.equals(stringValue)) {
                                changed.set(true);
                            }
                            super.visit(ignored, mapped);
                        } else {
                            super.visit(ignored, value);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String nestedName, String nestedDescriptor) {
                        return wrap(super.visitAnnotation(nestedName, nestedDescriptor), nestedDescriptor,
                                targets, mappings, aliases, changed, targetApi, enclosingMethodDescriptor);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String nestedName) {
                        return wrap(super.visitArray(nestedName), annotationDescriptor,
                                targets, mappings, aliases, changed, targetApi, enclosingMethodDescriptor);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
                return wrap(super.visitAnnotation(name, nestedDescriptor), nestedDescriptor,
                        targets, mappings, aliases, changed, targetApi, enclosingMethodDescriptor);
            }
        };
    }

    private static String mapStringValue(String annotationDescriptor, String elementName, String value,
                                         List<String> targets, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases, String enclosingMethodDescriptor,
                                         TargetApiIndex targetApi) {
        if (value.isEmpty()) {
            return value;
        }

        if (MIXIN.equals(annotationDescriptor) && elementName.equals("targets")) {
            String internal = value.replace('.', '/');
            String mapped = aliases.mapClass(mappings.mapClassName(internal));
            return value.indexOf('/') >= 0 ? mapped : mapped.replace('/', '.');
        }

        if (elementName.equals("value") && annotationDescriptor.equals(ACCESSOR)) {
            return remapAccessorOrInvoker(value, targets, mappings, aliases,
                    enclosingMethodDescriptor, false);
        }
        if (elementName.equals("value") && annotationDescriptor.equals(INVOKER)) {
            return remapAccessorOrInvoker(value, targets, mappings, aliases,
                    enclosingMethodDescriptor, true);
        }

        boolean methodSelector = elementName.equals("method")
                && (annotationDescriptor.startsWith(SPONGE_INJECTION_PREFIX)
                    || annotationDescriptor.startsWith(MIXINEXTRAS_INJECTOR_PREFIX));
        boolean atTarget = AT.equals(annotationDescriptor) && elementName.equals("target");
        if (!methodSelector && !atTarget) {
            return value;
        }
        if (methodSelector && value.contains("handleRespawn")) {
            System.out.println("[Gafi Loader] DEBUG respawn selector seen value=" + value
                    + " callback=" + enclosingMethodDescriptor + " targets=" + targets
                    + " annotation=" + annotationDescriptor);
        }
        String mapped = remapSelector(value, targets, mappings, aliases);
        if (targetApi != null) {
            mapped = remapSelectorClassAliases(mapped, targetApi);
        }
        if (methodSelector && targetApi != null) {
            mapped = remapUniqueDescriptorMatch(mapped, targets, mappings, aliases,
                    enclosingMethodDescriptor, targetApi);
            mapped = remapKnownRespawnSelector(mapped, targets, mappings, aliases,
                    enclosingMethodDescriptor, targetApi);
        }
        // NEW selectors use a constructor descriptor in the form (args)ReturnType, not the
        // owner;member syntax handled by remapSelector. Leaving intermediary class names here
        // makes Mixin scan zero allocations even when the target allocation exists.
        if (atTarget && value.startsWith("(")) {
            mapped = LegacyClassAliases.mapDescriptor(
                    aliases.mapDescriptor(mappings.mapDescriptor(value)), targetApi);
        }
        return atTarget ? remapKnownTargetSelector(mapped, targetApi) : mapped;
    }

    /**
     * Applies verified game-side class moves inside selector owners and descriptors. These
     * names are not part of Intermediary's namespace map, so normal member remapping alone
     * cannot update selectors that mention moved model classes.
     */
    private static String remapSelectorClassAliases(String selector, TargetApiIndex targetApi) {
        if (selector == null || selector.isBlank() || targetApi == null) {
            return selector;
        }
        String optional = selector.startsWith("?") ? "?" : "";
        String value = optional.isEmpty() ? selector : selector.substring(1);
        if (value.startsWith("(")) {
            return optional + LegacyClassAliases.mapDescriptor(value, targetApi);
        }
        if (value.startsWith("L")) {
            int ownerEnd = value.indexOf(';');
            if (ownerEnd <= 1 || ownerEnd + 1 >= value.length()) {
                return selector;
            }
            String owner = value.substring(1, ownerEnd);
            String member = value.substring(ownerEnd + 1);
            String mappedOwner = LegacyClassAliases.mapClass(owner, targetApi);
            int methodStart = member.indexOf('(');
            if (methodStart >= 0) {
                member = member.substring(0, methodStart)
                        + LegacyClassAliases.mapDescriptor(member.substring(methodStart), targetApi);
            } else {
                int fieldSeparator = member.indexOf(':');
                if (fieldSeparator >= 0) {
                    member = member.substring(0, fieldSeparator + 1)
                            + LegacyClassAliases.mapDescriptor(member.substring(fieldSeparator + 1), targetApi);
                }
            }
            return optional + "L" + mappedOwner + ";" + member;
        }
        int descriptorStart = value.indexOf('(');
        if (descriptorStart > 0) {
            return optional + value.substring(0, descriptorStart)
                    + LegacyClassAliases.mapDescriptor(value.substring(descriptorStart), targetApi);
        }
        return selector;
    }

    /**
     * If a legacy selector's method name changed but its exact descriptor remains unique on
     * the 26.2 target, replace only that name. Never guess when multiple target methods share
     * the descriptor, and leave selectors without a descriptor untouched.
     */
    private static String remapUniqueDescriptorMatch(String selector, List<String> targets,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            String callbackDescriptor, TargetApiIndex targetApi) {
        if (selector == null || selector.isBlank() || targetApi == null) {
            return selector;
        }
        String optionalPrefix = selector.startsWith("?") ? "?" : "";
        String value = optionalPrefix.isEmpty() ? selector : selector.substring(1);
        String owner = null;
        String member = value;
        if (value.startsWith("L")) {
            int ownerEnd = value.indexOf(';');
            if (ownerEnd <= 1 || ownerEnd + 1 >= value.length()) {
                return selector;
            }
            owner = value.substring(1, ownerEnd);
            member = value.substring(ownerEnd + 1);
        }
        int descriptorStart = member.indexOf('(');
        if (descriptorStart == 0) {
            return selector;
        }
        String name = descriptorStart < 0 ? member : member.substring(0, descriptorStart);
        String descriptor = descriptorStart < 0
                ? inferTargetDescriptor(callbackDescriptor, mappings, aliases, targetApi)
                : member.substring(descriptorStart);
        if (descriptor == null) {
            return selector;
        }
        if (name.contains("*") || name.contains("/") || name.contains(";")
                || targetApi.declaredMethodAccess(owner == null ? "" : owner, name, descriptor) != null) {
            return selector;
        }
        List<String> owners = new ArrayList<>();
        if (owner != null) {
            owners.add(owner);
        } else {
            for (String rawTarget : targets) {
                String normalized = rawTarget.replace('.', '/');
                if (normalized.startsWith("L") && normalized.endsWith(";")) {
                    normalized = normalized.substring(1, normalized.length() - 1);
                }
                owners.add(aliases.mapClass(mappings.mapClassName(normalized)));
            }
        }
        String replacement = null;
        for (String candidateOwner : owners) {
            if (!targetApi.hasClass(candidateOwner)) {
                return selector;
            }
            String candidate = targetApi.uniqueDeclaredMethodName(candidateOwner, descriptor);
            if (candidate == null || (replacement != null && !replacement.equals(candidate))) {
                return selector;
            }
            replacement = candidate;
        }
        if (replacement == null || replacement.equals(name)) {
            return selector;
        }
        if (descriptorStart < 0) {
            return optionalPrefix + (owner == null ? replacement : "L" + owner + ";" + replacement);
        }
        String mapped = owner == null ? replacement + descriptor
                : "L" + owner + ";" + replacement + descriptor;
        return optionalPrefix + mapped;
    }

    /**
     * Fabric API 1.21.11 names this callback's target through a selector that does not always
     * map through the implementation owner (the mapping is declared on the listener interface).
     * Preserve the behavior by using the verified 26.2 handler with the same packet signature.
     */
    private static String remapKnownRespawnSelector(String selector, List<String> targets,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            String callbackDescriptor, TargetApiIndex targetApi) {
        if (selector == null || callbackDescriptor == null || targetApi == null) {
            return selector;
        }
        String mappedCallback = LegacyClassAliases.mapDescriptor(
                aliases.mapDescriptor(mappings.mapDescriptor(callbackDescriptor)), targetApi);
        if (selector.contains("handleRespawn")) {
            System.out.println("[Gafi Loader] DEBUG respawn callback raw=" + callbackDescriptor
                    + " mapped=" + mappedCallback);
        }
        Type callbackType;
        try {
            callbackType = Type.getMethodType(mappedCallback);
        } catch (IllegalArgumentException exception) {
            return selector;
        }
        Type[] arguments = callbackType.getArgumentTypes();
        if (callbackType.getReturnType().getSort() != Type.VOID || arguments.length != 2
                || arguments[0].getSort() != Type.OBJECT
                || !arguments[0].getInternalName().equals(
                        "net/minecraft/network/protocol/game/ClientboundRespawnPacket")
                || arguments[1].getSort() != Type.OBJECT
                || !arguments[1].getInternalName().equals(
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
            return selector;
        }
        String owner = "net/minecraft/client/multiplayer/ClientPacketListener";
        boolean targetsListener = false;
        for (String rawTarget : targets) {
            String normalized = rawTarget.replace('.', '/');
            if (normalized.startsWith("L") && normalized.endsWith(";")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            String mappedOwner = LegacyClassAliases.mapClass(
                    aliases.mapClass(mappings.mapClassName(normalized)), targetApi);
            if (owner.equals(mappedOwner)) {
                targetsListener = true;
                break;
            }
        }
        String packetDescriptor = arguments[0].getDescriptor();
        boolean targetClassPresent = targetApi.hasClass(owner);
        System.out.println("[Gafi Loader] DEBUG respawn selector=" + selector
                + " callback=" + mappedCallback + " targets=" + targets
                + " listenerTarget=" + targetsListener + " targetClassPresent=" + targetClassPresent);
        if (!targetsListener || !targetClassPresent) {
            System.out.println("[Gafi Loader] DEBUG respawn selector left unchanged: target class was not resolved.");
            return selector;
        }
        // The target handler may have acquired additional arguments in 26.2. The legacy
        // callback only consumes the packet argument, which Mixin permits; match the unique
        // void handler by its first argument instead of requiring an obsolete exact descriptor.
        String targetMethodName = targetApi.uniqueVoidMethodNameByFirstArgument(owner, packetDescriptor);
        System.out.println("[Gafi Loader] DEBUG respawn candidate method=" + targetMethodName
                + " packetDescriptor=" + packetDescriptor);
        if (targetMethodName == null) {
            return selector;
        }
        String optional = selector.startsWith("?") ? "?" : "";
        String value = optional.isEmpty() ? selector : selector.substring(1);
        if (value.startsWith("L")) {
            int ownerEnd = value.indexOf(';');
            if (ownerEnd > 1) {
                return optional + value.substring(0, ownerEnd + 1) + targetMethodName;
            }
        }
        return optional + targetMethodName;
    }

    /**
     * Infers an @Inject target descriptor from its callback when the legacy selector contains
     * only a method name. This is safe only for ordinary void @Inject callbacks whose final
     * parameter is Mixin's CallbackInfo; ambiguous target signatures remain unchanged.
     */
    private static String inferTargetDescriptor(String callbackDescriptor,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            TargetApiIndex targetApi) {
        if (callbackDescriptor == null || targetApi == null) {
            return null;
        }
        String mapped = LegacyClassAliases.mapDescriptor(
                aliases.mapDescriptor(mappings.mapDescriptor(callbackDescriptor)), targetApi);
        Type callbackType;
        try {
            callbackType = Type.getMethodType(mapped);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (callbackType.getReturnType().getSort() != Type.VOID) {
            return null;
        }
        Type[] arguments = callbackType.getArgumentTypes();
        if (arguments.length == 0) {
            return null;
        }
        Type last = arguments[arguments.length - 1];
        if (last.getSort() != Type.OBJECT
                || !(last.getInternalName().equals(
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                    || last.getInternalName().equals(
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable"))) {
            return null;
        }
        Type[] targetArguments = java.util.Arrays.copyOf(arguments, arguments.length - 1);
        return Type.getMethodDescriptor(Type.VOID_TYPE, targetArguments);
    }

    /**
     * Fabric API 1.21.11 injected into MultiPlayerGameMode.interactAt. In the newer
     * target game, the same use-entity flow is anchored on interact. Only apply this reviewed
     * selector adaptation when the target JAR proves the replacement signature exists.
     */
    private static String remapKnownTargetSelector(String selector, TargetApiIndex targetApi) {
        if (targetApi == null || selector == null || selector.isBlank()) {
            return selector;
        }
        String optional = selector.startsWith("?") ? "?" : "";
        String value = optional.isEmpty() ? selector : selector.substring(1);
        String owner = "net/minecraft/client/multiplayer/MultiPlayerGameMode";
        String prefix = "L" + owner + ";interactAt";
        if (!value.startsWith(prefix)) {
            return selector;
        }
        String descriptor = value.substring(prefix.length());
        if (!descriptor.startsWith("(") || !targetApi.hasClass(owner)
                || targetApi.methodAccess(owner, "interact", descriptor) == null) {
            return selector;
        }
        return optional + "L" + owner + ";interact" + descriptor;
    }

    private static String remapAccessorOrInvoker(String value, List<String> targets,
                                                 IntermediaryMappings mappings,
                                                 CompatibilityScanner.AliasSet aliases,
                                                 String enclosingMethodDescriptor,
                                                 boolean invoker) {
        if (enclosingMethodDescriptor == null || value.isBlank()
                || value.indexOf('(') >= 0 || value.indexOf(':') >= 0) {
            return value;
        }

        String memberDescriptor;
        CompatibilityScanner.SymbolKind kind;
        if (invoker) {
            memberDescriptor = enclosingMethodDescriptor;
            kind = CompatibilityScanner.SymbolKind.METHOD;
        } else {
            Type accessor = Type.getMethodType(enclosingMethodDescriptor);
            Type[] arguments = accessor.getArgumentTypes();
            if (arguments.length == 0 && accessor.getReturnType().getSort() != Type.VOID) {
                memberDescriptor = accessor.getReturnType().getDescriptor();
            } else if (arguments.length == 1 && accessor.getReturnType().getSort() == Type.VOID) {
                memberDescriptor = arguments[0].getDescriptor();
            } else {
                // This is not a simple field getter or setter; leave Mixin's normal inference alone.
                return value;
            }
            kind = CompatibilityScanner.SymbolKind.FIELD;
        }

        String result = null;
        for (String owner : targets) {
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(kind, owner, value, memberDescriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            if (result != null && !result.equals(mapped.name())) {
                return value;
            }
            result = mapped.name();
        }
        return result == null ? value : result;
    }

    /**
     * Returns a reason when a fully specified Minecraft member selector cannot resolve in the
     * current target game. Unsupported/wildcard selectors are left to Mixin's own parser.
     */
    static String incompatibilityReason(String selector, List<String> targets,
                                        IntermediaryMappings mappings,
                                        CompatibilityScanner.AliasSet aliases,
                                        TargetApiIndex targetApi) {
        if (selector == null || selector.isBlank() || targetApi == null) {
            return null;
        }
        boolean optional = selector.startsWith("?");
        String unwrapped = optional ? selector.substring(1) : selector;
        String normalizedSelector = remapSelectorClassAliases(
                remapSelector(unwrapped, targets, mappings, aliases), targetApi);
        String mapped = remapKnownTargetSelector(
                remapUniqueDescriptorMatch(normalizedSelector, targets, mappings, aliases,
                        null, targetApi), targetApi);
        if (mapped == null || mapped.equals(unwrapped)) {
            // A selector that only needs descriptor normalization may still change even if the
            // member name stays the same; remapSelector handles that. If no full signature exists,
            // we deliberately do not guess.
        }

        if (mapped.startsWith("L")) {
            int ownerEnd = mapped.indexOf(';');
            if (ownerEnd <= 1 || ownerEnd + 1 >= mapped.length()) {
                return null;
            }
            String owner = mapped.substring(1, ownerEnd);
            if (!owner.startsWith("net/minecraft/")) {
                return null;
            }
            String member = mapped.substring(ownerEnd + 1);
            ParsedSelector parsed = parseMember(member);
            if (parsed == null) {
                return null;
            }
            if (!targetApi.hasClass(owner)) {
                return optional ? null : "selector owner class " + owner + " is absent from Minecraft 26.2";
            }
            // Mixin injectors are applied to methods declared by the target class itself;
            // a same-signature method inherited from a superclass is not an injection target.
            boolean exists = parsed.method()
                    ? (parsed.descriptor() == null
                        ? targetApi.hasDeclaredMethodNamed(owner, parsed.name())
                        : targetApi.declaredMethodAccess(owner, parsed.name(), parsed.descriptor()) != null)
                    : targetApi.fieldAccess(owner, parsed.name(), parsed.descriptor()) != null;
            if (!exists && !optional) {
                return "selector " + mapped + " does not exist in Minecraft 26.2";
            }
            return null;
        }

        int descriptorStart = mapped.indexOf('(');
        if (descriptorStart <= 0) {
            if (mapped.contains("*") || mapped.contains("/") || mapped.contains(";")
                    || mapped.startsWith("<") || mapped.contains(",")) {
                return null;
            }
            for (String rawTarget : targets) {
                String owner = aliases.mapClass(mappings.mapClassName(rawTarget));
                if (!owner.startsWith("net/minecraft/")) {
                    continue;
                }
                if (targetApi.hasClass(owner) && !targetApi.hasMethodNamed(owner, mapped) && !optional) {
                    return "selector " + mapped + " does not resolve on " + owner
                            + " in Minecraft 26.2";
                }
            }
            return null;
        }
        String name = mapped.substring(0, descriptorStart);
        String descriptor = mapped.substring(descriptorStart);
        if (name.contains("*") || name.contains("/") || name.contains(";")) {
            return null;
        }
        for (String rawTarget : targets) {
            String owner = aliases.mapClass(mappings.mapClassName(rawTarget));
            if (!owner.startsWith("net/minecraft/")) {
                continue;
            }
            if (!targetApi.hasClass(owner)) {
                if (!optional) {
                    return "target class " + owner + " is absent from Minecraft 26.2";
                }
                continue;
            }
            if (targetApi.declaredMethodAccess(owner, name, descriptor) == null && !optional) {
                return "selector " + name + descriptor + " does not resolve to a method declared on "
                        + owner + " in Minecraft 26.2";
            }
        }
        return null;
    }

    private static ParsedSelector parseMember(String selector) {
        int methodStart = selector.indexOf('(');
        int fieldSeparator = selector.indexOf(':');
        if (methodStart > 0 && (fieldSeparator < 0 || methodStart < fieldSeparator)) {
            return new ParsedSelector(true, selector.substring(0, methodStart),
                    selector.substring(methodStart));
        }
        if (fieldSeparator > 0) {
            return new ParsedSelector(false, selector.substring(0, fieldSeparator),
                    selector.substring(fieldSeparator + 1));
        }
        if (!selector.isBlank() && !selector.contains("*") && !selector.contains("/")) {
            return new ParsedSelector(true, selector, null);
        }
        return null;
    }

    private record ParsedSelector(boolean method, String name, String descriptor) {}

    static String normalizedMethodSelector(String selector, List<String> targets,
                                            IntermediaryMappings mappings,
                                            CompatibilityScanner.AliasSet aliases) {
        if (selector == null || selector.isBlank()) {
            return selector;
        }
        String mapped = remapSelector(selector, targets, mappings, aliases);
        if (mapped.startsWith("?")) {
            mapped = mapped.substring(1);
        }
        if (mapped.startsWith("L")) {
            int ownerEnd = mapped.indexOf(';');
            if (ownerEnd >= 0 && ownerEnd + 1 < mapped.length()) {
                mapped = mapped.substring(ownerEnd + 1);
            }
        }
        int descriptorStart = mapped.indexOf('(');
        return descriptorStart > 0 ? mapped.substring(0, descriptorStart) : mapped;
    }

    static String normalizedMethodSelectorWithDescriptor(String selector, List<String> targets,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            TargetApiIndex targetApi) {
        if (selector == null || selector.isBlank()) {
            return selector;
        }
        String mapped = remapSelector(selector, targets, mappings, aliases);
        if (mapped.startsWith("?")) {
            mapped = mapped.substring(1);
        }
        if (mapped.startsWith("L")) {
            int ownerEnd = mapped.indexOf(';');
            if (ownerEnd >= 0 && ownerEnd + 1 < mapped.length()) {
                mapped = mapped.substring(ownerEnd + 1);
            }
        }
        int descriptorStart = mapped.indexOf('(');
        if (descriptorStart <= 0) {
            return mapped;
        }
        String name = mapped.substring(0, descriptorStart);
        String descriptor = LegacyClassAliases.mapDescriptor(
                aliases.mapDescriptor(mappings.mapDescriptor(mapped.substring(descriptorStart))), targetApi);
        return name + descriptor;
    }

    private static String remapSelector(String selector, List<String> targets,
                                        IntermediaryMappings mappings,
                                        CompatibilityScanner.AliasSet aliases) {
        String optionalPrefix = selector.startsWith("?") ? "?" : "";
        if (!optionalPrefix.isEmpty()) {
            selector = selector.substring(1);
        }
        // Explicit-owner member selectors use Mixin's Lowner;name(desc) or Lowner;field:desc syntax.
        if (selector.startsWith("L")) {
            int ownerEnd = selector.indexOf(';');
            if (ownerEnd > 1 && ownerEnd + 1 < selector.length()) {
                String owner = selector.substring(1, ownerEnd);
                String memberSelector = selector.substring(ownerEnd + 1);
                String mapped = remapOwnedMember(owner, memberSelector, mappings, aliases);
                if (mapped != null) {
                    return optionalPrefix + "L" + mapped;
                }
            }
        }

        // Ownerless injection selectors are relative to @Mixin's target(s).
        int descriptorStart = selector.indexOf('(');
        if (descriptorStart <= 0) {
            if (selector.isBlank() || selector.contains("*") || selector.contains("/")
                    || selector.contains(";") || selector.contains(":") || selector.startsWith("<")) {
                return optionalPrefix + selector;
            }
            String mappedName = null;
            for (String owner : targets) {
                String candidate = mappings.mapMemberName(
                        CompatibilityScanner.SymbolKind.METHOD, owner, selector);
                if (mappedName != null && !mappedName.equals(candidate)) {
                    return optionalPrefix + selector;
                }
                mappedName = candidate;
            }
            return optionalPrefix + (mappedName == null ? selector : mappedName);
        }
        String name = selector.substring(0, descriptorStart);
        String descriptor = selector.substring(descriptorStart);
        if (name.contains("*") || name.contains("/") || name.contains(";")) {
            return optionalPrefix + selector;
        }

        String result = null;
        for (String owner : targets) {
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.METHOD,
                            owner, name, descriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            String candidate = mapped.name() + mapped.descriptor();
            if (result != null && !result.equals(candidate)) {
                return optionalPrefix + selector;
            }
            result = candidate;
        }
        return optionalPrefix + (result == null ? selector : result);
    }

    private static String remapOwnedMember(String owner, String selector,
                                           IntermediaryMappings mappings,
                                           CompatibilityScanner.AliasSet aliases) {
        int methodStart = selector.indexOf('(');
        int fieldSeparator = selector.indexOf(':');
        if (methodStart > 0 && (fieldSeparator < 0 || methodStart < fieldSeparator)) {
            String name = selector.substring(0, methodStart);
            String descriptor = selector.substring(methodStart);
            if (name.contains("*") || name.contains("/")) {
                return null;
            }
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.METHOD,
                            owner, name, descriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            return mapped.owner() + ";" + mapped.name() + mapped.descriptor();
        }
        if (fieldSeparator > 0) {
            String name = selector.substring(0, fieldSeparator);
            String descriptor = selector.substring(fieldSeparator + 1);
            if (name.contains("*") || name.contains("/")) {
                return null;
            }
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.FIELD,
                            owner, name, descriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            return mapped.owner() + ";" + mapped.name() + ":" + mapped.descriptor();
        }
        if (selector.isBlank() || selector.contains("*") || selector.contains("/")) {
            return null;
        }
        String mappedName = mappings.mapMemberName(
                CompatibilityScanner.SymbolKind.METHOD, owner, selector);
        return aliases.mapClass(mappings.mapClassName(owner)) + ";" + mappedName;
    }
}
