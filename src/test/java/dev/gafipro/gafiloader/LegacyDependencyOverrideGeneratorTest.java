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
        assertTrue(Files.exists(config.resolveSibling("fabric_loader_dependencies.json.bak")));
        assertTrue(Files.readString(config.resolveSibling("fabric_loader_dependencies.json.bak"))
                .contains("\"other-mod\""));
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
    void detectsLegacyApiByItsArtifactVersionWhenMinecraftRangeIsIndirect() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("version-only-mods"));
        writeJar(mods.resolve("fabric-api.jar"), "fabric.mod.json", """
                {
                  "id": "fabric-api",
                  "version": "0.141.6+1.21.11",
                  "depends": { "fabricloader": ">=0.16.0" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("version-only-config/fabric_loader_dependencies.json");
        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("overrides").has("fabric-api"));
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
        writeJar(mods.resolve("version-prefix-trap.jar"), "fabric.mod.json", """
                {
                  "id": "version-prefix-trap",
                  "depends": { "minecraft": "1.21.110" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("modern-config/fabric_loader_dependencies.json");
        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("overrides").isEmpty());
        assertEquals(0, result.modIdsOverridden());
    }


    @Test
    void recoversFromCorruptConfigUsingLastKnownGoodBackupWithoutDestroyingEitherCopy() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("recovery-mods"));
        writeJar(mods.resolve("fabric-api.jar"), "fabric.mod.json", """
                {
                  "id": "fabric-api",
                  "version": "0.141.6+1.21.11",
                  "depends": { "fabricloader": ">=0.16.0" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("recovery-config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{ this is broken JSON");
        Path backup = config.resolveSibling("fabric_loader_dependencies.json.bak");
        Files.writeString(backup, """
                {
                  "version": 1,
                  "overrides": {
                    "user-custom-override": {
                      "-depends": { "java": "" }
                    }
                  }
                }
                """);

        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(Files.exists(config.resolveSibling("fabric_loader_dependencies.json.corrupt")));
        assertTrue(Files.readString(config.resolveSibling("fabric_loader_dependencies.json.corrupt"))
                .contains("broken JSON"));
        assertTrue(Files.readString(backup).contains("user-custom-override"),
                "Recovery must not replace the last known-good backup with the damaged file");
        assertTrue(root.getAsJsonObject("overrides").has("user-custom-override"));
        assertTrue(root.getAsJsonObject("overrides").has("fabric-api"));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("recovered the last known-good")));
    }


    @Test
    void rebuildsConfigWhenBothCurrentAndBackupAreCorrupt() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("double-corrupt-mods"));
        writeJar(mods.resolve("fabric-api.jar"), "fabric.mod.json", """
                {
                  "id": "fabric-api",
                  "version": "0.141.6+1.21.11",
                  "depends": { "fabricloader": ">=0.16.0" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("double-corrupt-config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{ broken current config");
        Path backup = config.resolveSibling("fabric_loader_dependencies.json.bak");
        Files.writeString(backup, "{ broken backup config");

        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(Files.exists(config.resolveSibling("fabric_loader_dependencies.json.corrupt")));
        assertTrue(Files.readString(backup).contains("broken backup config"),
                "The invalid backup must remain available for manual recovery");
        assertTrue(root.getAsJsonObject("overrides").has("fabric-api"),
                "A fresh, valid config should still include detected legacy mod overrides");
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("rebuilding a fresh config")));
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
