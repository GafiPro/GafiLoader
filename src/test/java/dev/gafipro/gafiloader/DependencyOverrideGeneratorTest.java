package dev.gafipro.gafiloader;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyOverrideGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesPerModMinecraftVersionOverridesWithoutChangingModJar() throws IOException {
        Path mod = temporaryDirectory.resolve("legacy-example.jar");
        Path config = temporaryDirectory.resolve("fabric_loader_dependencies.json");
        writeModJar(mod, "{\"schemaVersion\":1,\"id\":\"legacy-example\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"~1.21.11\"}}");
        byte[] originalBytes = Files.readAllBytes(mod);

        DependencyOverrideGenerator.OverrideReport report = new DependencyOverrideGenerator()
                .generate(List.of(mod), config);

        assertEquals(List.of("legacy-example"), report.newlyConfiguredModIds());
        assertTrue(java.util.Arrays.equals(originalBytes, Files.readAllBytes(mod)));

        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt());
        JsonObject override = root.getAsJsonObject("overrides")
                .getAsJsonObject("legacy-example");
        assertEquals("", override.getAsJsonObject("-depends").get("minecraft").getAsString());

        DependencyOverrideGenerator.OverrideReport second = new DependencyOverrideGenerator()
                .generate(List.of(mod), config);
        assertTrue(second.newlyConfiguredModIds().isEmpty());
        assertEquals(List.of("legacy-example"), second.alreadyConfiguredModIds());
    }

    @Test
    void mergesOtherModOverridesButDoesNotSilentlyRewriteConflictingRules() throws IOException {
        Path mod = temporaryDirectory.resolve("legacy-existing.jar");
        Path config = temporaryDirectory.resolve("fabric_loader_dependencies.json");
        writeModJar(mod, "{\"schemaVersion\":1,\"id\":\"legacy-existing\",\"version\":\"1.0.0\"}");
        Files.writeString(config, """
                {
                  "version": 1,
                  "overrides": {
                    "another-mod": {
                      "-depends": { "minecraft": "" }
                    }
                  }
                }
                """);

        new DependencyOverrideGenerator().generate(List.of(mod), config);
        JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("overrides").has("another-mod"));
        assertTrue(root.getAsJsonObject("overrides").has("legacy-existing"));

        Path conflictingConfig = temporaryDirectory.resolve("conflicting.json");
        Files.writeString(conflictingConfig, """
                {
                  "version": 1,
                  "overrides": {
                    "legacy-existing": {
                      "depends": { "fabricloader": ">=0.14.0" }
                    }
                  }
                }
                """);
        String before = Files.readString(conflictingConfig);
        assertThrows(IOException.class, () -> new DependencyOverrideGenerator()
                .generate(List.of(mod), conflictingConfig));
        assertEquals(before, Files.readString(conflictingConfig));
    }

    @Test
    void requiresFabricMetadataAndUniqueModIds() throws IOException {
        Path withoutMetadata = temporaryDirectory.resolve("not-a-fabric-mod.jar");
        writeModJar(withoutMetadata, null);
        assertThrows(IOException.class, () -> new DependencyOverrideGenerator()
                .generate(List.of(withoutMetadata), temporaryDirectory.resolve("out.json")));

        Path invalidId = temporaryDirectory.resolve("invalid-id.jar");
        writeModJar(invalidId, "{\"schemaVersion\":1,\"id\":\"Legacy-Invalid\",\"version\":\"1\"}");
        assertThrows(IOException.class, () -> new DependencyOverrideGenerator()
                .generate(List.of(invalidId), temporaryDirectory.resolve("invalid-id.json")));

        Path first = temporaryDirectory.resolve("first.jar");
        Path second = temporaryDirectory.resolve("second.jar");
        String metadata = "{\"schemaVersion\":1,\"id\":\"duplicate-id\",\"version\":\"1\"}";
        writeModJar(first, metadata);
        writeModJar(second, metadata);
        assertThrows(IOException.class, () -> new DependencyOverrideGenerator()
                .generate(List.of(first, second), temporaryDirectory.resolve("duplicate.json")));
    }

    private static void writeModJar(Path jarPath, String metadata) throws IOException {
        try (OutputStream output = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(output)) {
            if (metadata != null) {
                jar.putNextEntry(new JarEntry("fabric.mod.json"));
                jar.write(metadata.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("data/dummy.txt"));
            jar.write("fixture".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
