package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                  "inheritsFrom": "26.2",
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
        byte[] jarBytes = createBootstrapJar("0.1.586");

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

        byte[] updatedBootstrapBytes = createBootstrapJar("0.1.587");
        installer.install(minecraft, new ByteArrayInputStream(updatedBootstrapBytes), "0.1.587");
        Path updatedBootstrapPath = minecraft.resolve(
                "libraries/dev/gafipro/gafiloader-bootstrap/0.1.587/gafiloader-bootstrap-0.1.587.jar");
        assertTrue(java.util.Arrays.equals(updatedBootstrapBytes, Files.readAllBytes(updatedBootstrapPath)));
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
                    "latest-release": {"name": "Latest Release", "type": "latest-release", "lastVersionId": "26.2"},
                    "fabric-source": {
                      "name": "Fabric 26.2", "type": "custom",
                      "lastVersionId": "fabric-loader-0.20.1-26.2",
                      "gameDir": "D:/Gafi/Instance",
                      "javaArgs": "-Xmx4G",
                      "javaDir": "C:/Java/Custom",
                      "resolution": {"width": 1920, "height": 1080}
                    }
                  },
                  "settings": {"locale": "en-us"},
                  "unknown-root-setting": 27
                }
                """;
        Files.writeString(launcherProfiles, launcherOriginal, StandardCharsets.UTF_8);
        byte[] bootstrap = createBootstrapJar("0.1.604");

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
        assertEquals("D:/Gafi/Instance", gafiEntry.get("gameDir").getAsString());
        assertEquals("-Xmx4G", gafiEntry.get("javaArgs").getAsString());
        assertEquals("C:/Java/Custom", gafiEntry.get("javaDir").getAsString());
        assertEquals(1920, gafiEntry.getAsJsonObject("resolution").get("width").getAsInt());
        assertEquals("en-us", launcherRoot.getAsJsonObject("settings").get("locale").getAsString());
        assertEquals(27, launcherRoot.get("unknown-root-setting").getAsInt());

        // Simulate a prior installer that registered GafiLoader but omitted custom profile settings.
        gafiEntry.remove("gameDir");
        gafiEntry.addProperty("javaArgs", "-Xmx8G");
        Files.writeString(launcherProfiles, launcherRoot.toString(), StandardCharsets.UTF_8);
        new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(createBootstrapJar("0.1.605")), "0.1.605");
        JsonObject repairedRoot = JsonParser.parseString(Files.readString(launcherProfiles)).getAsJsonObject();
        JsonObject repairedEntry = repairedRoot.getAsJsonObject("profiles").getAsJsonObject("gafiloader-26.2");
        assertEquals("D:/Gafi/Instance", repairedEntry.get("gameDir").getAsString(),
                "A rerun should fill missing settings from the source installation.");
        assertEquals("-Xmx8G", repairedEntry.get("javaArgs").getAsString(),
                "A rerun must preserve settings already customized on the GafiLoader profile.");

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
                minecraft, new ByteArrayInputStream(createBootstrapJar("0.1.604")), "0.1.604");

        assertTrue(result.installed(), result.message());
        JsonObject launcherRoot = JsonParser.parseString(Files.readString(launcherProfiles)).getAsJsonObject();
        assertEquals("gafiloader-26.2", launcherRoot.getAsJsonObject("profiles")
                .getAsJsonObject("gafiloader-26.2").get("lastVersionId").getAsString());
    }

    @Test
    void refusesAnInvalidBootstrapJarBeforePatchingTheProfile() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("invalid-bootstrap-minecraft"));
        Path profileDirectory = Files.createDirectories(minecraft.resolve("versions/gafiloader-26.2"));
        Path profile = profileDirectory.resolve("gafiloader-26.2.json");
        String original = """
                {
                  "id": "gafiloader-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [{"name": "net.fabricmc:fabric-loader:0.20.1"}]
                }
                """;
        Files.writeString(profile, original, StandardCharsets.UTF_8);
        Path launcherProfiles = minecraft.resolve("launcher_profiles.json");
        String launcherOriginal = """
                {"profiles": {}, "settings": {"locale":"en-us"}}
                """;
        Files.writeString(launcherProfiles, launcherOriginal, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream("not a jar".getBytes(StandardCharsets.UTF_8)), "0.1.588"));

        assertEquals(original, Files.readString(profile, StandardCharsets.UTF_8));
        assertEquals(launcherOriginal, Files.readString(launcherProfiles, StandardCharsets.UTF_8));
        assertFalse(Files.exists(profile.resolveSibling("gafiloader-26.2.json.gafiloader-backup")));
        assertFalse(Files.exists(minecraft.resolve(
                "libraries/dev/gafipro/gafiloader-bootstrap/0.1.588/gafiloader-bootstrap-0.1.588.jar")));
    }

    @Test
    void refusesBootstrapJarWithInvalidClassBytesBeforeChangingLauncherProfiles() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("invalid-class-minecraft"));
        Path profileDirectory = Files.createDirectories(minecraft.resolve("versions/gafiloader-26.2"));
        Path profile = profileDirectory.resolve("gafiloader-26.2.json");
        String original = """
                {
                  "id": "gafiloader-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [{"name": "net.fabricmc:fabric-loader:0.20.1"}]
                }
                """;
        Files.writeString(profile, original, StandardCharsets.UTF_8);
        Path launcherProfiles = minecraft.resolve("launcher_profiles.json");
        String launcherOriginal = "{\\"profiles\\":{},\\"settings\\":{}}";
        Files.writeString(launcherProfiles, launcherOriginal, StandardCharsets.UTF_8);

        byte[] invalidClassJar = createBootstrapJar(
                "0.1.589", "dev/gafipro/gafiloader/LaunchBootstrap.class");
        IOException failure = assertThrows(IOException.class, () -> new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(invalidClassJar), "0.1.589"));
        assertTrue(failure.getMessage().contains("valid Java class file"));

        assertEquals(original, Files.readString(profile, StandardCharsets.UTF_8));
        assertEquals(launcherOriginal, Files.readString(launcherProfiles, StandardCharsets.UTF_8));
        assertFalse(Files.exists(profile.resolveSibling("gafiloader-26.2.json.gafiloader-backup")));
        assertFalse(Files.exists(minecraft.resolve(
                "libraries/dev/gafipro/gafiloader-bootstrap/0.1.589/gafiloader-bootstrap-0.1.589.jar")));
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
        Files.writeString(minecraft.resolve("launcher_profiles.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);

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
    @Test
    void removesOnlyUnreferencedOldBootstrapFoldersAndKeepsReferencedOrUnrelatedFiles() throws Exception {
        Path minecraft = Files.createDirectory(temporaryDirectory.resolve("minecraft-cleanup"));
        Path versionsRoot = Files.createDirectories(minecraft.resolve("versions"));
        Path sourceDirectory = Files.createDirectories(versionsRoot.resolve("fabric-loader-0.20.1-26.2"));
        Files.writeString(sourceDirectory.resolve("fabric-loader-0.20.1-26.2.json"), """
                {
                  "id": "fabric-loader-0.20.1-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [{"name": "net.fabricmc:fabric-loader:0.20.1"}]
                }
                """, StandardCharsets.UTF_8);

        Path gafiDirectory = Files.createDirectories(versionsRoot.resolve("gafiloader-26.2"));
        Path gafiProfile = gafiDirectory.resolve("gafiloader-26.2.json");
        Files.writeString(gafiProfile, """
                {
                  "id": "gafiloader-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [
                    {"name": "net.fabricmc:fabric-loader:0.20.1"},
                    {"name": "dev.gafipro:gafiloader-bootstrap:0.1.590"}
                  ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(gafiProfile.resolveSibling("gafiloader-26.2.json.gafiloader-backup"), """
                {
                  "id": "gafiloader-26.2",
                  "inheritsFrom": "26.2",
                  "libraries": [{"name": "dev.gafipro:gafiloader-bootstrap:0.1.590"}]
                }
                """, StandardCharsets.UTF_8);

        Path launcherProfiles = minecraft.resolve("launcher_profiles.json");
        Files.writeString(launcherProfiles, """
                {"profiles":{"fabric-source":{"lastVersionId":"fabric-loader-0.20.1-26.2"}},
                 "settings":{"locale":"en-us"}}
                """, StandardCharsets.UTF_8);

        Path bootstrapRoot = Files.createDirectories(
                minecraft.resolve("libraries/dev/gafipro/gafiloader-bootstrap"));
        Path referencedOld = Files.createDirectories(bootstrapRoot.resolve("0.1.590"));
        Files.write(referencedOld.resolve("gafiloader-bootstrap-0.1.590.jar"), new byte[] {1, 2, 3});
        Path orphanedOld = Files.createDirectories(bootstrapRoot.resolve("0.1.591"));
        Files.write(orphanedOld.resolve("gafiloader-bootstrap-0.1.591.jar"), new byte[] {4, 5, 6});
        Path unrelated = Files.createDirectories(minecraft.resolve("libraries/dev/gafipro/other-library/0.1.591"));
        Files.write(unrelated.resolve("keep.txt"), "keep".getBytes(StandardCharsets.UTF_8));

        ProfileBootstrapInstaller.Result result = new ProfileBootstrapInstaller().install(
                minecraft, new ByteArrayInputStream(createBootstrapJar("0.1.617")), "0.1.617");

        assertTrue(result.installed(), result.message());
        assertFalse(Files.exists(orphanedOld), "Unreferenced stale bootstrap directories should be removed.");
        assertTrue(Files.isRegularFile(referencedOld.resolve("gafiloader-bootstrap-0.1.590.jar")),
                "An old library still referenced by a profile backup must be kept.");
        assertTrue(Files.isRegularFile(unrelated.resolve("keep.txt")),
                "The cleanup must not touch libraries outside GafiLoader's own artifact directory.");
        assertTrue(Files.isRegularFile(bootstrapRoot.resolve("0.1.617/gafiloader-bootstrap-0.1.617.jar")));
        assertTrue(result.message().contains("Removed 1 unreferenced old GafiLoader bootstrap folder(s)."));
    }

    private static byte[] createBootstrapJar(String version) throws IOException {
        return createBootstrapJar(version, null);
    }

    private static byte[] createBootstrapJar(String version, String invalidClassEntry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Main-Class", ProfileBootstrapInstaller.BOOTSTRAP_MAIN_CLASS);
        attributes.putValue("Implementation-Version", version);

        String[] entries = {
                "dev/gafipro/gafiloader/LaunchBootstrap.class",
                "dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator.class",
                "dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator$Result.class",
                "dev/gafipro/gafiloader/LegacyMinecraftVersionDetector.class",
                "com/google/gson/Gson.class",
                "com/google/gson/GsonBuilder.class",
                "com/google/gson/JsonParser.class",
                "com/google/gson/JsonObject.class",
                "com/google/gson/JsonArray.class",
                "com/google/gson/JsonElement.class",
                "com/google/gson/JsonPrimitive.class"
        };
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            for (String name : entries) {
                jar.putNextEntry(new JarEntry(name));
                if (name.equals(invalidClassEntry)) {
                    jar.write(new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
                } else {
                    try (var classBytes = ProfileBootstrapInstallerTest.class.getClassLoader()
                            .getResourceAsStream(name)) {
                        if (classBytes == null) {
                            throw new IOException("Test classpath is missing " + name);
                        }
                        jar.write(classBytes.readAllBytes());
                    }
                }
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }


}
