package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinServiceKnotPatcherTest {
    @Test
    void injectsResourceAdapterCallIntoFabricMixinService() {
        byte[] original = resourceServiceClass();
        byte[] transformed = MixinServiceKnotPatcher.transform(
                MixinServiceKnotPatcher.TARGET_CLASS, original);
        AtomicBoolean foundAdapter = new AtomicBoolean();
        AtomicBoolean foundClassAdapter = new AtomicBoolean();
        AtomicBoolean foundClassNodeAdapter = new AtomicBoolean();

        new ClassReader(transformed).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                boolean classBytesMethod = name.equals("getClassBytes");
                boolean classNodeMethod = name.equals("getClassNode");
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals("dev/gafipro/gafiloader/MixinResourceRemapper")
                                && methodName.equals("transform")
                                && methodDescriptor.equals(
                                        "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/io/InputStream;")) {
                            foundAdapter.set(true);
                        }
                        if (classBytesMethod && opcode == Opcodes.INVOKESTATIC
                                && owner.equals("dev/gafipro/gafiloader/MixinResourceRemapper")
                                && methodName.equals("transformClassBytes")
                                && methodDescriptor.equals("(Ljava/lang/String;[B)[B")) {
                            foundClassAdapter.set(true);
                        }
                        if (classNodeMethod && opcode == Opcodes.INVOKESTATIC
                                && owner.equals("dev/gafipro/gafiloader/MixinResourceRemapper")
                                && methodName.equals("transformClassNode")
                                && methodDescriptor.equals("(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;")) {
                            foundClassNodeAdapter.set(true);
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundAdapter.get(), "Expected the in-memory resource transformer call");
        assertTrue(foundClassAdapter.get(), "Expected the pre-Mixin class-byte transformer call");
        assertTrue(foundClassNodeAdapter.get(), "Expected the pre-Mixin ClassNode transformer call");
    }

    @Test
    void keepsEssentialClassByteHookWhenOptionalMethodsAreAbsent() {
        byte[] original = classWithClassBytesOnly();
        byte[] transformed = MixinServiceKnotPatcher.transform(
                MixinServiceKnotPatcher.TARGET_CLASS, original);
        assertTrue(transformed.length > original.length,
                "Expected the class-byte compatibility hook even without optional resource/ClassNode methods");
    }

    @Test
    void rejectsUnexpectedFabricMixinServiceSignature() {
        byte[] noExpectedMethod = classWithWrongMethod();
        assertThrows(IllegalStateException.class, () -> MixinServiceKnotPatcher.transform(
                MixinServiceKnotPatcher.TARGET_CLASS, noExpectedMethod));
    }

    private static byte[] resourceServiceClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, MixinServiceKnotPatcher.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "getResourceAsStream",
                "(Ljava/lang/String;)Ljava/io/InputStream;", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        MethodVisitor classBytes = writer.visitMethod(Opcodes.ACC_PUBLIC, "getClassBytes",
                "(Ljava/lang/String;Ljava/lang/String;)[B", null, null);
        classBytes.visitCode();
        classBytes.visitInsn(Opcodes.ACONST_NULL);
        classBytes.visitInsn(Opcodes.ARETURN);
        classBytes.visitMaxs(0, 0);
        classBytes.visitEnd();

        MethodVisitor classBytesFlag = writer.visitMethod(Opcodes.ACC_PUBLIC, "getClassBytes",
                "(Ljava/lang/String;Z)[B", null, null);
        classBytesFlag.visitCode();
        classBytesFlag.visitInsn(Opcodes.ACONST_NULL);
        classBytesFlag.visitInsn(Opcodes.ARETURN);
        classBytesFlag.visitMaxs(0, 0);
        classBytesFlag.visitEnd();

        MethodVisitor classNode = writer.visitMethod(Opcodes.ACC_PUBLIC, "getClassNode",
                "(Ljava/lang/String;ZI)Lorg/objectweb/asm/tree/ClassNode;", null, null);
        classNode.visitCode();
        classNode.visitInsn(Opcodes.ACONST_NULL);
        classNode.visitInsn(Opcodes.ARETURN);
        classNode.visitMaxs(0, 0);
        classNode.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithClassBytesOnly() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, MixinServiceKnotPatcher.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor classBytes = writer.visitMethod(Opcodes.ACC_PUBLIC, "getClassBytes",
                "(Ljava/lang/String;Z)[B", null, null);
        classBytes.visitCode();
        classBytes.visitInsn(Opcodes.ACONST_NULL);
        classBytes.visitInsn(Opcodes.ARETURN);
        classBytes.visitMaxs(0, 0);
        classBytes.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithWrongMethod() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, MixinServiceKnotPatcher.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC, "getResourceAsStream", "()Ljava/io/InputStream;",
                null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
