package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntermediaryBytecodeRemapperTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void remapsOwnersFieldsMethodsAndDescriptorsWithoutChangingInputBytes() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary.tiny");
        Files.writeString(mojang, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(intermediary, MojangMappings.load(mojang));
        byte[] input = legacyClass();
        byte[] preserved = input.clone();

        byte[] output = IntermediaryBytecodeRemapper.remap(input, mappings);

        assertNotNull(output);
        assertEquals(java.util.Arrays.hashCode(preserved), java.util.Arrays.hashCode(input));
        ClassInfo info = inspect(output);
        assertEquals("test/Legacy", info.name());
        assertEquals("(Lnet/minecraft/Foo;Lnet/minecraft/Bar;)V", info.runDescriptor());
        assertEquals(List.of(
                "FIELD net/minecraft/Foo#oldField:I",
                "METHOD net/minecraft/Foo#oldMethod(Lnet/minecraft/Bar;)V"
        ), info.references());
    }

    @Test
    void appliesReviewedFieldAndMethodRenamesAfterNamespaceNormalization() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-alias-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary-alias.tiny");
        Path aliasesFile = temporaryDirectory.resolve("runtime-aliases.tsv");
        Files.writeString(mojang, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));
        Files.writeString(aliasesFile, String.join("\n",
                "FIELD\tnet/minecraft/Foo\toldField\tI\tnet/minecraft/Foo\tcurrentField\tI",
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/Foo\tcurrentMethod\t(Lnet/minecraft/Bar;)V",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(intermediary, MojangMappings.load(mojang));
        CompatibilityScanner.AliasSet aliases = CompatibilityScanner.AliasSet.load(aliasesFile);
        byte[] output = IntermediaryBytecodeRemapper.remap(legacyClass(), mappings, aliases);

        assertNotNull(output);
        assertEquals(List.of(
                "FIELD net/minecraft/Foo#currentField:I",
                "METHOD net/minecraft/Foo#currentMethod(Lnet/minecraft/Bar;)V"
        ), inspect(output).references());
    }

    @Test
    void aliasesCanMoveFieldAndMethodReferencesToAnInheritedOwner() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-owner-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary-owner.tiny");
        Path aliasesFile = temporaryDirectory.resolve("runtime-owner-aliases.tsv");
        Files.writeString(mojang, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));
        Files.writeString(aliasesFile, String.join("\n",
                "FIELD\tnet/minecraft/Foo\toldField\tI\tnet/minecraft/Base\tcurrentField\tI",
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/Base\tcurrentMethod\t(Lnet/minecraft/Bar;)V",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(intermediary, MojangMappings.load(mojang));
        CompatibilityScanner.AliasSet aliases = CompatibilityScanner.AliasSet.load(aliasesFile);
        byte[] output = IntermediaryBytecodeRemapper.remap(legacyClass(), mappings, aliases);

        assertNotNull(output);
        assertEquals(List.of(
                "FIELD net/minecraft/Base#currentField:I",
                "METHOD net/minecraft/Base#currentMethod(Lnet/minecraft/Bar;)V"
        ), inspect(output).references());
    }

    @Test
    void rejectsAliasWhenTargetStaticnessDoesNotMatchLegacyCallOpcode() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-static-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary-static.tiny");
        Path aliasesFile = temporaryDirectory.resolve("runtime-static-aliases.tsv");
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2-static.jar");
        Files.writeString(mojang, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));
        Files.writeString(aliasesFile,
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/Foo\tcurrentMethod\t(Lnet/minecraft/Bar;)V\n");
        writeJar(targetJar,
                "net/minecraft/Foo", targetFooWithStaticMethod(),
                "net/minecraft/Bar", emptyClass("net/minecraft/Bar"));

        IntermediaryMappings mappings = IntermediaryMappings.load(intermediary, MojangMappings.load(mojang));
        CompatibilityScanner.AliasSet aliases = CompatibilityScanner.AliasSet.load(aliasesFile);
        aliases.validateAgainstTarget(targetJar);

        assertThrows(IOException.class, () -> IntermediaryBytecodeRemapper.remap(
                legacyClass(), mappings, aliases, TargetApiIndex.load(targetJar)));
    }

    @Test
    void rejectsAliasWhenOwnerChangesFromClassReferenceToInterfaceReference() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-interface-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary-interface.tiny");
        Path aliasesFile = temporaryDirectory.resolve("runtime-interface-aliases.tsv");
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2-interface.jar");
        Files.writeString(mojang, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));
        Files.writeString(aliasesFile,
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/IFoo\tcurrentMethod\t(Lnet/minecraft/Bar;)V\n");
        writeJar(targetJar,
                "net/minecraft/Foo", targetFooWithStaticMethod(),
                "net/minecraft/Bar", emptyClass("net/minecraft/Bar"),
                "net/minecraft/IFoo", interfaceWithMethod(
                        "net/minecraft/IFoo", "currentMethod", "(Lnet/minecraft/Bar;)V"));

        IntermediaryMappings mappings = IntermediaryMappings.load(intermediary, MojangMappings.load(mojang));
        CompatibilityScanner.AliasSet aliases = CompatibilityScanner.AliasSet.load(aliasesFile);
        aliases.validateAgainstTarget(targetJar);

        assertThrows(IOException.class, () -> IntermediaryBytecodeRemapper.remap(
                legacyClass(), mappings, aliases, TargetApiIndex.load(targetJar)));
    }

    @Test
    void returnsNullWhenClassContainsNoKnownIntermediarySymbols() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary.tiny");
        Files.writeString(mojang, "net.minecraft.Foo -> a:\n");
        Files.writeString(intermediary, "tiny\t2\t0\tofficial\tintermediary\nc\ta\tnet/minecraft/class_1\n");
        IntermediaryMappings mappings = IntermediaryMappings.load(intermediary, MojangMappings.load(mojang));

        byte[] output = IntermediaryBytecodeRemapper.remap(unrelatedClass(), mappings);

        assertNull(output);
    }

    private static byte[] legacyClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Legacy", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "(Lnet/minecraft/class_1;Lnet/minecraft/class_2;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_1", "field_1", "I");
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1", "method_2",
                "(Lnet/minecraft/class_2;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetFooWithStaticMethod() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/Foo", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "oldField", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "currentMethod",
                "(Lnet/minecraft/Bar;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceWithMethod(String name, String methodName, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                methodName, descriptor, null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, Object... keyValues) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (int i = 0; i < keyValues.length; i += 2) {
                String owner = (String) keyValues[i];
                byte[] classBytes = (byte[]) keyValues[i + 1];
                jar.putNextEntry(new JarEntry(owner + ".class"));
                jar.write(classBytes);
                jar.closeEntry();
            }
        }
    }

    private static byte[] unrelatedClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Unrelated", null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassInfo inspect(byte[] classBytes) {
        String[] className = new String[1];
        String[] runDescriptor = new String[1];
        List<String> references = new ArrayList<>();
        new ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                className[0] = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals("run")) {
                    runDescriptor[0] = descriptor;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String descriptor) {
                        references.add("FIELD " + owner + "#" + fieldName + ":" + descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String descriptor, boolean isInterface) {
                        references.add("METHOD " + owner + "#" + methodName + descriptor);
                    }
                };
            }
        }, 0);
        return new ClassInfo(className[0], runDescriptor[0], List.copyOf(references));
    }

    private record ClassInfo(String name, String runDescriptor, List<String> references) {}
}
