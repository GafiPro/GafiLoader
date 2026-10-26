package dev.gafipro.gafiloader;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDependencyOverrideTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preparesLegacyDependencyOverridesWithoutModifyingOriginalModJar() throws IOException {
        Path gameDir = Files.createDirectory(temporaryDirectory.resolve("game directory"));
        Path modsDir = Files.createDirectory(gameDir.resolve("mods"));
        Path configDir = Files.createDirectory(gameDir.resolve("config"));
        Path modJar = modsDir.resolve("legacy-test.jar");
        byte[] originalModBytes = createLegacyModJar();
        Files.write(modJar, originalModBytes);

        Path agentConfig = temporaryDirectory.resolve("gafi-loader-agent.properties");
        Properties properties = new Properties();
        properties.setProperty("gameDir", gameDir.toString());
        try (var writer = Files.newBufferedWriter(agentConfig)) {
            properties.store(writer, "test");
        }

        GameSideCompatibilityAgent.prepareLegacyDependencyOverrides(agentConfig);

        Path dependencyConfig = configDir.resolve("fabric_loader_dependencies.json");
        assertTrue(Files.isRegularFile(dependencyConfig), "Expected the override file to be created");
        var root = JsonParser.parseString(Files.readString(dependencyConfig)).getAsJsonObject();
        assertEquals("", root.getAsJsonObject("overrides")
                .getAsJsonObject("legacy-test")
                .getAsJsonObject("-depends")
                .get("minecraft").getAsString());
        assertArrayEquals(originalModBytes, Files.readAllBytes(modJar),
                "Runtime dependency preparation must never rewrite the mod JAR");
        assertFalse(Files.exists(dependencyConfig.resolveSibling("fabric_loader_dependencies.json.bak")),
                "A new config does not need a backup");
    }

    @Test
    void doesNothingWhenTheConfiguredModsDirectoryDoesNotExist() throws IOException {
        Path gameDir = temporaryDirectory.resolve("empty-game");
        Files.createDirectories(gameDir);
        Path agentConfig = temporaryDirectory.resolve("empty-agent.properties");
        Properties properties = new Properties();
        properties.setProperty("gameDir", gameDir.toString());
        try (var writer = Files.newBufferedWriter(agentConfig)) {
            properties.store(writer, "test");
        }

        GameSideCompatibilityAgent.prepareLegacyDependencyOverrides(agentConfig);

        assertFalse(Files.exists(gameDir.resolve("config/fabric_loader_dependencies.json")));
    }

    private static byte[] createLegacyModJar() throws IOException {
        Path temporaryJar = Files.createTempFile("gafi-legacy-fixture-", ".jar");
        try {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temporaryJar))) {
                jar.putNextEntry(new JarEntry("fabric.mod.json"));
                jar.write("""
                        {
                          "schemaVersion": 1,
                          "id": "legacy-test",
                          "version": "1.0.0",
                          "depends": {
                            "minecraft": "1.21.11",
                            "fabricloader": ">=0.16.0"
                          }
                        }
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            return Files.readAllBytes(temporaryJar);
        } finally {
            Files.deleteIfExists(temporaryJar);
        }
    }
}
