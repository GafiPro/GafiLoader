package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.concurrent.atomic.AtomicBoolean;

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

    static byte[] transform(String className, byte[] original) {
        if (!TARGET_CLASS.equals(className) || original == null) {
            return null;
        }
        AtomicBoolean patched = new AtomicBoolean();
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!"attachShadowMethod".equals(name) || !METHOD_DESCRIPTOR.equals(descriptor)) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                patched.set(true);
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                method.visitCode();
                Label notShadow = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 2);
                method.visitLdcInsn(Type.getObjectType("org/spongepowered/asm/mixin/Shadow"));
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "org/spongepowered/asm/mixin/transformer/MixinMethodNode",
                        "getVisibleAnnotation",
                        "(Ljava/lang/Class;)Lorg/objectweb/asm/tree/AnnotationNode;", false);
                method.visitJumpInsn(Opcodes.IFNULL, notShadow);
                // Treat any @Shadow method as a shadow member without requiring the old
                // name/descriptor to exist on the newer game target.
                method.visitInsn(Opcodes.ICONST_1);
                method.visitInsn(Opcodes.IRETURN);
                method.visitLabel(notShadow);
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(2, 3);
                method.visitEnd();
                return null;
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!patched.get()) {
            throw new IllegalStateException("Could not patch Mixin @Shadow validation: "
                    + TARGET_CLASS + ".attachShadowMethod" + METHOD_DESCRIPTOR
                    + " was not found; refusing to claim legacy @Shadow compatibility.");
        }
        System.out.println("[Gafi Loader] Enabled legacy @Shadow validation compatibility.");
        return writer.toByteArray();
    }
}
