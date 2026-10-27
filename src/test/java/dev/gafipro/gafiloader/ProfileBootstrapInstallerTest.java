package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileBootstrapInstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsBootstrapIntoOnlyTheGafiLoaderProfileAndPreservesBackup() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path profileDirectory = Files.createDirectories(
                minecraft.resolve("versions/gafiloader-26.2"));
        Path profile = profileDirectory.resolve("gafiloader-26.2.json");
        String original = """
                {
                  "id": "gafiloader-26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [
                    {"name": "net.fabricmc:fabric-loader:0.20.1", "url": "https://maven.fabricmc.net/"}
                  ],
                  "arguments": {"game": ["--gameDir", "example"]}
                }
                """;
        Files.writeString(profile, original, StandardCharsets.UTF_8);
        byte[] jarBytes = "test-bootstrap-jar".getBytes(StandardCharsets.UTF_8);

        ProfileBootstrapInstaller installer = new ProfileBootstrapInstaller();
        ProfileBootstrapInstaller.Result result = installer.install(
                minecraft, new ByteArrayInputStream(jarBytes), "0.1.586");

        assertTrue(result.installed(), result.message());
        assertTrue(Files.isRegularFile(result.bootstrapJar()));
        assertTrue(java.util.Arrays.equals(jarBytes, Files.readAllBytes(result.bootstrapJar())));
        assertEquals(original, Files.readString(profile.resolveSibling(
                "gafiloader-26.2.json.gafiloader-backup"), StandardCharsets.UTF_8));

        JsonObject root = JsonParser.parseString(Files.readString(profile)).getAsJsonObject();
        assertEquals(ProfileBootstrapInstaller.BOOTSTRAP_MAIN_CLASS, root.get("mainClass").getAsString());
        JsonArray libraries = root.getAsJsonArray("libraries");
        assertEquals(2, libraries.size());
        assertEquals("net.fabricmc:fabric-loader:0.20.1",
                libraries.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("dev.gafipro:gafiloader-bootstrap:0.1.586",
                libraries.get(1).getAsJsonObject().get("name").getAsString());

        installer.install(minecraft, new ByteArrayInputStream(jarBytes), "0.1.587");
        JsonObject updated = JsonParser.parseString(Files.readString(profile)).getAsJsonObject();
        JsonArray updatedLibraries = updated.getAsJsonArray("libraries");
        long bootstrapCount = 0;
        for (var library : updatedLibraries) {
            String name = library.getAsJsonObject().get("name").getAsString();
            if (name.startsWith("dev.gafipro:gafiloader-bootstrap:")) bootstrapCount++;
        }
        assertEquals(1, bootstrapCount, "Updates must replace the old coordinate instead of accumulating libraries.");
        assertTrue(updatedLibraries.toString().contains("dev.gafipro:gafiloader-bootstrap:0.1.587"));
        assertEquals(original, Files.readString(profile.resolveSibling(
                "gafiloader-26.2.json.gafiloader-backup"), StandardCharsets.UTF_8),
                "The backup must continue to represent the unmodified profile.");
    }

    @Test
    void refusesToPatchProfilesWithAnUnexpectedEntrypoint() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        Path profileDirectory = Files.createDirectories(
                minecraft.resolve("versions/gafiloader-26.2"));
        Path profile = profileDirectory.resolve("gafiloader-26.2.json");
        String original = """
                {"id":"gafiloader-26.2","mainClass":"example.CustomEntrypoint","libraries":[]}
                """;
        Files.writeString(profile, original, StandardCharsets.UTF_8);

        ProfileBootstrapInstaller.Result result = new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(new byte[] {1, 2, 3}), "0.1.586");

        assertFalse(result.installed());
        assertTrue(result.message().contains("left it unchanged"));
        assertEquals(original, Files.readString(profile, StandardCharsets.UTF_8));
        assertFalse(Files.exists(minecraft.resolve("libraries")));
    }

    @Test
    void reportsWhenTheExpectedProfileDoesNotExist() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft"));
        ProfileBootstrapInstaller.Result result = new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(new byte[] {1}), "0.1.586");

        assertFalse(result.installed());
        assertTrue(result.message().contains("gafiloader-26.2"));
    }
}
