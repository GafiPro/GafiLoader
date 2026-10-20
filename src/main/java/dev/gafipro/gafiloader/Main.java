package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
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
        try {
            Options options = parse(args);
            if (options.help()) {
                printUsage();
                return;
            }

            List<Path> mods = findModJars(options.modsPath());
            CompatibilityScanner.Report report = new CompatibilityScanner()
                    .scan(options.gameJar(), mods, options.aliasesPath());

            printSummary(report);
            for (CompatibilityScanner.Finding finding : report.findings()) {
                String symbol = finding.owner();
                if (finding.name() != null) {
                    symbol += "#" + finding.name() + (finding.descriptor() == null ? "" : finding.descriptor());
                }
                System.out.printf("  [%s] %s%n    mod: %s%n    %s%n",
                        finding.kind(), symbol, finding.modJar(), finding.detail());
            }

            if (options.reportPath() != null) {
                Path reportPath = options.reportPath().toAbsolutePath().normalize();
                Path parent = reportPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(reportPath, GSON.toJson(report) + System.lineSeparator());
                System.out.println();
                System.out.println("JSON report: " + reportPath);
            }

            if (report.findings().isEmpty()) {
                System.out.println("No unresolved direct Minecraft symbols were found.");
                System.out.println("This is not a guarantee of runtime compatibility.");
            } else {
                System.out.println();
                System.out.println("These are candidate incompatibilities, not automatic fixes.");
                System.out.println("Class/member renames and changed behavior still require mapping or adapters.");
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

    private static void printSummary(CompatibilityScanner.Report report) {
        CompatibilityScanner.Summary summary = report.summary();
        System.out.println("Gafi Loader — Minecraft compatibility scanner");
        System.out.println("Target game:       " + report.targetGameJar());
        System.out.println("Mod JARs scanned:  " + summary.modJarsScanned());
        System.out.println("Mod classes read:  " + summary.modClassesScanned());
        System.out.println("Target classes:    " + summary.targetClassesIndexed());
        System.out.println("Minecraft refs:    " + summary.referencesInspected());
        System.out.println("Findings:          " + summary.findingCount()
                + " (classes: " + summary.missingClasses()
                + ", methods: " + summary.missingMethods()
                + ", fields: " + summary.missingFields() + ")");
        System.out.println("Aliases loaded:    " + summary.classAliasesLoaded() + " classes, "
                + summary.memberAliasesLoaded() + " members");
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
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            switch (argument) {
                case "--help", "-h" -> help = true;
                case "--game" -> {
                    if (++i >= args.length) {
                        throw new IllegalArgumentException("--game requires a path to the Minecraft 26.2 JAR.");
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
                default -> throw new IllegalArgumentException("Unknown argument: " + argument);
            }
        }

        if (help) {
            return new Options(null, null, null, null, true);
        }
        if (gameJar == null) {
            throw new IllegalArgumentException("Missing required --game argument.");
        }
        if (modsPath == null) {
            throw new IllegalArgumentException("Missing required --mods argument.");
        }
        if (!Files.isRegularFile(gameJar)) {
            throw new IllegalArgumentException("Minecraft game JAR does not exist: " + gameJar);
        }
        requireJar(gameJar, "Target game");
        if (aliasesPath != null && !Files.isRegularFile(aliasesPath)) {
            throw new IllegalArgumentException("Alias file does not exist: " + aliasesPath);
        }
        return new Options(gameJar, modsPath, reportPath, aliasesPath, false);
    }

    private static void printUsage() {
        System.out.println("""
                Gafi Loader compatibility scanner

                Usage:
                  gafi-loader --game <26.2-game.jar> --mods <mod.jar-or-directory> [--report report.json]

                Options:
                  --game <path>     Minecraft 26.2 target JAR (required)
                  --mods <path>     One legacy mod JAR or a directory of JARs (required)
                  --report <path>   Also write a JSON report
                  --aliases <path>  Apply explicit TSV class/method/field aliases
                  --help, -h        Show this help

                The scanner finds direct net/minecraft class/member references absent from the target JAR.
                --aliases can normalize explicitly mapped old symbols before comparison.
                It does not load legacy mods or infer arbitrary renames yet.
                """);
    }

    private record Options(Path gameJar, Path modsPath, Path reportPath, Path aliasesPath, boolean help) {}
}
