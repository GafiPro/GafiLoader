package dev.gafipro.gafiloader;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Entry point for the gafiloader-26.2 Minecraft profile.
 *
 * <p>Runs a preflight before Fabric Loader resolves mods, then delegates to Fabric's original
 * client entrypoint. The preflight includes folder resolution, metadata overrides, recovery
 * from a damaged override file, and a persistent report. It does not implement missing APIs.</p>
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
        log(logPath, "Game directory: " + gameDirectory, false);
        log(logPath, "Mods directory: " + modsDirectory, false);
        log(logPath, "Dependency override config: " + configPath, false);
        log(logPath, "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")", false);

        if (Files.isDirectory(modsDirectory) && containsJar(modsDirectory)) {
            try {
                LegacyDependencyOverrideGenerator.Result result =
                        new LegacyDependencyOverrideGenerator().generate(modsDirectory, configPath);
                verifyGeneratedOverrides(configPath, result.overriddenModIds());
                log(logPath, "JARs scanned: " + result.jarsScanned(), false);
                log(logPath, "Legacy mod IDs prepared: " + result.modIdsOverridden(), false);
                if (!result.overriddenModIds().isEmpty()) {
                    log(logPath, "Version gate removed for: "
                            + String.join(", ", result.overriddenModIds()), false);
                } else {
                    log(logPath, "No metadata explicitly targeting Minecraft 1.21.11 was detected. "
                            + "If a 1.21.11 mod is still rejected, inspect its fabric.mod.json and this log.", true);
                }
                for (String warning : result.warnings()) {
                    log(logPath, "Warning: " + warning, true);
                }
            } catch (IOException | IllegalArgumentException exception) {
                log(logPath, "Preflight failed: " + exception.getMessage(), true);
                throw new IOException("GafiLoader could not prepare Fabric dependency overrides at "
                        + configPath + ". See " + logPath
                        + ". Minecraft was not handed to Fabric, so this setup failure cannot be hidden "
                        + "by the incompatible-mod screen.", exception);
            }
        } else {
            log(logPath, "No mod JARs found under " + modsDirectory
                    + "; no dependency overrides were generated.", false);
        }

        log(logPath, "Preflight complete; delegating to Fabric Loader.", false);
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
     * Mirrors Fabric Loader's supported fabric.modsFolder system property.
     * Relative custom paths are resolved against the actual launch game directory.
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
     * Independently validates the exact file Fabric will read before handing control to it.
     * This catches an incomplete write or unexpected JSON structure at the integration boundary.
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
                || root.get("version").getAsInt() != 1) {
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
