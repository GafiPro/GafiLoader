package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyClassTweakerRemapperTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void remapsClassFieldMethodAndDescriptorNamesFromIntermediaryToOfficial() throws Exception {
        LegacyClassTweakerMappings mappings = createMappings();
        LegacyClassTweakerRemapper remapper =
                new LegacyClassTweakerRemapper(temporaryDirectory, mappings);
        String input = """
                classTweaker v1 intermediary
                # keep comments
                accessible class net/minecraft/class_1299
                mutable field net/minecraft/class_1299 field_6097 I
                accessible method net/minecraft/class_1299 method_42 (Lnet/minecraft/class_1299;)V # method target
                """;

        LegacyClassTweakerRemapper.Result result = remapper.remap(input);

        assertTrue(result.remapped());
        assertTrue(result.contents().startsWith("classTweaker v1 official"));
        assertTrue(result.contents().contains("accessible class net/minecraft/world/entity/EntityType"));
        assertTrue(result.contents().contains("mutable field net/minecraft/world/entity/EntityType player I"));
        assertTrue(result.contents().contains(
                "accessible method net/minecraft/world/entity/EntityType tick "
                        + "(Lnet/minecraft/world/entity/EntityType;)V # method target"));
        assertTrue(result.contents().contains("# keep comments"));
    }

    @Test
    void remapsConstructorOwnerAndDescriptorWhenIntermediaryMappingsOmitInit() throws Exception {
        LegacyClassTweakerMappings mappings = createMappings();
        LegacyClassTweakerRemapper remapper =
                new LegacyClassTweakerRemapper(temporaryDirectory, mappings);
        String input = """
                classTweaker v1 intermediary
                accessible method net/minecraft/class_1959$class_5482 <init> (ZFLnet/minecraft/class_1959$class_5484;F)V
                """;

        LegacyClassTweakerRemapper.Result result = remapper.remap(input);

        assertTrue(result.remapped());
        assertTrue(result.contents().contains(
                "accessible method net/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier "
                        + "<init> (ZFLnet/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier$Test;F)V"),
                result.contents());
    }

    @Test
    void remapsClassTweakerInsideNestedFabricApiJarAndPreservesOriginalArchives() throws Exception {
        LegacyClassTweakerMappings mappings = createMappings();
        String classTweaker = String.valueOf((char) 0xFEFF) + """
                classTweaker v1 intermediary
                accessible class net/minecraft/class_1299
                mutable field net/minecraft/class_1299 field_6097 I
                """;
        byte[] nestedJar = jar(Map.of(
                "fabric-biome-api-v1.classtweaker", classTweaker.getBytes(StandardCharsets.UTF_8)));
        byte[] originalOuterJar = jar(Map.of(
                "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"fabric-api\"}"
                        .getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-biome-api-v1.jar", nestedJar));

        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        Path original = modsDirectory.resolve("fabric-api.jar");
        Files.write(original, originalOuterJar);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed(), "Staging should change the nested module because its metadata declares "
                + "an Intermediary ClassTweaker resource.");
        assertEquals(1, result.rewrittenClassTweakerFiles());
        assertEquals(0, result.rewrittenReferences());
        assertEquals(1, result.modifiedJars());
        assertArrayEquals(originalOuterJar, Files.readAllBytes(original),
                "The original Fabric API archive must remain byte-for-byte unchanged.");

        Path staged = result.modsDirectory().resolve("fabric-api.jar");
        byte[] stagedNestedJar = readEntry(Files.readAllBytes(staged),
                "META-INF/jars/fabric-biome-api-v1.jar");
        String stagedClassTweaker = new String(
                readEntry(stagedNestedJar, "fabric-biome-api-v1.classtweaker"),
                StandardCharsets.UTF_8);
        assertTrue(stagedClassTweaker.startsWith("classTweaker v1 official"));
        assertTrue(stagedClassTweaker.contains("net/minecraft/world/entity/EntityType"));
        assertTrue(stagedClassTweaker.contains("player I"));
        assertFalse(stagedClassTweaker.contains("net/minecraft/class_1299"));
    }

    @Test
    void remapsMetadataDeclaredClassTweakerWithCustomNameAndWhitespaceBeforeBom() throws Exception {
        LegacyClassTweakerMappings mappings = createMappings();
        String classTweaker = " " + String.valueOf((char) 0xFEFF)
                + "classTweaker v1 intermediary" + System.lineSeparator()
                + "accessible class net/minecraft/class_1299" + System.lineSeparator();
        byte[] moduleMetadata = """
                {"schemaVersion":1,"id":"fabric-biome-api-v1",
                 "accessWidener":"compat/biome-tweaker.data"}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] outerMetadata = """
                {"schemaVersion":1,"id":"fabric-api",
                 "jars":[{"file":"META-INF/jars/fabric-biome-api-v1.jar"}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] moduleJar = jar(Map.of(
                "fabric.mod.json", moduleMetadata,
                "compat/biome-tweaker.data", classTweaker.getBytes(StandardCharsets.UTF_8)));
        byte[] outerJar = jar(Map.of(
                "fabric.mod.json", outerMetadata,
                "META-INF/jars/fabric-biome-api-v1.jar", moduleJar));

        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game-custom-resource"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));
        Path original = modsDirectory.resolve("fabric-api.jar");
        Files.write(original, outerJar);

        assertEquals("compat/biome-tweaker.data",
                LegacyModCompatibilityTransformer.declaredClassTweakerResource(moduleJar));
        LegacyClassTweakerRemapper directRemapper =
                new LegacyClassTweakerRemapper(gameDirectory, mappings);
        assertTrue(directRemapper.remap(classTweaker).remapped(),
                "The whitespace/BOM-prefixed ClassTweaker header must be recognized.");

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(mappings).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(1, result.rewrittenClassTweakerFiles());
        assertArrayEquals(outerJar, Files.readAllBytes(original),
                "Metadata-driven rewriting must never modify the original outer mod JAR.");

        byte[] stagedNestedJar = readEntry(Files.readAllBytes(
                result.modsDirectory().resolve("fabric-api.jar")),
                "META-INF/jars/fabric-biome-api-v1.jar");
        String remapped = new String(readEntry(stagedNestedJar, "compat/biome-tweaker.data"),
                StandardCharsets.UTF_8);
        assertTrue(remapped.startsWith("classTweaker v1 official"));
        assertTrue(remapped.contains("net/minecraft/world/entity/EntityType"));
        assertFalse(remapped.contains("net/minecraft/class_1299"));
    }

    private LegacyClassTweakerMappings createMappings() throws IOException {
        Path intermediaryJar = temporaryDirectory.resolve("intermediary-1.21.11-v2.jar");
        byte[] tiny = ("tiny\t2\t0\tofficial\tintermediary\n"
                + "c\ta\tnet/minecraft/class_1299\n"
                + "\tf\tI\tb\tfield_6097\n"
                + "\tm\t(La;)V\tc\tmethod_42\n"
                + "c\td\tnet/minecraft/class_1959$class_5482\n"
                + "c\te\tnet/minecraft/class_1959$class_5484\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(intermediaryJar, jar(Map.of("mappings/mappings.tiny", tiny)));

        Path mojangMappings = temporaryDirectory.resolve("client-mappings-1.21.11.txt");
        Files.writeString(mojangMappings, """
                net.minecraft.world.entity.EntityType -> a:
                    int player -> b
                    void tick(net.minecraft.world.entity.EntityType) -> c
                net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier -> d:
                net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier$Test -> e:
                """, StandardCharsets.UTF_8);
        return LegacyClassTweakerMappings.load(intermediaryJar, mojangMappings);
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
                if (entry.getName().equals(requestedEntry)) return input.readAllBytes();
            }
        }
        throw new IOException("Missing test archive entry: " + requestedEntry);
    }
}
