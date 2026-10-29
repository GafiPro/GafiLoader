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

        if (Files.isDirectory(modsDirectory) && containsJar(modsDirectory)) {
            try {
                LegacyDependencyOverrideGenerator.Result result =
                        new LegacyDependencyOverrideGenerator().generate(modsDirectory, configPath);
                System.out.println("[GafiLoader] JARs scanned: " + result.jarsScanned());
                System.out.println("[GafiLoader] Legacy mod IDs prepared: " + result.modIdsOverridden());
                if (!result.overriddenModIds().isEmpty()) {
                    System.out.println("[GafiLoader] Version gate removed for: "
                            + String.join(", ", result.overriddenModIds()));
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
