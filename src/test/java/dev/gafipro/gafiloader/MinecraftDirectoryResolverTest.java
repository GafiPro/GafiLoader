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
