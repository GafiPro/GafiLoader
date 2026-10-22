package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassTweakerPatcherTest {
    @Test
    void replacesFilesReaderWithTheInMemoryClassTweakerAdapter() {
        byte[] transformed = ClassTweakerPatcher.transform(
                ClassTweakerPatcher.TARGET_CLASS, fabricLoaderClass(true));
        boolean[] foundAdapter = {false};
        boolean[] foundFilesCall = {false};

        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("loadClassTweakers")) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals("dev/gafipro/gafiloader/MixinResourceRemapper")
                                && methodName.equals("openClassTweakerReader")
                                && methodDescriptor.equals(
                                        "(Ljava/nio/file/Path;)Ljava/io/BufferedReader;")) {
                            foundAdapter[0] = true;
                        }
                        if (owner.equals("java/nio/file/Files")
                                && methodName.equals("newBufferedReader")) {
                            foundFilesCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundAdapter[0], "Expected Fabric's reader call to be intercepted");
        assertTrue(!foundFilesCall[0], "The original Files.newBufferedReader call should be absent");
    }

    @Test
    void rejectsUnexpectedFabricLoaderBytecode() {
        assertThrows(IllegalStateException.class, () -> ClassTweakerPatcher.transform(
                ClassTweakerPatcher.TARGET_CLASS, fabricLoaderClass(false)));
    }

    @Test
    void patchesThePinnedReleasedFabricLoaderClassWhenAvailable() throws Exception {
        String configuredJar = System.getProperty("gafi.loader.fabric.loader.jar");
        Assumptions.assumeTrue(configuredJar != null && Files.isRegularFile(Path.of(configuredJar)),
                "CI optionally supplies the pinned released Fabric Loader JAR");

        try (JarFile jar = new JarFile(configuredJar)) {
            JarEntry entry = jar.getJarEntry(ClassTweakerPatcher.TARGET_CLASS + ".class");
            Assumptions.assumeTrue(entry != null, "Pinned Fabric Loader JAR must contain FabricLoaderImpl");
            byte[] original;
            try (InputStream input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
            byte[] transformed = ClassTweakerPatcher.transform(ClassTweakerPatcher.TARGET_CLASS, original);
            assertTrue(transformed.length > 0);
        }
    }

    private static byte[] fabricLoaderClass(boolean includeReaderCall) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, ClassTweakerPatcher.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "loadClassTweakers", "()V", null, null);
        method.visitCode();
        if (includeReaderCall) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/nio/file/Files", "newBufferedReader",
                    "(Ljava/nio/file/Path;)Ljava/io/BufferedReader;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
