package dev.gafipro.gafiloader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Entry point for the gafiloader-26.2 Minecraft profile.
 *
 * <p>Before Fabric Loader starts resolving mods, this creates dependency overrides for
 * metadata that explicitly targets 1.21.11, then delegates to Fabric's original client entrypoint.
 * This removes the early metadata gate only; it does not implement missing API behavior.</p>
 */
public final class LaunchBootstrap {
    private static final String FABRIC_MAIN_CLASS =
            "net.fabricmc.loader.impl.launch.knot.KnotClient";

    private LaunchBootstrap() {}

    public static void main(String[] args) throws Exception {
        Path gameDirectory = resolveGameDirectory(args);
        Path modsDirectory = resolveModsDirectory(gameDirectory);
        Path configPath = gameDirectory.resolve("config").resolve("fabric_loader_dependencies.json");
        Path logPath = gameDirectory.resolve("logs").resolve("gafiloader-preflight.log");
        initializeLog(logPath);
        log(logPath, "Pre-launch bootstrap active.", false);
        log(logPath, "Game directory: " + gameDirectory, false);
        log(logPath, "Mods directory: " + modsDirectory, false);
        log(logPath, "Dependency override config: " + configPath, false);
        log(logPath, "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")", false);

        byte[] originalConfig = Files.isRegularFile(configPath) ? Files.readAllBytes(configPath) : null;
        byte[] generatedConfig = null;
        boolean configWasPrepared = false;

        if (Files.isDirectory(modsDirectory) && containsJar(modsDirectory)) {
            try {
                LegacyDependencyOverrideGenerator.Result result =
                        new LegacyDependencyOverrideGenerator().generate(modsDirectory, configPath);
                generatedConfig = Files.readAllBytes(configPath);
                configWasPrepared = true;
                verifyGeneratedOverrides(configPath, result.overriddenModIds());
                log(logPath, "JARs scanned: " + result.jarsScanned(), false);
                log(logPath, "Legacy mod IDs prepared: " + result.modIdsOverridden(), false);
                if (!result.overriddenModIds().isEmpty()) {
                    log(logPath, "Version gate removed for: "
                            + String.join(", ", result.overriddenModIds()), false);
                } else {
                    log(logPath, "No metadata explicitly targeting Minecraft 1.21.11 was detected. "
                            + "If a 1.21.11 mod is rejected, confirm this is the intended mods directory.", true);
                }
                for (String warning : result.warnings()) {
                    log(logPath, "Warning: " + warning, true);
                }
            } catch (IOException | IllegalArgumentException exception) {
                log(logPath, "Preflight failed: " + exception.getMessage(), true);
                if (generatedConfig != null) {
                    restoreDependencyConfigSafely(configPath, originalConfig, generatedConfig);
                }
                throw new IOException("GafiLoader could not prepare Fabric dependency overrides at "
                        + configPath + ". See " + logPath + ". Minecraft was not handed to Fabric.", exception);
            }
        } else {
            log(logPath, "No mod JARs found under " + modsDirectory
                    + "; starting Fabric without generating dependency overrides.", false);
        }

        if (!configWasPrepared) {
            try {
                log(logPath, "Preflight complete; handing off to Fabric Loader.", false);
                launchFabric(args);
            } catch (Exception exception) {
                log(logPath, "Fabric launch failed: " + exception, true);
                throw exception;
            } catch (Error error) {
                log(logPath, "Fabric launch failed: " + error, true);
                throw error;
            }
            return;
        }

        // The dependency file is shared by Fabric profiles using the same game directory.
        // Keep the override available for this launch, then restore the previous file when the
        // game exits so a later launch of another profile is not affected.
        byte[] prior = originalConfig;
        byte[] prepared = generatedConfig;
        java.util.concurrent.atomic.AtomicBoolean restored = new java.util.concurrent.atomic.AtomicBoolean();
        Thread shutdownHook = new Thread(() -> {
            if (restored.compareAndSet(false, true)) {
                restoreDependencyConfigSafely(configPath, prior, prepared);
            }
        }, "GafiLoader-dependency-config-restore");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            log(logPath, "Preflight complete; handing off to Fabric Loader.", false);
            launchFabric(args);
        } catch (Exception exception) {
            log(logPath, "Fabric launch failed: " + exception, true);
            throw exception;
        } catch (Error error) {
            log(logPath, "Fabric launch failed: " + error, true);
            throw error;
        } finally {
            if (restored.compareAndSet(false, true)) {
                restoreDependencyConfigSafely(configPath, prior, prepared);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already underway; the shutdown hook handles restoration.
            }
        }
    }

    private static void launchFabric(String[] args) throws Exception {
        System.out.println("[GafiLoader] Preflight complete; handing off to Fabric Loader.");
        Class<?> fabricMainClass = Class.forName(FABRIC_MAIN_CLASS);
        Method main = fabricMainClass.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checkedException) {
                throw checkedException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("Fabric Loader main method failed.", cause);
        }
    }

    static void restoreDependencyConfigSafely(Path configPath, byte[] originalConfig,
                                              byte[] generatedConfig) {
        try {
            restoreDependencyConfig(configPath, originalConfig, generatedConfig);
        } catch (IOException | RuntimeException exception) {
            System.err.println("[GafiLoader] WARNING: Could not restore the pre-launch dependency config at "
                    + configPath + ": " + exception.getMessage()
                    + ". Check the preserved .bak file before changing it manually.");
        }
    }

    static void restoreDependencyConfig(Path configPath, byte[] originalConfig,
                                        byte[] generatedConfig) throws IOException {
        if (generatedConfig == null || !Files.isRegularFile(configPath)
                || !java.util.Arrays.equals(Files.readAllBytes(configPath), generatedConfig)) {
            System.err.println("[GafiLoader] Dependency config changed externally; leaving it untouched: "
                    + configPath);
            return;
        }

        if (originalConfig == null) {
            Files.delete(configPath);
            System.out.println("[GafiLoader] Removed the temporary dependency override config.");
            return;
        }

        Path parent = configPath.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Config path has no parent directory: " + configPath);
        }
        Path temporary = Files.createTempFile(parent, configPath.getFileName().toString(), ".restore.tmp");
        try {
            Files.write(temporary, originalConfig);
            try {
                Files.move(temporary, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("[GafiLoader] Restored the pre-launch dependency config.");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Path resolveGameDirectory(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if ("--gameDir".equals(argument) || "--game-directory".equals(argument)) {
                if (i + 1 < args.length) {
                    String value = unquote(args[i + 1]);
                    if (!value.isBlank()) {
                        return Path.of(value).toAbsolutePath().normalize();
                    }
                }
            } else if (argument.startsWith("--gameDir=") || argument.startsWith("--game-directory=")) {
                String value = unquote(argument.substring(argument.indexOf('=') + 1));
                if (!value.isBlank()) {
                    return Path.of(value).toAbsolutePath().normalize();
                }
            }
        }

        String appData = System.getenv("APPDATA");
        Path defaultDirectory = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".minecraft")
                : Path.of(appData, ".minecraft");
        return defaultDirectory.toAbsolutePath().normalize();
    }

    /**
     * Honors Fabric's configured mods folder, resolving relative paths against --gameDir.
     */
    static Path resolveModsDirectory(Path gameDirectory) {
        String configured = System.getProperty("fabric.modsFolder");
        if (configured != null && !configured.isBlank()) {
            Path custom = Path.of(unquote(configured));
            return (custom.isAbsolute() ? custom : gameDirectory.resolve(custom))
                    .toAbsolutePath().normalize();
        }
        return gameDirectory.resolve("mods").toAbsolutePath().normalize();
    }

    /**
     * Validates the exact dependency override JSON that Fabric will read.
     */
    static void verifyGeneratedOverrides(Path configPath, List<String> expectedModIds) throws IOException {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IOException("Generated dependency override config is not valid JSON: " + configPath, exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Generated dependency override config must be a JSON object: " + configPath);
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("version") || !root.get("version").isJsonPrimitive()
                || !root.get("version").getAsString().equals("1")) {
            throw new IOException("Generated dependency override config must use spec version 1: " + configPath);
        }
        if (!root.has("overrides") || !root.get("overrides").isJsonObject()) {
            throw new IOException("Generated dependency override config is missing its 'overrides' object: " + configPath);
        }
        JsonObject overrides = root.getAsJsonObject("overrides");
        for (String modId : expectedModIds) {
            if (!overrides.has(modId) || !overrides.get(modId).isJsonObject()) {
                throw new IOException("Generated dependency override is missing mod ID '" + modId + "'.");
            }
            JsonObject modOverrides = overrides.getAsJsonObject(modId);
            if (!modOverrides.has("-depends") || !modOverrides.get("-depends").isJsonObject()
                    || !modOverrides.getAsJsonObject("-depends").has("minecraft")) {
                throw new IOException("Generated dependency override for '" + modId
                        + "' does not remove its minecraft dependency.");
            }
        }
    }

    private static void initializeLog(Path logPath) {
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, "GafiLoader launch preflight — " + Instant.now()
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            System.err.println("[GafiLoader] Could not initialize preflight log: " + exception.getMessage());
        }
    }

    private static void log(Path logPath, String message, boolean warning) {
        String prefix = warning ? "[GafiLoader] Warning: " : "[GafiLoader] ";
        (warning ? System.err : System.out).println(prefix + message);
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, Instant.now() + " " + prefix + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("[GafiLoader] Could not append preflight log: " + exception.getMessage());
        }
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean containsJar(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                    .anyMatch(name -> name.endsWith(".jar"));
        }
    }
}
