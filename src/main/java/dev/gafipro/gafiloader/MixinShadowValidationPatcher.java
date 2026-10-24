package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Keeps legacy @Shadow members classified as shadows even when their 1.21.11
 * declaration no longer exists under the same name in the 26.2 target.
 *
 * This validation bypass is only one part of compatibility: names/descriptors
 * still need to be resolved by the in-memory mapping and game-side bridge passes.
 * The source mod JAR is never modified.
 */
public final class MixinShadowValidationPatcher {
    static final String TARGET_CLASS =
            "org/spongepowered/asm/mixin/transformer/MixinPreProcessorStandard";
    private static final String CONTEXT =
            "Lorg/spongepowered/asm/mixin/transformer/MixinTargetContext;";
    private static final String SHADOW_ANNOTATION = "Lorg/spongepowered/asm/mixin/Shadow;";

    private MixinShadowValidationPatcher() {}

    private static boolean hasExpectedArguments(String descriptor, String secondArgumentSuffix) {
        Type methodType = Type.getMethodType(descriptor);
        Type[] arguments = methodType.getArgumentTypes();
        return methodType.getReturnType().equals(Type.BOOLEAN_TYPE)
                && arguments.length == 2
                && arguments[0].getDescriptor().equals(CONTEXT)
                && arguments[1].getSort() == Type.OBJECT
                && arguments[1].getInternalName().endsWith(secondArgumentSuffix);
    }

    /**
     * Called from patched Mixin bytecode. ASM FieldNode exposes visibleAnnotations
     * as a public field; reflection avoids linking this agent helper to Mixin's
     * private implementation classes/classloader.
     */
    public static boolean isShadowField(Object mixinField) {
        if (mixinField == null) return false;
        try {
            Field annotationsField = mixinField.getClass().getField("visibleAnnotations");
            Object annotations = annotationsField.get(mixinField);
            if (!(annotations instanceof List<?> list)) return false;
            for (Object annotation : list) {
                if (annotation == null) continue;
                Field descriptorField = annotation.getClass().getField("desc");
                if (SHADOW_ANNOTATION.equals(descriptorField.get(annotation))) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fail closed: never classify an unverified field as a shadow.
        }
        return false;
    }


    /** Locate the live mixin node reflectively; Mixin versions differ in field descriptors. */
    public static void normalizeRespawnSelectorFromPreprocessor(Object preprocessor) {
        normalizeRespawnSelectorFromPreprocessor(preprocessor, null);
    }

    public static void normalizeRespawnSelectorFromPreprocessor(Object preprocessor, Object targetContext) {
        if (preprocessor == null) return;
        try {
            try {
                normalizeRespawnSelector(readField(preprocessor, "classNode"), targetContext);
                return;
            } catch (NoSuchFieldException ignored) {
                // Continue with type-based discovery below.
            }
            Class<?> type = preprocessor.getClass();
            while (type != null) {
                for (Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    if (!fieldType.endsWith("MixinClassNode") && !fieldType.endsWith("ClassNode")) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object candidate = field.get(preprocessor);
                    if (candidate != null) {
                        normalizeRespawnSelector(candidate, targetContext);
                        return;
                    }
                }
                type = type.getSuperclass();
            }
            System.err.println("[Gafi Loader] Could not find live MixinClassNode field on "
                    + preprocessor.getClass().getName() + "; fields="
                    + java.util.Arrays.stream(preprocessor.getClass().getDeclaredFields())
                    .map(field -> field.getName() + ":" + field.getType().getName()).toList());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.err.println("[Gafi Loader] Could not locate live MixinClassNode: "
                    + exception.getMessage());
        }
    }

    /**
     * Mixin may parse the mixin class through a different bytecode path than its metadata
     * inspection hooks. Normalize the known respawn selector on the live MixinClassNode just
     * before Mixin prepares and attaches the methods.
     */
    public static void normalizeRespawnSelector(Object mixinClassNode) {
        normalizeRespawnSelector(mixinClassNode, null);
    }

    public static void normalizeRespawnSelector(Object mixinClassNode, Object targetContext) {
        if (mixinClassNode == null) return;
        System.out.println("[Gafi Loader] DEBUG live mixin node received: "
                + mixinClassNode.getClass().getName());
        try {
            Object methodsValue;
            try {
                methodsValue = readField(mixinClassNode, "mixinMethods");
            } catch (NoSuchFieldException ignored) {
                methodsValue = readField(mixinClassNode, "methods");
            }
            if (!(methodsValue instanceof List<?> methods)) {
                System.out.println("[Gafi Loader] DEBUG live mixin methods field is not a list: "
                        + (methodsValue == null ? "null" : methodsValue.getClass().getName()));
                return;
            }
            System.out.println("[Gafi Loader] DEBUG live mixin method count=" + methods.size());
            Object targetClassNode = null;
            if (targetContext != null) {
                try {
                    targetClassNode = targetContext.getClass().getMethod("getTargetClassNode")
                            .invoke(targetContext);
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    System.out.println("[Gafi Loader] Could not inspect live Mixin target class: "
                            + exception.getMessage());
                }
            }
            String targetClassName = targetClassNode == null ? ""
                    : String.valueOf(readField(targetClassNode, "name"));
            boolean packetListenerTarget = targetClassName.equals(
                    "net/minecraft/client/multiplayer/ClientPacketListener")
                    || targetClassName.equals("net/minecraft/class_634");
            for (Object method : methods) {
                String name = String.valueOf(readField(method, "name"));
                String descriptor = String.valueOf(readField(method, "desc"));
                if (!packetListenerTarget) {
                    // Keep the narrow fallback if Mixin does not expose its target node.
                    if (!(name.equals("onPlayerRespawn") || name.endsWith("$onPlayerRespawn")
                            || name.contains("$onPlayerRespawn$"))
                            || !descriptor.contains("ClientboundRespawnPacket")) {
                        continue;
                    }
                } else {
                    String packetArgument = firstObjectArgument(descriptor);
                    if (!descriptor.contains("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                            || packetArgument == null
                            || !(packetArgument.contains("Packet")
                                || packetArgument.matches("Lnet/minecraft/class_\\d+;"))) {
                        continue;
                    }
                }
                if (name.toLowerCase(java.util.Locale.ROOT).contains("respawn")
                        || descriptor.contains("ClientboundRespawnPacket")
                        || descriptor.contains("ClientboundLoginPacket")
                        || descriptor.contains("class_2724")) {
                    System.out.println("[Gafi Loader] DEBUG live packet mixin method="
                            + name + descriptor + " target=" + targetClassName);
                }
                String targetSelector = resolveTargetSelectorFromContext(targetClassNode, descriptor);
                if (targetSelector == null && name.toLowerCase(java.util.Locale.ROOT).contains("respawn")) {
                    targetSelector = MixinResourceRemapper.resolveRespawnTargetSelector();
                }
                System.out.println("[Gafi Loader] DEBUG live packet target selector=" + targetSelector
                        + " fromTargetNode=" + (targetClassNode != null));
                java.util.List<Object> annotations = new java.util.ArrayList<>();
                Object visibleValue = readField(method, "visibleAnnotations");
                if (visibleValue instanceof List<?> visible) annotations.addAll(visible);
                Object invisibleValue = readField(method, "invisibleAnnotations");
                if (invisibleValue instanceof List<?> invisible) annotations.addAll(invisible);
                for (Object annotation : annotations) {
                    if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(
                            String.valueOf(readField(annotation, "desc")))) {
                        continue;
                    }
                    Object valuesValue = readField(annotation, "values");
                    if (!(valuesValue instanceof List<?> rawValues)) continue;
                    @SuppressWarnings("unchecked")
                    List<Object> values = (List<Object>) rawValues;
                    boolean requirePresent = false;
                    for (int i = 0; i + 1 < values.size(); i += 2) {
                        if ("method".equals(values.get(i))) {
                            Object original = values.get(i + 1);
                            if (original instanceof List<?> selectors) {
                                values.set(i + 1, new java.util.ArrayList<>(List.of(targetSelector)));
                                System.out.println("[Gafi Loader] Normalized live packet selector on "
                                        + name + descriptor + ": " + selectors + " -> " + targetSelector);
                            } else if (original instanceof String selector) {
                                values.set(i + 1, new java.util.ArrayList<>(List.of(targetSelector)));
                                System.out.println("[Gafi Loader] Normalized live respawn selector on "
                                        + name + descriptor + ": " + selector + " -> " + targetSelector);
                            }
                        } else if ("require".equals(values.get(i))) {
                            values.set(i + 1, 0);
                            requirePresent = true;
                        }
                    }
                    // This legacy Fabric lifecycle hook is optional across target API shapes.
                    // Keep an unresolved injection from aborting the entire client; it still
                    // injects normally whenever the selector matches the live target.
                    if (!requirePresent) {
                        values.add("require");
                        values.add(0);
                    }
                    System.out.println("[Gafi Loader] Set optional packet injection require=0 for compatibility.");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.err.println("[Gafi Loader] Could not normalize live respawn selector: "
                    + exception.getMessage());
        }
    }

    private static String resolveTargetSelectorFromContext(Object targetClassNode,
            String callbackDescriptor) {
        if (targetClassNode == null || callbackDescriptor == null) return null;
        try {
            String callbackPacket = firstObjectArgument(callbackDescriptor);
            if (callbackPacket == null) return null;
            Object methodsValue = readField(targetClassNode, "methods");
            if (!(methodsValue instanceof List<?> methods)) return null;
            java.util.Set<String> candidates = new java.util.TreeSet<>();
            for (Object method : methods) {
                String descriptor = String.valueOf(readField(method, "desc"));
                if (!descriptor.endsWith(")V")) continue;
                String firstArgument = firstObjectArgument(descriptor);
                if (callbackPacket.equals(firstArgument)) {
                    candidates.add(String.valueOf(readField(method, "name")) + descriptor);
                }
            }
            if (candidates.size() == 1) return candidates.iterator().next();
            if (!candidates.isEmpty()) {
                System.out.println("[Gafi Loader] Ambiguous live respawn target candidates: " + candidates);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            System.out.println("[Gafi Loader] Could not resolve respawn target from live target node: "
                    + exception.getMessage());
        }
        return null;
    }

    private static String firstObjectArgument(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(")) return null;
        int start = 1;
        if (descriptor.length() <= start || descriptor.charAt(start) != 'L') return null;
        int end = descriptor.indexOf(';', start);
        return end < 0 ? null : descriptor.substring(start, end + 1);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Read @Shadow metadata reflectively to avoid linking against Mixin's shaded ASM return type. */
    public static boolean isShadowMethod(Object mixinMethod) {
        if (mixinMethod == null) return false;
        try {
            Field annotationsField = mixinMethod.getClass().getField("visibleAnnotations");
            Object annotations = annotationsField.get(mixinMethod);
            if (!(annotations instanceof List<?> list)) return false;
            for (Object annotation : list) {
                if (annotation == null) continue;
                Field descriptorField = annotation.getClass().getField("desc");
                if (SHADOW_ANNOTATION.equals(descriptorField.get(annotation))) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fail closed: never classify an unverified method as a shadow.
        }
        return false;
    }

    static byte[] transform(String className, byte[] original) {
        if (!TARGET_CLASS.equals(className) || original == null) return null;

        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(node, 0);
        int patchedMethods = 0;
        int patchedFields = 0;
        int patchedAttach = 0;

        for (MethodNode method : node.methods) {
            boolean attach = "attach".equals(method.name)
                    && method.desc.contains("MixinTargetContext;")
                    && method.desc.endsWith("MixinPreProcessorStandard;");
            if (attach) {
                InsnList entry = new InsnList();
                entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
                entry.add(new VarInsnNode(Opcodes.ALOAD, 1));
                entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gafipro/gafiloader/MixinShadowValidationPatcher",
                        "normalizeRespawnSelectorFromPreprocessor",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                method.instructions.insert(entry);
                patchedAttach++;
            }
            boolean shadowMethod = "attachShadowMethod".equals(method.name)
                    && hasExpectedArguments(method.desc, "MixinMethodNode");
            boolean shadowField = "attachShadowField".equals(method.name)
                    && hasExpectedArguments(method.desc, "MixinFieldNode");
            if (!shadowMethod && !shadowField) continue;

            method.instructions.clear();
            if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            InsnList code = method.instructions;
            if (shadowMethod) {
                // Reflect over visibleAnnotations so the method does not link to either
                // Fabric's ASM classes or the agent's relocated/shaded ASM classes.
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gafipro/gafiloader/MixinShadowValidationPatcher",
                        "isShadowMethod", "(Ljava/lang/Object;)Z", false));
                code.add(new InsnNode(Opcodes.IRETURN));
                patchedMethods++;
            } else {
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "dev/gafipro/gafiloader/MixinShadowValidationPatcher",
                        "isShadowField", "(Ljava/lang/Object;)Z", false));
                code.add(new InsnNode(Opcodes.IRETURN));
                patchedFields++;
            }
            method.maxStack = 2;
            method.maxLocals = 3;
        }

        if (patchedMethods == 0 && patchedFields == 0 && patchedAttach == 0) {
            String available = node.methods.stream()
                    .filter(method -> method.name.toLowerCase(java.util.Locale.ROOT).contains("shadow"))
                    .map(method -> method.name + method.desc)
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalStateException("Could not patch Mixin @Shadow validation in "
                    + TARGET_CLASS + ". Available methods: " + available);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        System.out.println("[Gafi Loader] Enabled legacy @Shadow validation compatibility (methods="
                + patchedMethods + ", fields=" + patchedFields + ", attach=" + patchedAttach + ").");
        return writer.toByteArray();
    }
}
