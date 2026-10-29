package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchBootstrapTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresThePreLaunchConfigWhenTheGeneratedFileIsUnchanged() throws IOException {
        Path config = temporaryDirectory.resolve("config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        byte[] original = "{\"version\":1,\"overrides\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] generated = "{\"version\":1,\"overrides\":{\"legacy-mod\":{\"-depends\":{\"minecraft\":\"\"}}}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, generated);

        LaunchBootstrap.restoreDependencyConfig(config, original, generated);

        assertArrayEquals(original, Files.readAllBytes(config),
                "Restoration must preserve the original JSON bytes, not merely its parsed meaning.");
    }

    @Test
    void removesAConfigCreatedOnlyForTheGafiLoaderLaunch() throws IOException {
        Path config = temporaryDirectory.resolve("config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        byte[] generated = "{\"version\":1,\"overrides\":{}}".getBytes(StandardCharsets.UTF_8);
        Files.write(config, generated);

        LaunchBootstrap.restoreDependencyConfig(config, null, generated);

        assertFalse(Files.exists(config));
    }

    @Test
    void doesNotOverwriteAConfigChangedOutsideGafiLoader() throws IOException {
        Path config = temporaryDirectory.resolve("config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        byte[] original = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] generated = "{\"version\":1,\"overrides\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] externalChange = "{\"version\":1,\"overrides\":{\"my-rule\":{}}}".getBytes(StandardCharsets.UTF_8);
        Files.write(config, externalChange);

        LaunchBootstrap.restoreDependencyConfig(config, original, generated);

        assertArrayEquals(externalChange, Files.readAllBytes(config),
                "Do not overwrite configuration if something else modified it during the game session.");
    }

    @Test
    void resolvesTheActualGameDirectoryArgument() {
        Path gameDirectory = temporaryDirectory.resolve("custom game directory");
        Path resolved = LaunchBootstrap.resolveGameDirectory(new String[] {
                "--username", "test", "--gameDir", gameDirectory.toString(), "--version", "gafiloader-26.2"
        });
        assertEquals(gameDirectory.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolvesFabricCustomModsFolderProperty() {
        String previous = System.getProperty("fabric.modsFolder");
        Path gameDirectory = temporaryDirectory.resolve("instance");
        Path customMods = temporaryDirectory.resolve("shared-mods");
        try {
            System.setProperty("fabric.modsFolder", customMods.toString());
            assertEquals(customMods.toAbsolutePath().normalize(),
                    LaunchBootstrap.resolveModsDirectory(gameDirectory));
        } finally {
            if (previous == null) System.clearProperty("fabric.modsFolder");
            else System.setProperty("fabric.modsFolder", previous);
        }
    }

    @Test
    void defaultsModsFolderToActualGameDirectory() {
        String previous = System.getProperty("fabric.modsFolder");
        Path gameDirectory = temporaryDirectory.resolve("instance");
        try {
            System.clearProperty("fabric.modsFolder");
            assertEquals(gameDirectory.resolve("mods").toAbsolutePath().normalize(),
                    LaunchBootstrap.resolveModsDirectory(gameDirectory));
        } finally {
            if (previous == null) System.clearProperty("fabric.modsFolder");
            else System.setProperty("fabric.modsFolder", previous);
        }
    }

    @Test
    void validatesTheOverrideFileFabricWillRead() throws Exception {
        Path config = temporaryDirectory.resolve("config/fabric_loader_dependencies.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {"version":1,"overrides":{"fabric-api":{"-depends":{"minecraft":""}}}}
                """, StandardCharsets.UTF_8);
        LaunchBootstrap.verifyGeneratedOverrides(config, List.of("fabric-api"));
    }

    @Test
    void rejectsAnOverrideFileMissingAnExpectedLegacyMod() throws Exception {
        Path config = temporaryDirectory.resolve("config/missing-override.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                {"version":1,"overrides":{}}
                """, StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                LaunchBootstrap.verifyGeneratedOverrides(config, List.of("fabric-api")));
    }

    @Test
    void resolvesEqualsFormOfTheGameDirectoryArgument() {
        Path gameDirectory = temporaryDirectory.resolve("another instance");
        Path resolved = LaunchBootstrap.resolveGameDirectory(new String[] {
                "--gameDir=" + gameDirectory
        });
        assertEquals(gameDirectory.toAbsolutePath().normalize(), resolved);
    }
}
