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

    @Test
    void appliesExplicitClassAndMethodAliasesBeforeComparing() throws IOException {
        Path gameJar = temporaryDirectory.resolve("minecraft-alias-test.jar");
        Path modJar = temporaryDirectory.resolve("legacy-alias-test-mod.jar");
        Path aliasesFile = temporaryDirectory.resolve("aliases.tsv");

        writeJar(gameJar, "net/minecraft/Target", targetClass());
        writeJar(modJar, "test/AliasedLegacyMod", aliasedLegacyModClass());
        java.nio.file.Files.writeString(aliasesFile, String.join("\n",
                "CLASS\tnet/minecraft/LegacyTarget\tnet/minecraft/Target",
                "METHOD\tnet/minecraft/LegacyTarget\toldPresent\t()V"
                        + "\tnet/minecraft/Target\tpresent\t()V",
                ""));

        CompatibilityScanner.Report report = new CompatibilityScanner()
                .scan(gameJar, List.of(modJar), aliasesFile);

        assertTrue(report.findings().isEmpty());
        assertEquals(1, report.summary().classAliasesLoaded());
        assertEquals(1, report.summary().memberAliasesLoaded());
    }

    @Test
    void detectsMissingFabricApiMethodsFromNestedTargetApiModules() throws IOException {
        Path gameJar = temporaryDirectory.resolve("minecraft-api-target.jar");
        Path modJar = temporaryDirectory.resolve("legacy-fabric-api-mod.jar");
        Path targetApiJar = temporaryDirectory.resolve("fabric-api-26.2.jar");

        writeJar(gameJar, "net/minecraft/Target", targetClass());
        writeJar(modJar, "test/LegacyFabricApiMod", fabricApiLegacyModClass());

        byte[] moduleJar = jarBytes("net/fabricmc/fabric/api/event/Event.class", fabricApiTargetClass());
        try (JarOutputStream outer = new JarOutputStream(java.nio.file.Files.newOutputStream(targetApiJar))) {
            outer.putNextEntry(new JarEntry("META-INF/jars/fabric-api-event.jar"));
            outer.write(moduleJar);
            outer.closeEntry();
        }

        CompatibilityScanner scanner = new CompatibilityScanner();
        CompatibilityScanner.Report withoutApi = scanner.scan(gameJar, List.of(modJar));
        assertTrue(withoutApi.findings().isEmpty(),
                "Fabric API references are only checked when a target Fabric API JAR is provided");

        CompatibilityScanner.Report report = scanner.scan(
                gameJar, List.of(modJar), null, null, null, List.of(targetApiJar));

        assertEquals(1, report.summary().missingMethods());
        assertEquals(0, report.summary().missingClasses());
        assertTrue(report.findings().stream().anyMatch(finding ->
                finding.kind().equals("MISSING_METHOD")
                        && finding.owner().equals("net/fabricmc/fabric/api/event/Event")
                        && finding.name().equals("removed")));
        assertEquals(List.of(targetApiJar.toAbsolutePath().normalize().toString()),
                report.targetApiJars());
    }

    private static byte[] aliasedLegacyModClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/AliasedLegacyMod",
                null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/LegacyTarget", "oldPresent", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fabricApiTargetClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/fabricmc/fabric/api/event/Event",
                null, "java/lang/Object", null);
        MethodVisitor register = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register", "()V", null, null);
        register.visitCode();
        register.visitInsn(Opcodes.RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fabricApiLegacyModClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyFabricApiMod",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/fabricmc/fabric/api/event/Event", "register", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/fabricmc/fabric/api/event/Event", "removed", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] jarBytes(String entryName, byte[] classBytes) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(classBytes);
            jar.closeEntry();
        }
        return bytes.toByteArray();
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
