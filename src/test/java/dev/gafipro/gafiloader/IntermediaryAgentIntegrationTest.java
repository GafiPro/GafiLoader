package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediaryAgentIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedAgentRemapsIntermediaryReferencesBeforeLegacyClassIsDefined() throws Exception {
        String agentJarSetting = System.getProperty("gafi.loader.agent.jar");
        assertNotNull(agentJarSetting, "Gradle must provide the built self-contained agent JAR");
        Path agentJar = Path.of(agentJarSetting);
        assertTrue(Files.isRegularFile(agentJar), "Agent JAR not found: " + agentJar);

        Path gameFoo = writeClass("net/minecraft/Foo", fooClass());
        Path gameBar = writeClass("net/minecraft/Bar", emptyPublicClass("net/minecraft/Bar"));
        Path legacyModClass = writeClass("test/LegacyIntermediaryMod", intermediaryModClass());
        Path legacyModJar = temporaryDirectory.resolve("legacy-intermediary-mod.jar");
        byte[] originalModClass = Files.readAllBytes(legacyModClass);
        writeJar(legacyModJar, "test/LegacyIntermediaryMod", originalModClass);
        byte[] originalModJar = Files.readAllBytes(legacyModJar);
        Files.delete(legacyModClass);

        Path targetGameJar = temporaryDirectory.resolve("minecraft-26.2-fixture.jar");
        writeJar(targetGameJar,
                "net/minecraft/Foo", fooClass(),
                "net/minecraft/Bar", emptyPublicClass("net/minecraft/Bar"),
                "net/minecraft/client/renderer/block/BlockAndTintGetter",
                emptyPublicClass("net/minecraft/client/renderer/block/BlockAndTintGetter"));
        assertTrue(Files.exists(gameFoo));
        assertTrue(Files.exists(gameBar));
        Path mojang = temporaryDirectory.resolve("client.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary.tiny");
        Path aliases = temporaryDirectory.resolve("runtime-bytecode-aliases.tsv");
        Path config = temporaryDirectory.resolve("gafi-loader-agent.properties");
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
        Files.writeString(aliases, String.join("\n",
                "FIELD\tnet/minecraft/Foo\toldField\tI\tnet/minecraft/Foo\tcurrentField\tI",
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/Foo\tcurrentMethod\t(Lnet/minecraft/Bar;)V",
                ""));
        Files.writeString(config, String.join("\n",
                "targetGameJar=minecraft-26.2-fixture.jar",
                "intermediaryMappings=intermediary.tiny",
                "legacyMappings=client.txt",
                "bytecodeAliases=runtime-bytecode-aliases.tsv",
                ""));

        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        String classpath = temporaryDirectory + java.io.File.pathSeparator
                + legacyModJar + java.io.File.pathSeparator
                + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-javaagent:" + agentJar + "=" + config,
                "-cp", classpath,
                "dev.gafipro.gafiloader.IntermediaryRemapSmokeMain")
                .redirectErrorStream(true)
                .start();

        boolean exited = process.waitFor(25, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "Agent subprocess timed out. Output:\n" + output);
        assertEquals(0, process.exitValue(), "Agent subprocess failed. Output:\n" + output);
        assertTrue(output.contains("INTERMEDIARY_AGENT_REMAP_OK"),
                "Intermediary owner/member references were not remapped. Output:\n" + output);
        assertTrue(output.contains("Intermediary remapping enabled"),
                "Agent did not enable configured mappings. Output:\n" + output);
        assertTrue(java.util.Arrays.equals(originalModJar, Files.readAllBytes(legacyModJar)),
                "The original legacy mod JAR must remain byte-for-byte unchanged.");
        assertTrue(Files.notExists(legacyModClass),
                "The legacy class fixture must be loaded from the mod JAR, not an unpacked class file.");
    }

    private Path writeClass(String internalName, byte[] bytes) throws IOException {
        Path path = temporaryDirectory.resolve(internalName + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return path;
    }

    private static byte[] fooClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/Foo", null,
                "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        FieldVisitor field = writer.visitField(Opcodes.ACC_PUBLIC, "currentField", "I", null, 7);
        field.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "currentMethod",
                "(Lnet/minecraft/Bar;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyPublicClass(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void writeJar(Path path, Object... keyValues) throws IOException {
        try (java.io.OutputStream output = Files.newOutputStream(path);
             java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(output)) {
            for (int i = 0; i < keyValues.length; i += 2) {
                String owner = (String) keyValues[i];
                byte[] classBytes = (byte[]) keyValues[i + 1];
                jar.putNextEntry(new java.util.jar.JarEntry(owner + ".class"));
                jar.write(classBytes);
                jar.closeEntry();
            }
        }
    }

    private static byte[] intermediaryModClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyIntermediaryMod",
                null, "java/lang/Object", null);
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
}
