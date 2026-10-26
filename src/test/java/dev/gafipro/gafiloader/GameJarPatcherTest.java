package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameJarPatcherTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void addsADelegatingAliasToCopiedGameJarAndLeavesSourceBytesUntouched() throws Exception {
        Path original = temporaryDirectory.resolve("minecraft-26.2.jar");
        Path patched = temporaryDirectory.resolve("minecraft-26.2-gafi.jar");
        Path rules = temporaryDirectory.resolve("rules.tsv");
        byte[] originalClass = targetClass();

        writeJar(original, "sample/Target.class", originalClass);
        Files.writeString(rules, "METHOD_ALIAS\tsample/Target\tcurrentValue\t()I\tlegacyValue\tSTATIC\n");

        GameJarPatcher.PatchReport report = new GameJarPatcher().patch(original, patched, rules);

        assertEquals(1, report.classesPatched());
        assertEquals(1, report.methodAliasesAdded());
        assertTrue(Files.exists(patched));
        try (JarFile source = new JarFile(original.toFile());
             JarFile output = new JarFile(patched.toFile())) {
            assertTrue(java.util.Arrays.equals(originalClass,
                    source.getInputStream(source.getJarEntry("sample/Target.class")).readAllBytes()));
            byte[] patchedBytes = output.getInputStream(output.getJarEntry("sample/Target.class")).readAllBytes();
            assertTrue(hasMethod(patchedBytes, "legacyValue", "()I"));
        }

        Class<?> type = new JarClassLoader(patched).loadClass("sample.Target");
        Method method = type.getMethod("legacyValue");
        assertEquals(42, method.invoke(null));
    }

    @Test
    void runtimeTransformerAddsTheSameAliasBeforeTheClassIsDefined() throws Exception {
        Path rules = temporaryDirectory.resolve("runtime-rules.tsv");
        Files.writeString(rules, "METHOD_ALIAS\tsample/Target\tcurrentValue\t()I\tlegacyValue\tSTATIC\n");

        GameJarPatcher.RuntimeRules runtimeRules = GameJarPatcher.loadRuntimeRules(rules);
        byte[] transformed = GameJarPatcher.transformClassForRuntime(
                "sample/Target", targetClass(), runtimeRules);

        assertTrue(transformed != null);
        Class<?> type = new ByteArrayClassLoader().define(transformed);
        assertEquals(42, type.getMethod("legacyValue").invoke(null));
        assertEquals(1, runtimeRules.ruleCount());
        assertEquals(1, runtimeRules.appliedRules().size());
        assertFalse(GameJarPatcher.transformClassForRuntime(
                "sample/Unrelated", targetClass(), runtimeRules) != null);
    }

    @Test
    void delegatesInstanceAliasToTheCurrentInstanceMethod() throws Exception {
        Path rules = temporaryDirectory.resolve("instance-rules.tsv");
        Files.writeString(rules, "METHOD_ALIAS\tsample/InstanceTarget\tcurrentValue\t()I\tlegacyValue\tINSTANCE\n");

        GameJarPatcher.RuntimeRules runtimeRules = GameJarPatcher.loadRuntimeRules(rules);
        byte[] transformed = GameJarPatcher.transformClassForRuntime(
                "sample/InstanceTarget", instanceTargetClass(), runtimeRules);

        assertTrue(transformed != null);
        Class<?> type = new ByteArrayClassLoader().define("sample.InstanceTarget", transformed);
        Object instance = type.getConstructor().newInstance();
        assertEquals(42, type.getMethod("legacyValue").invoke(instance));
        assertEquals(1, runtimeRules.appliedRules().size());
    }

    @Test
    void rejectsStaticInstanceMismatchBeforeWritingOutput() throws IOException {
        Path original = temporaryDirectory.resolve("minecraft-static-mismatch.jar");
        Path patched = temporaryDirectory.resolve("mismatch-output.jar");
        Path rules = temporaryDirectory.resolve("mismatch-rules.tsv");
        writeJar(original, "sample/Target.class", targetClass());
        Files.writeString(rules, "METHOD_ALIAS\tsample/Target\tcurrentValue\t()I\tlegacyValue\tINSTANCE\n");

        assertThrows(IOException.class, () -> new GameJarPatcher().patch(original, patched, rules));
        assertFalse(Files.exists(patched));
        assertTrue(Files.exists(original));
    }

    @Test
    void refusesToOverwriteTheOriginalGameJar() throws IOException {
        Path original = temporaryDirectory.resolve("minecraft-26.2.jar");
        Path rules = temporaryDirectory.resolve("rules.tsv");
        writeJar(original, "sample/Target.class", targetClass());
        Files.writeString(rules, "METHOD_ALIAS\tsample/Target\tcurrentValue\t()I\tlegacyValue\tSTATIC\n");

        assertThrows(IllegalArgumentException.class,
                () -> new GameJarPatcher().patch(original, original, rules));
    }

    @Test
    void failsClosedWhenTargetMethodDoesNotExist() throws IOException {
        Path original = temporaryDirectory.resolve("minecraft-26.2.jar");
        Path patched = temporaryDirectory.resolve("should-not-exist.jar");
        Path rules = temporaryDirectory.resolve("rules.tsv");
        writeJar(original, "sample/Target.class", targetClass());
        Files.writeString(rules, "METHOD_ALIAS\tsample/Target\tmissing\t()I\tlegacyValue\tSTATIC\n");

        assertThrows(IOException.class, () -> new GameJarPatcher().patch(original, patched, rules));
        assertFalse(Files.exists(patched));
        assertTrue(Files.exists(original));
    }

    private static boolean hasMethod(byte[] bytes, String name, String descriptor) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                if (methodName.equals(name) && methodDescriptor.equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static byte[] instanceTargetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/InstanceTarget",
                null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "currentValue",
                "()I", null, null);
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, 42);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Target", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "currentValue", "()I", null, null);
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, 42);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, String entryName, byte[] bytes) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(bytes);
            jar.closeEntry();
        }
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private ByteArrayClassLoader() {
            super(GameJarPatcherTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            return define("sample.Target", bytes);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final class JarClassLoader extends ClassLoader {
        private final Path jarPath;

        private JarClassLoader(Path jarPath) {
            super(GameJarPatcherTest.class.getClassLoader());
            this.jarPath = jarPath;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String entryName = name.replace('.', '/') + ".class";
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }
}
