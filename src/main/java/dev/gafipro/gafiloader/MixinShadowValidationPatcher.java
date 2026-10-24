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
final class MixinShadowValidationPatcher {
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

    static byte[] transform(String className, byte[] original) {
        if (!TARGET_CLASS.equals(className) || original == null) return null;

        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(node, 0);
        int patchedMethods = 0;
        int patchedFields = 0;

        for (MethodNode method : node.methods) {
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
                // MixinMethodNode provides getVisibleAnnotation(Class).
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new org.objectweb.asm.tree.LdcInsnNode(
                        Type.getObjectType("org/spongepowered/asm/mixin/Shadow")));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "org/spongepowered/asm/mixin/transformer/MixinInfo$MixinMethodNode",
                        "getVisibleAnnotation",
                        "(Ljava/lang/Class;)Lorg/objectweb/asm/tree/AnnotationNode;", false));
                org.objectweb.asm.tree.LabelNode notShadow =
                        new org.objectweb.asm.tree.LabelNode();
                code.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, notShadow));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.IRETURN));
                code.add(notShadow);
                code.add(new InsnNode(Opcodes.ICONST_0));
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

        if (patchedMethods == 0 && patchedFields == 0) {
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
                + patchedMethods + ", fields=" + patchedFields + ").");
        return writer.toByteArray();
    }
}
