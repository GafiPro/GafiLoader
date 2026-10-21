package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameApiComparatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesObfuscatedLegacyClassesAndSuggestsRenamedMembers() throws IOException {
        Path legacyJar = temporaryDirectory.resolve("minecraft-1.21.11-obfuscated.jar");
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2-named.jar");
        Path mappings = temporaryDirectory.resolve("client-mappings.txt");

        Files.writeString(mappings, String.join("\n",
                "com.example.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(com.example.Bar):12:12 -> b",
                "com.example.Bar -> d:",
                ""));
        writeJar(legacyJar,
                entry("a", legacyFooClass()),
                entry("d", emptyClass("d")));
        writeJar(targetJar,
                entry("com/example/Foo", targetFooClass()),
                entry("com/example/Bar", emptyClass("com/example/Bar")));

        GameApiComparator.Report report = new GameApiComparator().compare(legacyJar, targetJar, mappings);

        assertEquals(2, report.legacyClassesMapped());
        assertEquals(2, report.legacyMembersMapped());
        assertEquals(0, report.summary().missingClasses());
        assertEquals(2, report.summary().renamedCandidates());
        assertTrue(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("METHOD_NAME_CHANGED_CANDIDATE")
                        && difference.owner().equals("com/example/Foo")
                        && difference.name().equals("oldMethod")
                        && difference.candidates().contains("newMethod")));
        assertTrue(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("FIELD_NAME_CHANGED_CANDIDATE")
                        && difference.owner().equals("com/example/Foo")
                        && difference.name().equals("oldField")
                        && difference.candidates().contains("newField")));
    }

    @Test
    void doesNotSuggestNameAliasesWhenStaticInstanceSemanticsDiffer() throws IOException {
        Path legacyJar = temporaryDirectory.resolve("legacy-dispatch.jar");
        Path targetJar = temporaryDirectory.resolve("target-dispatch.jar");
        Path mappings = temporaryDirectory.resolve("dispatch-mappings.txt");

        Files.writeString(mappings, "com.example.Foo -> a:\n");
        writeJar(legacyJar, entry("a", legacyDispatchChangedClass()));
        writeJar(targetJar, entry("com/example/Foo", targetDispatchChangedClass()));

        GameApiComparator.Report report = new GameApiComparator().compare(legacyJar, targetJar, mappings);

        assertTrue(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("METHOD_DISPATCH_CHANGED_CANDIDATE")
                        && difference.owner().equals("com/example/Foo")
                        && difference.name().equals("oldName")
                        && difference.candidates().contains("newName")));
        assertTrue(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("METHOD_DISPATCH_CHANGED")
                        && difference.owner().equals("com/example/Foo")
                        && difference.name().equals("sameName")));
        assertFalse(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("METHOD_NAME_CHANGED_CANDIDATE")
                        && difference.owner().equals("com/example/Foo")
                        && difference.name().equals("oldName")));
    }

    @Test
    void doesNotReportPrivateSyntheticLambdaBodiesAsRemovedApiMethods() throws IOException {
        Path legacyJar = temporaryDirectory.resolve("legacy-with-lambda.jar");
        Path targetJar = temporaryDirectory.resolve("target-without-lambda.jar");
        Path mappings = temporaryDirectory.resolve("lambda-mappings.txt");

        Files.writeString(mappings, "com.example.Foo -> a:\\n");
        writeJar(legacyJar, entry("a", legacySyntheticLambdaClass()));
        writeJar(targetJar, entry("com/example/Foo", emptyClass("com/example/Foo")));

        GameApiComparator.Report report = new GameApiComparator().compare(legacyJar, targetJar, mappings);

        assertFalse(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("MISSING_METHOD")
                        && difference.name().equals("lambda$run$0")));
    }

    @Test
    void reportsClassesThatNoLongerExistInTarget() throws IOException {
        Path legacyJar = temporaryDirectory.resolve("legacy-missing-class.jar");
        Path targetJar = temporaryDirectory.resolve("target-missing-class.jar");
        Path mappings = temporaryDirectory.resolve("minimal-mappings.txt");

        Files.writeString(mappings, "com.example.Vanished -> z:\n");
        writeJar(legacyJar, entry("z", emptyClass("z")));
        writeJar(targetJar, entry("com/example/Present", emptyClass("com/example/Present")));

        GameApiComparator.Report report = new GameApiComparator().compare(legacyJar, targetJar, mappings);

        assertEquals(1, report.summary().missingClasses());
        assertTrue(report.differences().stream().anyMatch(difference ->
                difference.kind().equals("MISSING_CLASS")
                        && difference.owner().equals("com/example/Vanished")));
    }

    private static byte[] legacySyntheticLambdaClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$run$0", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyFooClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null);

        FieldVisitor field = writer.visitField(Opcodes.ACC_PUBLIC, "c", "I", null, null);
        field.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "b",
                "(Ld;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetFooClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Foo",
                null, "java/lang/Object", null);

        FieldVisitor field = writer.visitField(Opcodes.ACC_PUBLIC, "newField", "I", null, null);
        field.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "newMethod",
                "(Lcom/example/Bar;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyDispatchChangedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a", null, "java/lang/Object", null);

        MethodVisitor oldName = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "oldName", "()V", null, null);
        oldName.visitCode();
        oldName.visitInsn(Opcodes.RETURN);
        oldName.visitMaxs(0, 0);
        oldName.visitEnd();

        MethodVisitor sameName = writer.visitMethod(Opcodes.ACC_PUBLIC, "sameName", "()I", null, null);
        sameName.visitCode();
        sameName.visitIntInsn(Opcodes.BIPUSH, 7);
        sameName.visitInsn(Opcodes.IRETURN);
        sameName.visitMaxs(0, 0);
        sameName.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetDispatchChangedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Foo",
                null, "java/lang/Object", null);

        MethodVisitor newName = writer.visitMethod(Opcodes.ACC_PUBLIC, "newName", "()V", null, null);
        newName.visitCode();
        newName.visitInsn(Opcodes.RETURN);
        newName.visitMaxs(0, 0);
        newName.visitEnd();

        MethodVisitor sameName = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sameName", "()I", null, null);
        sameName.visitCode();
        sameName.visitIntInsn(Opcodes.BIPUSH, 7);
        sameName.visitInsn(Opcodes.IRETURN);
        sameName.visitMaxs(0, 0);
        sameName.visitEnd();

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
