package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyModRemapperTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesToWriteRemappedCopiesInsideTheOriginalModsDirectory() throws Exception {
        Path mods = Files.createDirectory(temporaryDirectory.resolve("mods"));
        Path mappings = Files.writeString(temporaryDirectory.resolve("intermediary.jar"), "placeholder");
        Path legacyMappings = Files.writeString(temporaryDirectory.resolve("legacy-mappings.txt"), "placeholder");
        Path game = Files.writeString(temporaryDirectory.resolve("minecraft-26.2.jar"), "placeholder");
        Path outputInsideMods = mods.resolve("remapped");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new LegacyModRemapper().remap(mods, mappings, legacyMappings, null,
                        outputInsideMods, game));

        assertTrue(error.getMessage().contains("must not be inside"));
        assertTrue(Files.isDirectory(mods));
        assertTrue(!Files.exists(outputInsideMods));
    }
}
