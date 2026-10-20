package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
