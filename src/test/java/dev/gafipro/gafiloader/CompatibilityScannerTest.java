package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void findsMissingMinecraftClassesAndMethodsWithoutFlaggingExistingMethods() throws IOException {
        Path gameJar = temporaryDirectory.resolve("minecraft-26.2.jar");
        Path modJar = temporaryDirectory.resolve("legacy-test-mod.jar");

        writeJar(gameJar, "net/minecraft/Target", targetClass());
        writeJar(modJar, "test/LegacyMod", legacyModClass());

        CompatibilityScanner.Report report = new CompatibilityScanner().scan(gameJar, List.of(modJar));

        Set<String> findingKeys = report.findings().stream()
                .map(finding -> finding.kind() + ":" + finding.owner()
                        + (finding.name() == null ? "" : "#" + finding.name()))
                .collect(Collectors.toSet());

        assertEquals(1, report.summary().missingClasses());
        assertEquals(1, report.summary().missingMethods());
        assertEquals(0, report.summary().missingFields());
        assertTrue(findingKeys.contains("MISSING_CLASS:net/minecraft/Vanished"));
        assertTrue(findingKeys.contains("MISSING_METHOD:net/minecraft/Target#removed"));
        assertTrue(findingKeys.stream().noneMatch(key -> key.endsWith("#present")));
    }

    private static byte[] targetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/Target",
                null, "java/lang/Object", null);

        MethodVisitor present = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "present", "()V", null, null);
        present.visitCode();
        present.visitInsn(Opcodes.RETURN);
        present.visitMaxs(0, 0);
        present.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyModClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyMod",
                null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/Target", "present", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/Target", "removed", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/Vanished", "call", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, String classEntry, byte[] classBytes) throws IOException {
        try (OutputStream output = java.nio.file.Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output)) {
            jar.putNextEntry(new JarEntry(classEntry + ".class"));
            jar.write(classBytes);
            jar.closeEntry();
        }
    }
}
