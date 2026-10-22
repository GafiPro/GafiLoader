package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedAgentTransformsClassBeforeItIsLoadedByTheJvm() throws Exception {
        String agentJarSetting = System.getProperty("gafi.loader.agent.jar");
        assertNotNull(agentJarSetting, "Gradle must provide the built self-contained agent JAR");
        Path agentJar = Path.of(agentJarSetting);
        assertTrue(Files.isRegularFile(agentJar), "Agent JAR not found: " + agentJar);

        Path rules = temporaryDirectory.resolve("agent-smoke-rules.tsv");
        Files.writeString(rules,
                "METHOD_ALIAS\tdev/gafipro/gafiloader/AgentSmokeTarget\tcurrentValue\t()I\tlegacyValue\tSTATIC\n",
                StandardCharsets.UTF_8);

        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-javaagent:" + agentJar + "=" + rules,
                "-cp", System.getProperty("java.class.path"),
                "dev.gafipro.gafiloader.AgentSmokeMain")
                .redirectErrorStream(true)
                .start();

        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "Agent subprocess timed out. Output:\n" + output);
        assertEquals(0, process.exitValue(), "Agent subprocess failed. Output:\n" + output);
        assertTrue(output.contains("AGENT_SMOKE_OK=42"), "Agent did not add the alias. Output:\n" + output);
        assertTrue(output.contains("Runtime game-side compatibility agent active"),
                "Agent premain did not run. Output:\n" + output);
    }
    @Test
    void shadedAgentRelocatesAsmAwayFromFabricReservedNamespace() throws Exception {
        String agentJarSetting = System.getProperty("gafi.loader.agent.jar");
        assertNotNull(agentJarSetting, "Gradle must provide the built self-contained agent JAR");
        Path agentJar = Path.of(agentJarSetting);
        assertTrue(Files.isRegularFile(agentJar), "Agent JAR not found: " + agentJar);

        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(agentJar.toFile())) {
            assertNull(jar.getJarEntry("org/objectweb/asm/ClassReader.class"),
                    "The javaagent must not publish ASM in Fabric's reserved package");
            assertNotNull(jar.getJarEntry("dev/gafipro/gafiloader/internal/asm/ClassReader.class"),
                    "The javaagent must contain the relocated ASM runtime");
            java.util.jar.Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "The executable agent JAR needs a manifest");
            assertEquals("dev.gafipro.gafiloader.GameSideCompatibilityAgent",
                    manifest.getMainAttributes().getValue("Premain-Class"));
            assertEquals("dev.gafipro.gafiloader.Main",
                    manifest.getMainAttributes().getValue("Main-Class"));
        }
    }

}
