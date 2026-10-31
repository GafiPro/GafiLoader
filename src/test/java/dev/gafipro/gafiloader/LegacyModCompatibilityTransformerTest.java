package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModCompatibilityTransformerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rewritesEntityRegistryReferenceInsideNestedFabricApiJarWithoutChangingOriginals() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] legacyMixin = classWithIntermediaryEntityTypePlayerReference();
        byte[] nestedRenderingJar = jar(Map.of(
                "net/fabricmc/fabric/mixin/client/rendering/EntityRenderersMixin.class", legacyMixin,
                "META-INF/TEST.SF", "old-signature".getBytes(StandardCharsets.UTF_8)));
        byte[] fabricApiJar = jar(Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"fabric-api\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-rendering-v1.jar", nestedRenderingJar));
        byte[] otherModJar = jar(Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"other-mod\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "example/OtherMod.class", classWithNoFieldReferences()));

        Path fabricApi = modsDirectory.resolve("fabric-api.jar");
        Path otherMod = modsDirectory.resolve("other-mod.jar");
        Files.write(fabricApi, fabricApiJar);
        Files.write(otherMod, otherModJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.rewrittenReferences());
        assertEquals(1, result.modifiedJars());
        assertEquals(gameDirectory.resolve(".gafiloader/compat-mods").toAbsolutePath().normalize(),
                result.modsDirectory());
        assertArrayEquals(fabricApiJar, Files.readAllBytes(fabricApi),
                "The original Fabric API outer JAR must remain byte-for-byte unchanged.");
        assertArrayEquals(otherModJar, Files.readAllBytes(otherMod),
                "Other original mod JARs must remain byte-for-byte unchanged.");

        Path stagedApi = result.modsDirectory().resolve("fabric-api.jar");
        Path stagedOther = result.modsDirectory().resolve("other-mod.jar");
        assertArrayEquals(otherModJar, Files.readAllBytes(stagedOther),
                "Unmodified mods should be copied without rewriting their content.");

        byte[] stagedNestedJar = readEntry(Files.readAllBytes(stagedApi),
                "META-INF/jars/fabric-rendering-v1.jar");
        byte[] stagedMixin = readEntry(stagedNestedJar,
                "net/fabricmc/fabric/mixin/client/rendering/EntityRenderersMixin.class");
        assertEquals("net/minecraft/world/entity/EntityTypes", firstFieldOwner(stagedMixin));
        assertEquals("PLAYER", firstFieldName(stagedMixin));
        assertEquals("Lnet/minecraft/class_1299;", firstFieldDescriptor(stagedMixin),
                "Keep the legacy descriptor for Fabric Loader to remap; only the new owner/name are injected.");
        assertFalse(hasEntry(stagedNestedJar, "META-INF/TEST.SF"),
                "Signatures on a modified nested archive must be removed.");
    }

    @Test
    void remapsLegacyFabricApiBytecodeInNestedJarBeforeFabricLoadsIt() throws Exception {
        LegacyClassTweakerMappings mappings = createBytecodeMappings();
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-bytecode-remap"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));

        byte[] originalClass = classWithIntermediaryRegistryReferences();
        byte[] nestedModule = jar(Map.of("net/example/LegacyApiEntrypoint.class", originalClass));
        byte[] originalApi = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-api","version":"0.141.6+1.21.11"}
                        """.getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-registry-api-v1.jar", nestedModule));
        Path source = modsDirectory.resolve("fabric-api.jar");
        Files.write(source, originalApi);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.remappedClasses());
        assertEquals(0, result.rewrittenReferences());
        assertEquals(1, result.modifiedJars());
        assertArrayEquals(originalApi, Files.readAllBytes(source),
                "The source Fabric API JAR must remain byte-for-byte unchanged.");

        byte[] stagedNested = readEntry(Files.readAllBytes(result.modsDirectory().resolve("fabric-api.jar")),
                "META-INF/jars/fabric-registry-api-v1.jar");
        byte[] stagedClass = readEntry(stagedNested, "net/example/LegacyApiEntrypoint.class");
        String constantPoolText = new String(stagedClass, StandardCharsets.ISO_8859_1);
        assertTrue(constantPoolText.contains("net/minecraft/core/Registry"), constantPoolText);
        assertTrue(constantPoolText.contains("lookup"), constantPoolText);
        assertTrue(constantPoolText.contains("entry"), constantPoolText);
        assertFalse(constantPoolText.contains("net/minecraft/class_7924"), constantPoolText);
        assertFalse(constantPoolText.contains("method_2345"), constantPoolText);
        assertFalse(constantPoolText.contains("field_1234"), constantPoolText);
    }

    @Test
    void rewritesNamedPlayerFieldReference() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-zombie"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] originalJar = jar(Map.of(
                "net/example/EntityHelper.class", classWithEntityTypeFieldReference("PLAYER")));
        Path source = modsDirectory.resolve("entity-helper.jar");
        Files.write(source, originalJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.rewrittenReferences());
        byte[] stagedClass = readEntry(Files.readAllBytes(result.modsDirectory().resolve("entity-helper.jar")),
                "net/example/EntityHelper.class");
        assertEquals("net/minecraft/world/entity/EntityTypes", firstFieldOwner(stagedClass));
        assertArrayEquals(originalJar, Files.readAllBytes(source));
    }

    @Test
    void doesNotCreateAStagedDirectoryWhenNoMovedRegistryReferenceIsFound() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-no-change"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        byte[] originalJar = jar(Map.of(
                "net/example/OtherMod.class", classWithNoFieldReferences()));
        Files.write(modsDirectory.resolve("other-mod.jar"), originalJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer().prepare(modsDirectory, gameDirectory);

        assertFalse(result.transformed());
        assertEquals(modsDirectory.toAbsolutePath().normalize(), result.modsDirectory());
        assertFalse(Files.exists(gameDirectory.resolve(".gafiloader/compat-mods")));
        assertArrayEquals(originalJar, Files.readAllBytes(modsDirectory.resolve("other-mod.jar")));
    }

    private static byte[] classWithEntityTypeFieldReference(String fieldName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/Generated", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "entityType", "()Lnet/minecraft/world/entity/EntityType;", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/EntityType",
                fieldName, "Lnet/minecraft/world/entity/EntityType;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithIntermediaryEntityTypePlayerReference() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/IntermediaryGenerated", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "entityType", "()Lnet/minecraft/class_1299;", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_1299",
                "field_6097", "Lnet/minecraft/class_1299;");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private LegacyClassTweakerMappings createBytecodeMappings() throws IOException {
        Path intermediaryJar = temporaryDirectory.resolve("intermediary-bytecode-fixture.jar");
        byte[] tiny = ("tiny\t2\t0\tofficial\tintermediary\n"
                + "c\ta\tnet/minecraft/class_7924\n"
                + "\tf\tLa;\tb\tfield_1234\n"
                + "\tm\t(La;)La;\tc\tmethod_2345\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(intermediaryJar, jar(Map.of("mappings/mappings.tiny", tiny)));

        Path mojangMappings = temporaryDirectory.resolve("client-mappings-bytecode-fixture.txt");
        Files.writeString(mojangMappings, """
                net.minecraft.core.Registry -> a:
                    net.minecraft.core.Registry entry -> b
                    net.minecraft.core.Registry lookup(net.minecraft.core.Registry) -> c
                """, StandardCharsets.UTF_8);
        return LegacyClassTweakerMappings.load(intermediaryJar, mojangMappings);
    }

    private static byte[] classWithIntermediaryRegistryReferences() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/LegacyApiEntrypoint", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "registry", "Lnet/minecraft/class_7924;", null, null)
                .visitEnd();

        MethodVisitor lookup = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lookup", "(Lnet/minecraft/class_7924;)Lnet/minecraft/class_7924;", null, null);
        lookup.visitCode();
        lookup.visitVarInsn(Opcodes.ALOAD, 0);
        lookup.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_7924", "method_2345",
                "(Lnet/minecraft/class_7924;)Lnet/minecraft/class_7924;", false);
        lookup.visitInsn(Opcodes.ARETURN);
        lookup.visitMaxs(1, 1);
        lookup.visitEnd();

        MethodVisitor field = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "field", "()Lnet/minecraft/class_7924;", null, null);
        field.visitCode();
        field.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_7924", "field_1234",
                "Lnet/minecraft/class_7924;");
        field.visitInsn(Opcodes.ARETURN);
        field.visitMaxs(1, 0);
        field.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithNoFieldReferences() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/example/NoReferences", null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String firstFieldOwner(byte[] classBytes) {
        AtomicReference<String> owner = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String fieldOwner, String fieldName,
                                               String fieldDescriptor) {
                        if (owner.get() == null) owner.set(fieldOwner);
                    }
                };
            }
        }, 0);
        return owner.get();
    }

    private static String firstFieldName(byte[] classBytes) {
        AtomicReference<String> name = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (name.get() == null) name.set(fieldName);
                    }
                };
            }
        }, 0);
        return name.get();
    }

    private static String firstFieldDescriptor(byte[] classBytes) {
        AtomicReference<String> descriptor = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (descriptor.get() == null) descriptor.set(fieldDescriptor);
                    }
                };
            }
        }, 0);
        return descriptor.get();
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] readEntry(byte[] archive, String requestedEntry) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(requestedEntry)) {
                    return input.readAllBytes();
                }
            }
        }
        throw new IOException("Missing test archive entry: " + requestedEntry);
    }

    private static boolean hasEntry(byte[] archive, String requestedEntry) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(requestedEntry)) return true;
            }
        }
        return false;
    }
}
