package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.JarEntry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapJarPackagingTest {
    @Test
    void bootstrapUsesMinecraftAsmInsteadOfBundlingDuplicateClasses() throws IOException {
        InputStream resource = getClass().getResourceAsStream("/bootstrap/gafiloader-bootstrap.jar");
        assertNotNull(resource, "The launch bootstrap must be embedded in the application resources.");

        Set<String> entries = new HashSet<>();
        try (JarInputStream jar = new JarInputStream(resource)) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                entries.add(entry.getName());
            }
        }

        assertTrue(entries.contains("dev/gafipro/gafiloader/LaunchBootstrap.class"));
        assertTrue(entries.contains("dev/gafipro/gafiloader/LegacyModCompatibilityTransformer.class"));
        assertTrue(entries.contains("com/google/gson/Gson.class"),
                "The bootstrap keeps its pinned Gson dependency.");
        assertFalse(entries.contains("org/objectweb/asm/ClassReader.class"),
                "ASM must come from the Minecraft 26.2 runtime classpath, not the bootstrap JAR.");
        assertFalse(entries.stream().anyMatch(name -> name.startsWith("org/objectweb/asm/")),
                "No ASM classes may be shaded into the launch bootstrap.");
    }
}
