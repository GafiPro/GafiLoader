package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnotClassLoaderPatcherTest {
    @Test
    void injectsResourceAdapterCallIntoKnotClassLoader() {
        byte[] transformed = KnotClassLoaderPatcher.transform(
                KnotClassLoaderPatcher.TARGET_CLASS, classWithResourceMethod());
        AtomicBoolean foundAdapter = new AtomicBoolean();
        AtomicBoolean foundUrlAdapter = new AtomicBoolean();
        new ClassReader(transformed).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
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
                        if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals("dev/gafipro/gafiloader/MixinResourceRemapper")
                                && methodName.equals("transformUrl")
                                && methodDescriptor.equals(
                                        "(Ljava/lang/String;Ljava/net/URL;)Ljava/net/URL;")) {
                            foundUrlAdapter.set(true);
                        }
                    }
                };
            }
        }, 0);
        assertTrue(foundAdapter.get(), "Expected KnotClassLoader stream hook to invoke the in-memory adapter");
        assertTrue(foundUrlAdapter.get(), "Expected KnotClassLoader URL hook to invoke the in-memory adapter");
    }

    @Test
    void rejectsKnotClassLoaderWithoutExpectedResourceMethod() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, KnotClassLoaderPatcher.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC, "getResourceAsStream", "()Ljava/io/InputStream;",
                null, null).visitEnd();
        writer.visitEnd();
        assertThrows(IllegalStateException.class, () -> KnotClassLoaderPatcher.transform(
                KnotClassLoaderPatcher.TARGET_CLASS, writer.toByteArray()));
    }

    private static byte[] classWithResourceMethod() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, KnotClassLoaderPatcher.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor urlMethod = writer.visitMethod(Opcodes.ACC_PUBLIC, "getResource",
                "(Ljava/lang/String;)Ljava/net/URL;", null, null);
        urlMethod.visitCode();
        urlMethod.visitInsn(Opcodes.ACONST_NULL);
        urlMethod.visitInsn(Opcodes.ARETURN);
        urlMethod.visitMaxs(0, 0);
        urlMethod.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "getResourceAsStream",
                "(Ljava/lang/String;)Ljava/io/InputStream;", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
