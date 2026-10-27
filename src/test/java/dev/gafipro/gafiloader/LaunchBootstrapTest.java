package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchBootstrapTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesTheActualGameDirectoryArgument() {
        Path gameDirectory = temporaryDirectory.resolve("custom game directory");
        Path resolved = LaunchBootstrap.resolveGameDirectory(new String[] {
                "--username", "test", "--gameDir", gameDirectory.toString(), "--version", "gafiloader-26.2"
        });
        assertEquals(gameDirectory.toAbsolutePath().normalize(), resolved);
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
