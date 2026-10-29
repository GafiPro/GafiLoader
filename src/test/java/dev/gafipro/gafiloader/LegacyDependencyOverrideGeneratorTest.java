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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        String originalConfig = """
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
                """;
        Files.writeString(config, originalConfig);

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

        // A later launch must not replace the original backup with our generated config.
        new LegacyDependencyOverrideGenerator().generate(mods, config);
        assertEquals(originalConfig,
                Files.readString(config.resolveSibling("fabric_loader_dependencies.json.bak")));
        JsonObject afterSecondRun = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertEquals("", afterSecondRun.getAsJsonObject("overrides")
                .getAsJsonObject("fabric-api").getAsJsonObject("-depends")
                .get("minecraft").getAsString());
        assertEquals("", afterSecondRun.getAsJsonObject("overrides")
                .getAsJsonObject("other-mod").getAsJsonObject("-depends")
                .get("java").getAsString());
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
    void refusesToReplaceMalformedExistingOverrideForLegacyMod() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("malformed-override-mods"));
        writeJar(mods.resolve("fabric-api.jar"), "fabric.mod.json", """
                {
                  "id": "fabric-api",
                  "depends": { "minecraft": "1.21.11" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path config = temporaryDirectory.resolve("malformed-override-config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        String originalConfig = """
                {
                  "version": 1,
                  "overrides": {
                    "fabric-api": {
                      "-depends": "not-an-object"
                    }
                  }
                }
                """;
        Files.writeString(config, originalConfig, StandardCharsets.UTF_8);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new LegacyDependencyOverrideGenerator().generate(mods, config));

        assertTrue(exception.getMessage().contains("fabric-api"));
        assertTrue(exception.getMessage().contains("must be a JSON object"));
        assertEquals(originalConfig, Files.readString(config, StandardCharsets.UTF_8),
                "Malformed config must remain untouched when generation is rejected.");
        assertTrue(!Files.exists(config.resolveSibling("fabric_loader_dependencies.json.bak")),
                "A backup must not be created from a config that was never safely merged.");
    }

    @Test
    void handlesFabricApiAggregatorAndNestedLegacyModulesTogether() throws IOException {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("fabric-api-bundle-mods"));
        byte[] nestedBase = zipBytes("fabric.mod.json", """
                {
                  "id": "fabric-api-base",
                  "version": "1.6.0+1.21.11",
                  "depends": {
                    "minecraft": "~1.21.11",
                    "fabricloader": ">=0.16.0"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
        byte[] nestedRendering = zipBytes("fabric.mod.json", """
                {
                  "id": "fabric-rendering-v1",
                  "version": "16.0.0+1.21.11",
                  "depends": { "minecraft": "1.21.11" }
                }
                """.getBytes(StandardCharsets.UTF_8));

        Path outer = mods.resolve("fabric-api-0.141.6+1.21.11.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write("""
                    {
                      "id": "fabric-api",
                      "version": "0.141.6+1.21.11",
                      "depends": { "fabricloader": ">=0.16.0" },
                      "jars": [
                        { "file": "META-INF/jars/fabric-api-base.jar" },
                        { "file": "META-INF/jars/fabric-rendering-v1.jar" }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/jars/fabric-api-base.jar"));
            jar.write(nestedBase);
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/jars/fabric-rendering-v1.jar"));
            jar.write(nestedRendering);
            jar.closeEntry();
        }

        Path config = temporaryDirectory.resolve("fabric-api-bundle-config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {
                  "version": 1,
                  "overrides": {
                    "fabric-api": { "-depends": { "java": "" } },
                    "unrelated-mod": { "-depends": { "minecraft": "" } }
                  }
                }
                """, StandardCharsets.UTF_8);

        LegacyDependencyOverrideGenerator.Result result =
                new LegacyDependencyOverrideGenerator().generate(mods, config);

        JsonObject overrides = JsonParser.parseString(Files.readString(config))
                .getAsJsonObject().getAsJsonObject("overrides");
        for (String modId : new String[] { "fabric-api", "fabric-api-base", "fabric-rendering-v1" }) {
            assertEquals("", overrides.getAsJsonObject(modId)
                    .getAsJsonObject("-depends").get("minecraft").getAsString(), modId);
        }
        assertEquals("", overrides.getAsJsonObject("fabric-api")
                .getAsJsonObject("-depends").get("java").getAsString(),
                "An existing unrelated rule on the same mod must survive the merge.");
        assertEquals("", overrides.getAsJsonObject("unrelated-mod")
                .getAsJsonObject("-depends").get("minecraft").getAsString(),
                "Rules for mods outside the compatibility target must survive.");
        assertEquals(3, result.modIdsOverridden());
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
