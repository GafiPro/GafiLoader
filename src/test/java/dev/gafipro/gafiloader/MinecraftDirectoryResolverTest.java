package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftDirectoryResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void explicitOverrideWinsEvenWhenTheDirectoryDoesNotExistYet() {
        Path override = temporaryDirectory.resolve("custom-minecraft");

        MinecraftDirectoryResolver.Resolution result = MinecraftDirectoryResolver.resolve(
                override.toString(), temporaryDirectory.resolve("appdata").toString(),
                temporaryDirectory.resolve("home").toString());

        assertEquals(override.toAbsolutePath().normalize(), result.path());
        assertTrue(result.source().contains("explicit override"));
    }

    @Test
    void prefersAppDataWhenItContainsARecognizedLauncherProfileFile() throws Exception {
        Path appData = Files.createDirectories(temporaryDirectory.resolve("roaming"));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path preferred = Files.createDirectories(appData.resolve(".minecraft"));
        Files.writeString(preferred.resolve("launcher_profiles.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);
        Path other = Files.createDirectories(home.resolve("AppData/Roaming/.minecraft"));
        Files.writeString(other.resolve("launcher_profiles.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);

        MinecraftDirectoryResolver.Resolution result = MinecraftDirectoryResolver.resolve(
                null, appData.toString(), home.toString());

        assertEquals(preferred.toAbsolutePath().normalize(), result.path());
    }

    @Test
    void fallsBackToTheOnlyRecognizedLauncherRootWhenAppDataRootIsEmpty() throws Exception {
        Path appData = Files.createDirectories(temporaryDirectory.resolve("empty-roaming"));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path fallback = Files.createDirectories(home.resolve("AppData/Roaming/.minecraft"));
        Files.writeString(fallback.resolve("launcher_profiles_microsoft_store.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);

        MinecraftDirectoryResolver.Resolution result = MinecraftDirectoryResolver.resolve(
                null, appData.toString(), home.toString());

        assertEquals(fallback.toAbsolutePath().normalize(), result.path());
        assertTrue(result.source().contains("fallback"));
    }

    @Test
    void choosesMicrosoftStorePackageRootWhenItContainsFabricAndRoamingRootIsStale() throws Exception {
        Path appData = Files.createDirectories(temporaryDirectory.resolve("roaming"));
        Path localAppData = Files.createDirectories(temporaryDirectory.resolve("local"));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));

        Path staleRoamingRoot = Files.createDirectories(appData.resolve(".minecraft"));
        Files.writeString(staleRoamingRoot.resolve("launcher_profiles.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);

        Path storeRoot = microsoftStoreMinecraftDirectory(localAppData);
        Files.createDirectories(storeRoot);
        Files.writeString(storeRoot.resolve("launcher_profiles_microsoft_store.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);
        writeFabric262Profile(storeRoot);

        MinecraftDirectoryResolver.Resolution result = MinecraftDirectoryResolver.resolve(
                null, appData.toString(), localAppData.toString(), home.toString());

        assertEquals(storeRoot.toAbsolutePath().normalize(), result.path());
        assertTrue(result.source().contains("Microsoft Store"));
    }

    @Test
    void prefersMicrosoftStorePackageRootWhenBothLauncherRootsContainFabric() throws Exception {
        Path appData = Files.createDirectories(temporaryDirectory.resolve("roaming-both"));
        Path localAppData = Files.createDirectories(temporaryDirectory.resolve("local-both"));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home-both"));

        Path roamingRoot = Files.createDirectories(appData.resolve(".minecraft"));
        Files.writeString(roamingRoot.resolve("launcher_profiles.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);
        writeFabric262Profile(roamingRoot);

        Path storeRoot = microsoftStoreMinecraftDirectory(localAppData);
        Files.createDirectories(storeRoot);
        Files.writeString(storeRoot.resolve("launcher_profiles_microsoft_store.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);
        writeFabric262Profile(storeRoot);

        MinecraftDirectoryResolver.Resolution result = MinecraftDirectoryResolver.resolve(
                null, appData.toString(), localAppData.toString(), home.toString());

        assertEquals(storeRoot.toAbsolutePath().normalize(), result.path());
        assertTrue(result.source().contains("Microsoft Store"));
    }

    private static Path microsoftStoreMinecraftDirectory(Path localAppData) {
        return localAppData.resolve("Packages")
                .resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                .resolve("LocalCache").resolve("Local").resolve("minecraft");
    }

    private static void writeFabric262Profile(Path minecraftRoot) throws Exception {
        String profileId = "fabric-loader-0.20.1-26.2";
        Path versionDirectory = Files.createDirectories(
                minecraftRoot.resolve("versions").resolve(profileId));
        String json = """
                {
                  "id": "fabric-loader-0.20.1-26.2",
                  "inheritsFrom": "26.2",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "libraries": [
                    {"name": "net.fabricmc:fabric-loader:0.20.1"}
                  ]
                }
                """;
        Files.writeString(versionDirectory.resolve(profileId + ".json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void ignoresMalformedLauncherProfileFilesWhenChoosingFallback() throws Exception {
        Path appData = Files.createDirectories(temporaryDirectory.resolve("bad-roaming"));
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path malformed = Files.createDirectories(appData.resolve(".minecraft"));
        Files.writeString(malformed.resolve("launcher_profiles.json"), "{bad json", StandardCharsets.UTF_8);
        Path valid = Files.createDirectories(home.resolve("AppData/Roaming/.minecraft"));
        Files.writeString(valid.resolve("launcher_profiles.json"),
                "{\"profiles\":{},\"settings\":{}}", StandardCharsets.UTF_8);

        MinecraftDirectoryResolver.Resolution result = MinecraftDirectoryResolver.resolve(
                null, appData.toString(), home.toString());

        assertEquals(valid.toAbsolutePath().normalize(), result.path());
    }
}
