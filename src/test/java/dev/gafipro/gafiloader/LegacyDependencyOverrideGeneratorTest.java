package dev.gafipro.gafiloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDependencyOverrideGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesOverridesForLegacyModsAndPreservesExistingConfig() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("mods"));
        Path mod = mods.resolve("fabric-api.jar");
        writeJar(mod, "fabric.mod.json", """
                {
                  "id": "fabric-api",
                  "depends": {
                    "minecraft": "1.21.11",
                    "fabricloader": ">=0.16.0"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {
                  "version": 1,
                  "overrides": {
                    "other-mod": {
                      "-depends": {
                        "java": ""
                      }
                    }
                  }
                }
                """);

        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        JsonObject overrides = root.getAsJsonObject("overrides");
        assertEquals(1, root.get("version").getAsInt());
        assertEquals("", overrides.getAsJsonObject("fabric-api")
                .getAsJsonObject("-depends").get("minecraft").getAsString());
        assertEquals("", overrides.getAsJsonObject("other-mod")
                .getAsJsonObject("-depends").get("java").getAsString());
        assertEquals(1, result.modIdsOverridden());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void discoversLegacyFabricModNestedInsideAnApiJar() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("nested-mods"));
        Path outer = mods.resolve("fabric-api.jar");
        byte[] nestedJar = zipBytes("fabric.mod.json", """
                {
                  "id": "fabric-api-base",
                  "depends": { "minecraft": "~1.21.11" }
                }
                """.getBytes(StandardCharsets.UTF_8));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry("META-INF/jars/fabric-api-base.jar"));
            jar.write(nestedJar);
            jar.closeEntry();
        }

        Path config = temporaryDirectory.resolve("nested-config/fabric_loader_dependencies.json");
        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("overrides").has("fabric-api-base"));
        assertEquals(1, result.modIdsOverridden());
    }

    @Test
    void ignoresModsForOtherMinecraftVersions() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("modern-mods"));
        writeJar(mods.resolve("modern.jar"), "fabric.mod.json", """
                {
                  "id": "modern-mod",
                  "depends": { "minecraft": "~26.2" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("modern-config/fabric_loader_dependencies.json");
        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("overrides").isEmpty());
        assertEquals(0, result.modIdsOverridden());
    }

    private static byte[] zipBytes(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void writeJar(Path path, String name, byte[] content) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry(name));
            jar.write(content);
            jar.closeEntry();
        }
    }
}
