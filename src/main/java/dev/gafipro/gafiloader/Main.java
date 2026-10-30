package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class Main {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            runDefaultSetup();
            return;
        }
        if (args.length > 0 && args[0].equals("--prepare-legacy-overrides")) {
            runLegacyOverrideGenerator(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--remap-legacy-mods")) {
            try {
                runLegacyModRemapper(args);
            } catch (IllegalArgumentException | IOException exception) {
                System.err.println("GafiLoader: " + exception.getMessage());
                System.exit(2);
            }
            return;
        }
        try {
            Options options = parse(args);
            if (options.help()) {
                printUsage();
                return;
            }

            if (options.modsPath() != null) {
                List<Path> mods = findModJars(options.modsPath());
                CompatibilityScanner.Report report = new CompatibilityScanner()
                        .scan(options.gameJar(), mods, options.aliasesPath(),
                                options.intermediaryMappingsPath(), options.legacyMappingsPath(),
                                options.targetApiJars());

                printSummary(report);
                for (CompatibilityScanner.Finding finding : report.findings()) {
                    String symbol = finding.owner();
                    if (finding.name() != null) {
                        symbol += "#" + finding.name()
                                + (finding.descriptor() == null ? "" : finding.descriptor());
                    }
                    System.out.printf("  [%s] %s%n    mod: %s%n    %s%n",
                            finding.kind(), symbol, finding.modJar(), finding.detail());
                }

                writeJsonReport(options.reportPath(), report, "Symbol scan");
                if (report.findings().isEmpty()) {
                    System.out.println("No unresolved direct Minecraft symbols were found.");
                    System.out.println("This is not a guarantee of runtime compatibility.");
                } else {
                    System.out.println();
                    System.out.println("These are candidate incompatibilities, not automatic fixes.");
                    System.out.println("Class/member renames and changed behavior still require mappings or adapters.");
                }
            }

            if (options.legacyGameJar() != null) {
                GameApiComparator.Report report = new GameApiComparator().compare(
                        options.legacyGameJar(), options.gameJar(), options.legacyMappingsPath());
                printApiSummary(report);
                for (GameApiComparator.Difference difference : report.differences()) {
                    String symbol = difference.owner();
                    if (difference.name() != null) {
                        symbol += "#" + difference.name()
                                + (difference.descriptor() == null ? "" : difference.descriptor());
                    }
                    System.out.printf("  [%s] %s%n    %s%n",
                            difference.kind(), symbol, difference.detail());
                    if (!difference.candidates().isEmpty()) {
                        System.out.println("    candidates: " + String.join(", ", difference.candidates()));
                    }
                }

                writeJsonReport(options.apiReportPath(), report, "API comparison");
                System.out.println("Candidate names require review; this report does not apply patches.");
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("Gafi Loader: " + exception.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (IOException exception) {
            System.err.println("Gafi Loader: I/O error: " + exception.getMessage());
            System.exit(2);
        }
    }

    private static void runDefaultSetup() {
        String explicitMinecraft = System.getProperty("gafiloader.minecraftDir");
        if (explicitMinecraft == null || explicitMinecraft.isBlank()) {
            explicitMinecraft = System.getenv("GAFILOADER_MINECRAFT_DIR");
        }
        MinecraftDirectoryResolver.Resolution resolution = MinecraftDirectoryResolver.resolve(
                explicitMinecraft, System.getenv("APPDATA"), System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home"));
        Path minecraft = resolution.path();
        Path setupLog = minecraft.resolve("logs").resolve("gafiloader-setup.log");
        initializeSetupLog(setupLog, minecraft);

        System.out.println("GafiLoader — create a dedicated Minecraft installation");
        System.out.println("Minecraft folder: " + minecraft);
        System.out.println("Minecraft folder detection: " + resolution.source());
        System.out.println("Setup diagnostic log: " + setupLog);
        System.out.println("IMPORTANT: fully close Minecraft Launcher before continuing, then reopen it after setup.");
        System.out.println("GafiLoader will create a separate " + ProfileBootstrapInstaller.PROFILE_NAME
                + " launcher entry from an installed Fabric " + ProfileBootstrapInstaller.GAME_VERSION + " profile.");
        System.out.println("The source Fabric version and vanilla profiles will remain unchanged.");
        System.out.println("Dependency overrides are generated at launch using the new installation's actual --gameDir.");

        boolean installed = installLaunchBootstrap(minecraft, setupLog);
        appendSetupLog(setupLog, (installed ? "RESULT: SUCCESS" : "RESULT: FAILED")
                + "; minecraftDirectory=" + minecraft);
        System.out.println();
        if (installed) {
            System.out.println("Success: the " + ProfileBootstrapInstaller.PROFILE_NAME
                    + " installation has been registered. Reopen Minecraft Launcher and select it under Installations.");
            System.out.println("If setup says no Fabric 26.2 profile exists, install Fabric for Minecraft 26.2 once and rerun GafiLoader.");
            System.out.println("Compatibility with every legacy mod is not guaranteed; keep the original mod JARs.");
        } else {
            System.out.println("The GafiLoader installation was not created. Read the message above or inspect the setup log.");
        }
        System.out.println();
        System.out.println("Press Enter to close.");
        try {
            System.in.read();
        } catch (IOException ignored) {
            // Closing the console is harmless.
        }
    }

    private static boolean installLaunchBootstrap(Path minecraft, Path setupLog) {
        appendSetupLog(setupLog, "Starting setup. minecraftDirectory=" + minecraft);
        appendSetupLog(setupLog, "Minecraft directory detection=" + MinecraftDirectoryResolver
                .resolve(System.getProperty("gafiloader.minecraftDir") != null
                        && !System.getProperty("gafiloader.minecraftDir").isBlank()
                        ? System.getProperty("gafiloader.minecraftDir")
                        : System.getenv("GAFILOADER_MINECRAFT_DIR"),
                        System.getenv("APPDATA"), System.getProperty("user.home")).source());
        try (InputStream bootstrap = Main.class.getResourceAsStream("/bootstrap/gafiloader-bootstrap.jar")) {
            if (bootstrap == null) {
                String message = "Embedded launch bootstrap was not packaged; the profile was not modified.";
                System.err.println("GafiLoader: " + message);
                appendSetupLog(setupLog, "ERROR: " + message);
                return false;
            }
            String version = Main.class.getPackage().getImplementationVersion();
            if (version == null || version.isBlank()) version = "0.1.0-SNAPSHOT";
            appendSetupLog(setupLog, "Application implementation version=" + version);

            ProfileBootstrapInstaller.Result result =
                    new ProfileBootstrapInstaller().install(minecraft, bootstrap, version);
            System.out.println("[GafiLoader] " + result.message());
            appendSetupLog(setupLog, "Installer result: installed=" + result.installed()
                    + "; message=" + result.message());
            appendSetupLog(setupLog, "Version JSON path=" + result.profileJson());
            appendSetupLog(setupLog, "Bootstrap JAR path=" + result.bootstrapJar());
            return result.installed();
        } catch (IOException | RuntimeException exception) {
            System.err.println("GafiLoader could not install the launch-time bootstrap: " + exception.getMessage());
            StringWriter details = new StringWriter();
            exception.printStackTrace(new PrintWriter(details));
            appendSetupLog(setupLog, "ERROR: " + details);
            return false;
        }
    }

    private static void initializeSetupLog(Path logPath, Path minecraftDirectory) {
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, "GafiLoader setup diagnostics — " + Instant.now()
                            + System.lineSeparator() + "Minecraft directory: " + minecraftDirectory
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException exception) {
            System.err.println("[GafiLoader] Could not create setup log: " + logPath
                    + " (" + exception.getMessage() + ")");
        }
    }

    private static void appendSetupLog(Path logPath, String message) {
        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(logPath, Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("[GafiLoader] Could not append setup log: " + logPath
                    + " (" + exception.getMessage() + ")");
        }
    }

    private static void runInteractiveWizard() {
        java.util.Scanner input = new java.util.Scanner(System.in);
        String explicitMinecraft = System.getProperty("gafiloader.minecraftDir");
        if (explicitMinecraft == null || explicitMinecraft.isBlank()) {
            explicitMinecraft = System.getenv("GAFILOADER_MINECRAFT_DIR");
        }
        Path minecraft = MinecraftDirectoryResolver.resolve(
                explicitMinecraft, System.getenv("APPDATA"), System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home")).path();
        Path defaultMods = minecraft.resolve("mods");
        Path defaultConfig = minecraft.resolve("config").resolve("fabric_loader_dependencies.json");

        System.out.println("GafiLoader — Legacy Fabric metadata diagnostic");
        System.out.println("This utility can only bypass a Minecraft version check; it is not yet a runtime compatibility loader.");
        System.out.println();
        System.out.println("Enter the mods folder for the Minecraft 26.2 instance.");
        System.out.println("Press Enter to use: " + defaultMods);
        System.out.print("Mods folder: ");
        String modsText = input.nextLine().trim();
        Path mods = modsText.isEmpty() ? defaultMods : Path.of(modsText.replace("\"", ""));
        System.out.println();
        System.out.println("Enter the config/fabric_loader_dependencies.json path for that same instance.");
        System.out.println("Press Enter to use: " + defaultConfig);
        System.out.print("Config path: ");
        String configText = input.nextLine().trim();
        Path config = configText.isEmpty() ? defaultConfig : Path.of(configText.replace("\"", ""));

        System.out.println();
        System.out.println("This will create/merge the override config and will not modify mod JARs.");
        System.out.print("Continue? Type YES to proceed: ");
        if (!input.nextLine().trim().equals("YES")) {
            System.out.println("Cancelled; no files were changed.");
            return;
        }
        try {
            LegacyDependencyOverrideGenerator.Result result =
                    new LegacyDependencyOverrideGenerator().generate(mods, config);
            System.out.println();
            System.out.println("Config written: " + result.configPath());
            System.out.println("JARs scanned: " + result.jarsScanned());
            System.out.println("Mod IDs overridden: " + result.modIdsOverridden());
            if (!result.overriddenModIds().isEmpty()) {
                System.out.println(String.join(", ", result.overriddenModIds()));
            }
            System.out.println("Original mod JARs were not modified.");
            System.out.println("Important: this does not fix missing classes, methods, mappings or mixins.");
            for (String warning : result.warnings()) System.out.println("Warning: " + warning);
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("GafiLoader: " + exception.getMessage());
        }
        System.out.println();
        System.out.println("Press Enter to close.");
        input.nextLine();
    }

    private static void runLegacyModRemapper(String[] args) throws IOException {
        Path mods = null, intermediary = null, legacyMappings = null, aliases = null, output = null, game = null;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.println("Usage: gafi-loader --remap-legacy-mods --mods <dir> --intermediary-mappings <1.21.11-mappings.jar> --legacy-mappings <official-mappings.txt> --game <26.2.jar> --output <separate-dir> [--aliases aliases.tsv]");
                return;
            }
            if (++i >= args.length) throw new IllegalArgumentException(arg + " requires a value.");
            Path value = Path.of(args[i]);
            switch (arg) {
                case "--mods" -> mods = value;
                case "--intermediary-mappings" -> intermediary = value;
                case "--legacy-mappings" -> legacyMappings = value;
                case "--aliases" -> aliases = value;
                case "--output" -> output = value;
                case "--game" -> game = value;
                default -> throw new IllegalArgumentException("Unknown remapper argument: " + arg);
            }
        }
        if (mods == null || intermediary == null || legacyMappings == null || output == null || game == null) {
            throw new IllegalArgumentException("Required: --mods, --intermediary-mappings, --legacy-mappings, --game, --output. Use --remap-legacy-mods --help.");
        }
        LegacyModRemapper.Result result = new LegacyModRemapper().remap(mods, intermediary, legacyMappings, aliases, output, game);
        System.out.println("Remapping complete: " + result.jarsRemapped() + " JAR(s).");
        System.out.println("Original mods: " + result.inputDirectory());
        System.out.println("Remapped copies: " + result.outputDirectory());
        System.out.println("Mappings loaded: " + result.mappedClasses() + " classes, " + result.mappedMembers() + " members.");
        System.out.println("Aliases applied: " + result.classAliases() + " classes, " + result.memberAliases() + " members.");
        for (String warning : result.warnings()) System.out.println("WARNING: " + warning);
    }

    private static void runLegacyOverrideGenerator(String[] args) {
        Path modsPath = null;
        Path configPath = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--mods" -> {
                    if (++i >= args.length) {
                        System.err.println("--mods requires the legacy mods directory.");
                        System.exit(2);
                        return;
                    }
                    modsPath = Path.of(args[i]);
                }
                case "--config" -> {
                    if (++i >= args.length) {
                        System.err.println("--config requires an output path.");
                        System.exit(2);
                        return;
                    }
                    configPath = Path.of(args[i]);
                }
                case "--help", "-h" -> {
                    System.out.println("Usage: gafi-loader --prepare-legacy-overrides --mods <mods-directory> --config <config/fabric_loader_dependencies.json>");
                    return;
                }
                default -> {
                    System.err.println("Unknown override-generator argument: " + args[i]);
                    System.exit(2);
                    return;
                }
            }
        }
        if (modsPath == null || configPath == null) {
            System.err.println("The override generator requires both --mods and --config.");
            System.err.println("Usage: gafi-loader --prepare-legacy-overrides --mods <mods-directory> --config <config/fabric_loader_dependencies.json>");
            System.exit(2);
            return;
        }
        try {
            LegacyDependencyOverrideGenerator.Result result =
                    new LegacyDependencyOverrideGenerator().generate(modsPath, configPath);
            System.out.println("Fabric Loader dependency override config written: " + result.configPath());
            System.out.println("Mod JARs scanned: " + result.jarsScanned());
            System.out.println("Legacy mod IDs with Minecraft 1.21.11 dependency gates overridden: "
                    + result.modIdsOverridden());
            if (!result.overriddenModIds().isEmpty()) {
                System.out.println("Overridden mod IDs: " + String.join(", ", result.overriddenModIds()));
            }
            System.out.println("Original mod JARs were not modified.");
            System.out.println("WARNING: this only bypasses metadata version checks; binary/API incompatibilities remain.");
            for (String warning : result.warnings()) {
                System.out.println("Warning: " + warning);
            }
        } catch (IOException | IllegalArgumentException exception) {
            System.err.println("Gafi Loader: " + exception.getMessage());
            System.exit(2);
        }
    }

    private static void writeJsonReport(Path output, Object report, String label) throws IOException {
        if (output == null) {
            return;
        }
        Path reportPath = output.toAbsolutePath().normalize();
        Path parent = reportPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(reportPath, GSON.toJson(report) + System.lineSeparator());
        System.out.println();
        System.out.println(label + " JSON report: " + reportPath);
    }

    private static void printSummary(CompatibilityScanner.Report report) {
        CompatibilityScanner.Summary summary = report.summary();
        System.out.println("Gafi Loader — Minecraft compatibility scanner");
        System.out.println("Target game:       " + report.targetGameJar());
        System.out.println("Mod JARs scanned:  " + summary.modJarsScanned());
        System.out.println("Mod classes read:  " + summary.modClassesScanned());
        System.out.println("Target classes:    " + summary.targetClassesIndexed());
        if (!report.targetApiJars().isEmpty()) {
            System.out.println("Target Fabric API: " + String.join(", ", report.targetApiJars()));
            System.out.println("Checked namespaces: Minecraft + Fabric API public packages");
        }
        System.out.println("References inspected: " + summary.referencesInspected());
        System.out.println("Findings:          " + summary.findingCount()
                + " (classes: " + summary.missingClasses()
                + ", methods: " + summary.missingMethods()
                + ", fields: " + summary.missingFields() + ")");
        System.out.println("Aliases loaded:    " + summary.classAliasesLoaded() + " classes, "
                + summary.memberAliasesLoaded() + " members");
        System.out.println("Intermediary map:  " + summary.intermediaryClassesMapped() + " classes, "
                + summary.intermediaryMembersMapped() + " members");
        System.out.println();
    }

    private static void printApiSummary(GameApiComparator.Report report) {
        GameApiComparator.Summary summary = report.summary();
        System.out.println();
        System.out.println("Gafi Loader — 1.21.11 to 26.2 API comparison");
        System.out.println("Legacy game:       " + report.legacyGameJar());
        System.out.println("Target game:       " + report.targetGameJar());
        System.out.println("Legacy mappings:   " + report.legacyMappingsFile());
        System.out.println("Legacy classes:    " + report.legacyClassesIndexed()
                + " indexed (" + report.legacyClassesMapped() + " mapped)");
        System.out.println("Target classes:    " + report.targetClassesIndexed());
        System.out.println("Mapping members:   " + report.legacyMembersMapped());
        System.out.println("Differences:       " + summary.differences()
                + " (classes: " + summary.missingClasses()
                + ", methods: " + summary.missingMethods()
                + ", fields: " + summary.missingFields()
                + ", rename candidates: " + summary.renamedCandidates()
                + ", descriptor candidates: " + summary.descriptorChangedCandidates() + ")");
        System.out.println();
    }

    private static List<Path> findModJars(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Mods path does not exist: " + path);
        }
        if (Files.isRegularFile(path)) {
            requireJar(path, "Mod");
            return List.of(path.toAbsolutePath().normalize());
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Mods path is not a JAR or directory: " + path);
        }

        try (Stream<Path> files = Files.walk(path)) {
            List<Path> jars = files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(file -> file.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            if (jars.isEmpty()) {
                throw new IllegalArgumentException("No JAR files were found in: " + path);
            }
            return jars;
        }
    }

    private static void requireJar(Path path, String description) {
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException(description + " file must have a .jar extension: " + path);
        }
    }

    private static Options parse(String[] args) {
        Path gameJar = null;
        Path modsPath = null;
        Path reportPath = null;
        Path aliasesPath = null;
        Path intermediaryMappingsPath = null;
        Path legacyGameJar = null;
        Path legacyMappingsPath = null;
        Path apiReportPath = null;
        List<Path> targetApiJars = new java.util.ArrayList<>();
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--help", "-h" -> help = true;
                case "--game" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--game requires the Minecraft 26.2 JAR.");
                    }
                    gameJar = Path.of(args[i]);
                }
                case "--mods" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--mods requires a JAR path or directory.");
                    }
                    modsPath = Path.of(args[i]);
                }
                case "--report" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--report requires an output path.");
                    }
                    reportPath = Path.of(args[i]);
                }
                case "--aliases" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--aliases requires a TSV alias file.");
                    }
                    aliasesPath = Path.of(args[i]);
                }
                case "--target-api" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--target-api requires a target Fabric API JAR.");
                    }
                    targetApiJars.add(Path.of(args[i]));
                }
                case "--intermediary-mappings" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException(
                                "--intermediary-mappings requires the 1.21.11 Fabric Intermediary Tiny file or JAR.");
                    }
                    intermediaryMappingsPath = Path.of(args[i]);
                }
                case "--legacy-game" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--legacy-game requires the Minecraft 1.21.11 JAR.");
                    }
                    legacyGameJar = Path.of(args[i]);
                }
                case "--legacy-mappings" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--legacy-mappings requires official 1.21.11 mappings.");
                    }
                    legacyMappingsPath = Path.of(args[i]);
                }
                case "--api-report" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--api-report requires an output path.");
                    }
                    apiReportPath = Path.of(args[i]);
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + argument);
            }
        }

        if (help) {
            return new Options(null, null, null, null, null, null, null, null, List.of(), true);
        }
        if (gameJar == null) {
            throw new IllegalArgumentException("Missing required --game argument (Minecraft 26.2 target).");
        }
        if (modsPath == null && legacyGameJar == null) {
            throw new IllegalArgumentException("Provide --mods, --legacy-game, or both.");
        }
        if (legacyGameJar != null && legacyMappingsPath == null) {
            throw new IllegalArgumentException("--legacy-game requires --legacy-mappings (official 1.21.11 Mojang mappings).");
        }
        if (intermediaryMappingsPath != null && legacyMappingsPath == null) {
            throw new IllegalArgumentException(
                    "--intermediary-mappings requires --legacy-mappings to translate Intermediary names to Mojang names.");
        }
        if (legacyGameJar == null && legacyMappingsPath != null && intermediaryMappingsPath == null) {
            throw new IllegalArgumentException("--legacy-mappings requires --legacy-game or --intermediary-mappings.");
        }
        if (intermediaryMappingsPath != null && modsPath == null) {
            throw new IllegalArgumentException("--intermediary-mappings requires --mods.");
        }
        if (modsPath == null && (reportPath != null || aliasesPath != null || !targetApiJars.isEmpty())) {
            throw new IllegalArgumentException("--report, --aliases, and --target-api require --mods.");
        }
        if (legacyGameJar == null && apiReportPath != null) {
            throw new IllegalArgumentException("--api-report requires --legacy-game.");
        }
        if (!Files.isRegularFile(gameJar)) {
            throw new IllegalArgumentException("Minecraft 26.2 game JAR does not exist: " + gameJar);
        }
        requireJar(gameJar, "Target game");
        checkFile(aliasesPath, "Alias file");
        checkFile(intermediaryMappingsPath, "Intermediary mappings file/JAR");
        checkFile(legacyGameJar, "Legacy game JAR");
        checkFile(legacyMappingsPath, "Legacy mappings file");
        for (Path targetApiJar : targetApiJars) {
            checkFile(targetApiJar, "Target Fabric API JAR");
            requireJar(targetApiJar, "Target Fabric API");
        }
        if (legacyGameJar != null) {
            requireJar(legacyGameJar, "Legacy game");
        }
        return new Options(gameJar, modsPath, reportPath, aliasesPath, intermediaryMappingsPath,
                legacyGameJar, legacyMappingsPath, apiReportPath, List.copyOf(targetApiJars), false);
    }

    private static void checkFile(Path path, String description) {
        if (path != null && !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(description + " does not exist: " + path);
        }
    }

    private static void printUsage() {
        System.out.println("""
                Gafi Loader — Minecraft 26.2 compatibility tools

                Usage:
                  gafi-loader --prepare-legacy-overrides --mods <mods-directory> --config <config/fabric_loader_dependencies.json>
                  gafi-loader --remap-legacy-mods --mods <original-mods> --intermediary-mappings <1.21.11-mappings.jar> --legacy-mappings <official-mappings.txt> --game <26.2.jar> --output <separate-dir> [--aliases aliases.tsv]
                  gafi-loader --game <26.2-game.jar> --mods <mod.jar-or-directory> [--target-api <26.2-fabric-api.jar>] [--report report.json] [--aliases aliases.tsv]
                  gafi-loader --game <26.2-game.jar> --legacy-game <1.21.11-game.jar> --legacy-mappings <official-mappings.txt> [--api-report api-diff.json]
                  gafi-loader --game <26.2-game.jar> --mods <mods> --intermediary-mappings <intermediary-v2.jar> --legacy-mappings <official-mappings.txt>
                  Add --mods and --legacy-game to run both analyses.

                Options:
                  --prepare-legacy-overrides  Generate official Fabric Loader overrides for Minecraft 1.21.11 metadata gates
                  --remap-legacy-mods         Create separate remapped mod copies without overwriting originals
                  --output <path>             Separate destination directory for remapped copies
                  --config <path>             Output/merge path for fabric_loader_dependencies.json (override mode)
                  --game <path>             Minecraft 26.2 target JAR (always the target)
                  --mods <path>             One mod JAR or a directory of JARs to scan
                  --target-api <path>       Target Fabric API JAR (repeatable); check old Fabric API calls
                  --report <path>           Save the mod symbol scan as JSON
                  --aliases <path>          Apply reviewed TSV class/method/field aliases
                  --intermediary-mappings <path>  Tiny file or JAR for 1.21.11 Intermediary mappings
                  --legacy-game <path>      Obfuscated Minecraft 1.21.11 client JAR
                  --legacy-mappings <path>  Official Mojang mappings for Minecraft 1.21.11
                  --api-report <path>       Save the API comparison as JSON
                  --help, -h                Show this help

                The API comparison normalizes the 1.21.11 game JAR using Mojang's ProGuard mappings.
                The mod scanner can normalize production Intermediary references when both mapping files are supplied.
                Remapped copies are preparation artifacts, not a runtime loader. They do not repair behavior changes,
                missing Fabric API internals, mixins, or access wideners. Original JARs are never overwritten.
                """);
    }

    private record Options(Path gameJar, Path modsPath, Path reportPath, Path aliasesPath,
                           Path intermediaryMappingsPath, Path legacyGameJar,
                           Path legacyMappingsPath, Path apiReportPath, List<Path> targetApiJars,
                           boolean help) {}
}
