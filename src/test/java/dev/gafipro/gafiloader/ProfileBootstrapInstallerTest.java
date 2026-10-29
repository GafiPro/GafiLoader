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
        Path launcherProfiles = minecraft.resolve("launcher_profiles.json");
        String launcherOriginal = """
                {
                  "profiles": {
                    "latest-release": {
                      "name": "Latest Release",
                      "type": "latest-release",
                      "lastVersionId": "26.2"
                    }
                  },
                  "settings": { "locale": "en-us" },
                  "preserve-me": true
                }
                """;
        Files.writeString(launcherProfiles, launcherOriginal, StandardCharsets.UTF_8);
        byte[] jarBytes = "test-bootstrap-jar".getBytes(StandardCharsets.UTF_8);

        ProfileBootstrapInstaller installer = new ProfileBootstrapInstaller();
        ProfileBootstrapInstaller.Result result = installer.install(
                minecraft, new ByteArrayInputStream(jarBytes), "0.1.586");

        assertTrue(result.installed(), result.message());
        assertTrue(Files.isRegularFile(result.bootstrapJar()));
        assertTrue(java.util.Arrays.equals(jarBytes, Files.readAllBytes(result.bootstrapJar())));
        assertEquals(original, Files.readString(profile.resolveSibling(
                "gafiloader-26.2.json.gafiloader-backup"), StandardCharsets.UTF_8));

        JsonObject launcherRoot = JsonParser.parseString(Files.readString(launcherProfiles)).getAsJsonObject();
        JsonObject launcherEntry = launcherRoot.getAsJsonObject("profiles").getAsJsonObject("gafiloader-26.2");
        assertEquals("GafiLoader 26.2", launcherEntry.get("name").getAsString());
        assertEquals("custom", launcherEntry.get("type").getAsString());
        assertEquals("gafiloader-26.2", launcherEntry.get("lastVersionId").getAsString());
        assertEquals("en-us", launcherRoot.getAsJsonObject("settings").get("locale").getAsString());
        assertTrue(launcherRoot.get("preserve-me").getAsBoolean());
        assertTrue(launcherRoot.getAsJsonObject("profiles").has("latest-release"));

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
    void createsAndRegistersDedicatedInstallationFromInstalledFabric262Profile() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft-new-install"));
        Path sourceDirectory = Files.createDirectories(
                minecraft.resolve("versions/fabric-loader-0.20.1-26.2"));
        Path sourceJson = sourceDirectory.resolve("fabric-loader-0.20.1-26.2.json");
        String sourceOriginal = """
                {
                  "id": "fabric-loader-0.20.1-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [
                    {"name": "net.fabricmc:fabric-loader:0.20.1", "url": "https://maven.fabricmc.net/"},
                    {"name": "org.ow2.asm:asm:9.9.1"}
                  ],
                  "arguments": {"game": ["--gameDir", "."]}
                }
                """;
        Files.writeString(sourceJson, sourceOriginal, StandardCharsets.UTF_8);

        Path vanillaDirectory = Files.createDirectories(minecraft.resolve("versions/26.2"));
        Path vanillaJson = vanillaDirectory.resolve("26.2.json");
        String vanillaOriginal = "{\"id\":\"26.2\",\"type\":\"release\"}";
        Files.writeString(vanillaJson, vanillaOriginal, StandardCharsets.UTF_8);

        Path launcherProfiles = minecraft.resolve("launcher_profiles.json");
        String launcherOriginal = """
                {
                  "profiles": {
                    "latest-release": {"name": "Latest Release", "type": "latest-release", "lastVersionId": "26.2"}
                  },
                  "settings": {"locale": "en-us"},
                  "unknown-root-setting": 27
                }
                """;
        Files.writeString(launcherProfiles, launcherOriginal, StandardCharsets.UTF_8);
        byte[] bootstrap = "new-profile-bootstrap".getBytes(StandardCharsets.UTF_8);

        ProfileBootstrapInstaller.Result result = new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(bootstrap), "0.1.604");

        assertTrue(result.installed(), result.message());
        Path createdVersion = minecraft.resolve("versions/gafiloader-26.2/gafiloader-26.2.json");
        assertTrue(Files.isRegularFile(createdVersion));
        JsonObject versionRoot = JsonParser.parseString(Files.readString(createdVersion)).getAsJsonObject();
        assertEquals("gafiloader-26.2", versionRoot.get("id").getAsString());
        assertEquals("26.2", versionRoot.get("inheritsFrom").getAsString());
        assertEquals(ProfileBootstrapInstaller.BOOTSTRAP_MAIN_CLASS, versionRoot.get("mainClass").getAsString());
        assertTrue(versionRoot.getAsJsonArray("libraries").toString()
                .contains("net.fabricmc:fabric-loader:0.20.1"));
        assertTrue(versionRoot.getAsJsonArray("libraries").toString()
                .contains("dev.gafipro:gafiloader-bootstrap:0.1.604"));

        JsonObject launcherRoot = JsonParser.parseString(Files.readString(launcherProfiles)).getAsJsonObject();
        JsonObject entries = launcherRoot.getAsJsonObject("profiles");
        assertTrue(entries.has("latest-release"));
        JsonObject gafiEntry = entries.getAsJsonObject("gafiloader-26.2");
        assertEquals("GafiLoader 26.2", gafiEntry.get("name").getAsString());
        assertEquals("gafiloader-26.2", gafiEntry.get("lastVersionId").getAsString());
        assertEquals("en-us", launcherRoot.getAsJsonObject("settings").get("locale").getAsString());
        assertEquals(27, launcherRoot.get("unknown-root-setting").getAsInt());

        assertEquals(sourceOriginal, Files.readString(sourceJson, StandardCharsets.UTF_8),
                "The installed Fabric source profile must remain byte-for-byte unchanged.");
        assertEquals(vanillaOriginal, Files.readString(vanillaJson, StandardCharsets.UTF_8),
                "The vanilla 26.2 profile must remain unchanged.");
        assertTrue(java.util.Arrays.equals(bootstrap, Files.readAllBytes(result.bootstrapJar())));
    }

    @Test
    void registersInstallationInMicrosoftStoreLauncherProfileFileWhenThatIsTheOnlyOne() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft-store"));
        Path sourceDirectory = Files.createDirectories(
                minecraft.resolve("versions/fabric-loader-0.20.1-26.2"));
        Files.writeString(sourceDirectory.resolve("fabric-loader-0.20.1-26.2.json"), """
                {
                  "id": "fabric-loader-0.20.1-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [{"name": "net.fabricmc:fabric-loader:0.20.1"}]
                }
                """, StandardCharsets.UTF_8);
        Path launcherProfiles = minecraft.resolve("launcher_profiles_microsoft_store.json");
        Files.writeString(launcherProfiles, "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);

        ProfileBootstrapInstaller.Result result = new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(new byte[] {9, 8, 7}), "0.1.604");

        assertTrue(result.installed(), result.message());
        JsonObject launcherRoot = JsonParser.parseString(Files.readString(launcherProfiles)).getAsJsonObject();
        assertEquals("gafiloader-26.2", launcherRoot.getAsJsonObject("profiles")
                .getAsJsonObject("gafiloader-26.2").get("lastVersionId").getAsString());
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
    void reportsWhenNoFabric262SourceProfileExistsAndDoesNotCreateAnOrphan() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft-no-fabric"));
        Files.writeString(minecraft.resolve("launcher_profiles.json"), "{\"profiles\":{},\"settings\":{}}",
                StandardCharsets.UTF_8);
        ProfileBootstrapInstaller.Result result = new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(new byte[] {1}), "0.1.586");

        assertFalse(result.installed());
        assertTrue(result.message().contains("no installed Fabric profile"));
        assertFalse(Files.exists(minecraft.resolve("versions/gafiloader-26.2/gafiloader-26.2.json")));
        assertFalse(Files.exists(minecraft.resolve("libraries")));
        JsonObject launcherRoot = JsonParser.parseString(Files.readString(minecraft.resolve("launcher_profiles.json")))
                .getAsJsonObject();
        assertFalse(launcherRoot.getAsJsonObject("profiles").has("gafiloader-26.2"));
    }
}
