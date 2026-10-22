package dev.gafipro.gafiloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A live-artifact compatibility test. CI provides Mojang's actual 26.2 client JAR,
 * official 1.21.11 mappings, Fabric Intermediary mappings, and a released 1.21.11 mod.
 *
 * <p>The test deliberately does not pretend to launch Minecraft. It proves that a real mod
 * can be scanned and its production bytecode can be transformed in memory without writing
 * to the stored JAR.</p>
 */
class RealModArtifactIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private static final String TARGET_GAME_PROPERTY = "gafi.loader.real.target.jar";
    private static final String LEGACY_MOD_PROPERTY = "gafi.loader.real.mod.jar";
    private static final String INTERMEDIARY_PROPERTY = "gafi.loader.real.intermediary.jar";
    private static final String LEGACY_MAPPINGS_PROPERTY = "gafi.loader.real.official.mappings";

    @AfterEach
    void clearGlobalMixinConfiguration() {
        MixinResourceRemapper.configure(null, CompatibilityScanner.AliasSet.empty());
    }

    @Test
    void scansAndRemapsRealProductionModAgainstOfficialMinecraft262WithoutChangingModJar()
            throws IOException, NoSuchAlgorithmException {
        String targetValue = System.getProperty(TARGET_GAME_PROPERTY);
        String modValue = System.getProperty(LEGACY_MOD_PROPERTY);
        String intermediaryValue = System.getProperty(INTERMEDIARY_PROPERTY);
        String mappingsValue = System.getProperty(LEGACY_MAPPINGS_PROPERTY);
        assumeTrue(targetValue != null && modValue != null && intermediaryValue != null
                        && mappingsValue != null,
                "Real artifact properties are only configured by the real-artifact CI integration step.");

        Path targetGame = requireFile(targetValue);
        Path modJar = requireFile(modValue);
        Path intermediaryJar = requireFile(intermediaryValue);
        Path legacyMappingsFile = requireFile(mappingsValue);

        String modHashBefore = sha256(modJar);
        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediaryJar, MojangMappings.load(legacyMappingsFile));
        MixinResourceRemapper.configure(mappings, CompatibilityScanner.AliasSet.empty());

        CompatibilityScanner.Report report = new CompatibilityScanner().scan(
                targetGame, List.of(modJar), null, intermediaryJar, legacyMappingsFile);
        CompatibilityScanner.Summary summary = report.summary();
        assertEquals(1, summary.modJarsScanned(), "Expected the pinned production mod JAR to be scanned");
        assertTrue(summary.modClassesScanned() > 0, "No production mod classes were inspected");
        assertTrue(summary.targetClassesIndexed() > 0, "No classes were indexed from the official 26.2 JAR");
        assertTrue(summary.referencesInspected() > 0,
                "The production mod did not expose any direct Minecraft API references to inspect");

        int classFiles = 0;
        int transformedClasses = 0;
        int mixinJsonResources = 0;
        try (JarFile jar = new JarFile(modJar.toFile())) {
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                if (entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")) {
                    byte[] original;
                    try (InputStream input = jar.getInputStream(entry)) {
                        original = input.readAllBytes();
                    }
                    classFiles++;
                    byte[] transformed = IntermediaryBytecodeRemapper.remap(
                            original, mappings, CompatibilityScanner.AliasSet.empty(), null);
                    byte[] effective = transformed == null ? original : transformed;
                    // Parse the result again so an output that merely changed names but produced
                    // malformed classfile bytes cannot count as a pass.
                    new ClassReader(effective);
                    if (transformed != null) {
                        transformedClasses++;
                    }
                } else if (entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                        && MixinResourceRemapper.isMixinResource(entry.getName())) {
                    byte[] original;
                    try (InputStream input = jar.getInputStream(entry)) {
                        original = input.readAllBytes();
                    }
                    try (InputStream remapped = MixinResourceRemapper.transform(
                            entry.getName(), new java.io.ByteArrayInputStream(original))) {
                        assertTrue(remapped.readAllBytes().length > 0,
                                "Mixin resource unexpectedly became empty: " + entry.getName());
                    }
                    mixinJsonResources++;
                }
            }
        }

        assertTrue(classFiles > 0, "The selected mod JAR contained no class files");
        assertTrue(transformedClasses > 0,
                "The selected production mod JAR did not yield any in-memory Intermediary remapping");
        assertEquals(modHashBefore, sha256(modJar), "The original mod JAR must remain byte-for-byte unchanged");

        System.out.printf(
                "REAL_MOD_COMPATIBILITY_SCAN_OK targetClasses=%d modClasses=%d transformedClasses=%d "
                        + "directReferences=%d unresolvedFindings=%d mixinJsonResources=%d%n",
                summary.targetClassesIndexed(), classFiles, transformedClasses,
                summary.referencesInspected(), summary.findingCount(), mixinJsonResources);
        System.out.println("This verifies real artifact scanning and in-memory normalization; it does not prove in-game behavior.");
    }

    @Test
    void packagedAgentInjectsBridgesIntoClassesFromOfficialMinecraft262() throws Exception {
        String targetValue = System.getProperty(TARGET_GAME_PROPERTY);
        String intermediaryValue = System.getProperty(INTERMEDIARY_PROPERTY);
        String mappingsValue = System.getProperty(LEGACY_MAPPINGS_PROPERTY);
        assumeTrue(targetValue != null && intermediaryValue != null && mappingsValue != null,
                "Official Minecraft fixture properties are only configured by real-artifact CI.");

        Path targetGame = requireFile(targetValue);
        Path intermediaryJar = requireFile(intermediaryValue);
        Path legacyMappingsFile = requireFile(mappingsValue);
        String agentJarValue = System.getProperty("gafi.loader.agent.jar");
        assertNotNull(agentJarValue, "Gradle must provide the self-contained agent JAR");
        Path agentJar = requireFile(agentJarValue);

        Properties config = new Properties();
        config.setProperty("targetGameJar", targetGame.toString());
        config.setProperty("intermediaryMappings", intermediaryJar.toString());
        config.setProperty("legacyMappings", legacyMappingsFile.toString());
        Path configPath = temporaryDirectory.resolve("official-26.2-agent.properties");
        try (OutputStream output = Files.newOutputStream(configPath)) {
            config.store(output, "Gafi Loader official Minecraft 26.2 agent smoke test");
        }

        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        String classpath = System.getProperty("java.class.path")
                + File.pathSeparator + targetGame;
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-Xverify:all",
                "-javaagent:" + agentJar + "=" + configPath,
                "-cp", classpath,
                "dev.gafipro.gafiloader.RealTargetAgentSmokeMain")
                .redirectErrorStream(true)
                .start();

        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "Official target agent smoke subprocess timed out. Output:\n" + output);
        assertEquals(0, process.exitValue(), "Official target agent smoke subprocess failed. Output:\n" + output);
        assertTrue(output.contains("Added in-memory legacy API bridge to net/minecraft/world/scores/DisplaySlot"),
                "The packaged agent did not inject the DisplaySlot bridge. Output:\n" + output);
        assertTrue(output.contains("Added in-memory legacy API bridge to net/minecraft/world/scores/ReadOnlyScoreInfo"),
                "The packaged agent did not inject the ReadOnlyScoreInfo bridge. Output:\n" + output);
        assertTrue(output.contains("REAL_TARGET_AGENT_BRIDGES_OK"),
                "The real target classes did not expose both injected methods. Output:\n" + output);
    }

    private static Path requireFile(String value) {
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(path), "Required real integration fixture does not exist: " + path);
        return path;
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 128];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
