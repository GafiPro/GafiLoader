package dev.gafipro.gafiloader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
        System.out.println("[GafiLoader] Pre-launch bootstrap active.");
        Path gameDirectory = resolveGameDirectory(args);
        Path modsDirectory = gameDirectory.resolve("mods");
        Path configPath = gameDirectory.resolve("config").resolve("fabric_loader_dependencies.json");
        System.out.println("[GafiLoader] Game directory: " + gameDirectory);
        System.out.println("[GafiLoader] Dependency override config: " + configPath);

        byte[] originalConfig = Files.isRegularFile(configPath) ? Files.readAllBytes(configPath) : null;
        byte[] generatedConfig = null;
        boolean configWasPrepared = false;

        if (Files.isDirectory(modsDirectory) && containsJar(modsDirectory)) {
            try {
                LegacyDependencyOverrideGenerator.Result result =
                        new LegacyDependencyOverrideGenerator().generate(modsDirectory, configPath);
                generatedConfig = Files.readAllBytes(configPath);
                configWasPrepared = true;
                System.out.println("[GafiLoader] JARs scanned: " + result.jarsScanned());
                System.out.println("[GafiLoader] Legacy mod IDs prepared: " + result.modIdsOverridden());
                if (!result.overriddenModIds().isEmpty()) {
                    System.out.println("[GafiLoader] Version gate removed for: "
                            + String.join(", ", result.overriddenModIds()));
                } else {
                    System.out.println("[GafiLoader] No metadata targeting Minecraft 1.21.11 was detected.");
                }
                for (String warning : result.warnings()) {
                    System.err.println("[GafiLoader] Warning: " + warning);
                }
            } catch (IOException | IllegalArgumentException exception) {
                throw new IOException("GafiLoader could not prepare Fabric dependency overrides at "
                        + configPath + ". Minecraft was not handed to Fabric so the original "
                        + "incompatible-mod screen would not hide this setup error.", exception);
            }
        } else {
            System.out.println("[GafiLoader] No mod JARs found under " + modsDirectory
                    + "; starting Fabric without generating dependency overrides.");
        }

        if (!configWasPrepared) {
            launchFabric(args);
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
            launchFabric(args);
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
