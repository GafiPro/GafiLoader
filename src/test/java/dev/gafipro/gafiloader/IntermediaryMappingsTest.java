package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediaryMappingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsProductionIntermediaryReferencesThroughOfficialNamesToTargetClasses() throws IOException {
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2.jar");
        Path modJar = temporaryDirectory.resolve("legacy-intermediary-mod.jar");
        Path mojangMappings = temporaryDirectory.resolve("client-mappings.txt");
        Path intermediaryMappings = temporaryDirectory.resolve("intermediary-1.21.11.tiny");

        Files.writeString(mojangMappings, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                "net.minecraft.Child -> e:",
                ""));

        Files.writeString(intermediaryMappings, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                "c\te\tnet/minecraft/class_3",
                ""));

        writeJar(targetJar,
                entry("net/minecraft/Foo", targetFooClass()),
                entry("net/minecraft/Bar", emptyClass("net/minecraft/Bar")));
        writeJar(modJar, entry("test/IntermediaryMod", intermediaryModClass()));

        CompatibilityScanner.Report report = new CompatibilityScanner().scan(
                targetJar, List.of(modJar), null, intermediaryMappings, mojangMappings);

        assertTrue(report.findings().isEmpty(), () -> report.findings().toString());
        assertEquals(2, report.summary().intermediaryClassesMapped());
        assertEquals(2, report.summary().intermediaryMembersMapped());

        IntermediaryMappings loaded = IntermediaryMappings.load(
                intermediaryMappings, MojangMappings.load(mojangMappings));
        assertEquals("net/minecraft/Foo$1", loaded.mapClassName("net/minecraft/class_1$1"));
        assertEquals("(Lnet/minecraft/Foo$Nested;)V",
                loaded.mapDescriptor("(Lnet/minecraft/class_1$Nested;)V"));

        CompatibilityScanner.Reference inheritedMethod = loaded.normalize(
                new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.METHOD,
                        "net/minecraft/class_3", "method_2", "(Lnet/minecraft/class_2;)V"));
        assertEquals("net/minecraft/Child", inheritedMethod.owner());
        assertEquals("oldMethod", inheritedMethod.name());
        assertEquals("(Lnet/minecraft/Bar;)V", inheritedMethod.descriptor());
    }

    private static byte[] targetFooClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/Foo",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "oldField", "I", null, null).visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "oldMethod",
                "(Lnet/minecraft/Bar;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] intermediaryModClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/IntermediaryMod",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1", "method_2",
                "(Lnet/minecraft/class_2;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static JarEntryData entry(String name, byte[] bytes) {
        return new JarEntryData(name + ".class", bytes);
    }

    private static void writeJar(Path jarPath, JarEntryData... entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (JarEntryData entry : entries) {
                jar.putNextEntry(new JarEntry(entry.path()));
                jar.write(entry.bytes());
                jar.closeEntry();
            }
        }
    }

    private record JarEntryData(String path, byte[] bytes) {}
}
