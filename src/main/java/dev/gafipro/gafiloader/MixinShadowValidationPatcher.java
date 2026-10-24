package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * Relaxes Mixin's hard failure for legacy @Shadow methods that disappeared or changed
 * in the target game. The method is still classified as a shadow and removed from the
 * merged method list; the mod JAR remains untouched. Name/descriptor compatibility is
 * handled separately by the in-memory remappers and game-side bridges.
 */
final class MixinShadowValidationPatcher {
    static final String TARGET_CLASS =
            "org/spongepowered/asm/mixin/transformer/MixinPreProcessorStandard";
    private static final String METHOD_DESCRIPTOR =
            "(Lorg/spongepowered/asm/mixin/transformer/MixinTargetContext;"
                    + "Lorg/spongepowered/asm/mixin/transformer/MixinMethodNode;)Z";

    private MixinShadowValidationPatcher() {}

 
    private static boolean isShadowMethodDescriptor(String descriptor) {
        Type methodType = Type.getMethodType(descriptor);
        Type[] arguments = methodType.getArgumentTypes();
        return methodType.getReturnType().equals(Type.BOOLEAN_TYPE)
                && arguments.length == 2
                && arguments[0].getSort() == Type.OBJECT
                && arguments[0].getInternalName().endsWith("/MixinTargetContext")
                && arguments[1].getSort() == Type.OBJECT
                && arguments[1].getInternalName().endsWith("MixinMethodNode");
    }

    static byte[] transform(String className, byte[] original) {
        if (!TARGET_CLASS.equals(className) || original == null) {
            return null;
        }
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(original).accept(node, 0);
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!"attachShadowMethod".equals(method.name)
                    || !isShadowMethodDescriptor(method.desc)) {
                continue;
            }
            method.instructions.clear();
            if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            InsnList code = method.instructions;
            LabelNode notShadow = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new LdcInsnNode(Type.getObjectType("org/spongepowered/asm/mixin/Shadow")));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "org/spongepowered/asm/mixin/transformer/MixinInfo$MixinMethodNode",
                    "getVisibleAnnotation",
                    "(Ljava/lang/Class;)Lorg/objectweb/asm/tree/AnnotationNode;", false));
            code.add(new JumpInsnNode(Opcodes.IFNULL, notShadow));
            // Treat any @Shadow method as a shadow member without requiring the old
            // name/descriptor to exist on the newer game target.
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IRETURN));
            code.add(notShadow);
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 2;
            method.maxLocals = 3;
            patched = true;
        }
        if (!patched) {
            String available = node.methods.stream()
                    .filter(method -> method.name.contains("Shadow"))
                    .map(method -> method.name + method.desc)
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new IllegalStateException("Could not patch Mixin @Shadow validation: no compatible "
                    + "attachShadowMethod was found in " + TARGET_CLASS
                    + ". Available shadow methods: " + available
                    + ". Expected a boolean method taking MixinTargetContext and MixinMethodNode.");
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        System.out.println("[Gafi Loader] Enabled legacy @Shadow validation compatibility.");
        return writer.toByteArray();
    }
}
