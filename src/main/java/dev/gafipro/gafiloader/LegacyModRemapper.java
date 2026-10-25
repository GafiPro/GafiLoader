package dev.gafipro.gafiloader;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.jar.JarFile;

/**
 * Creates a separate remapped copy of legacy mod JARs without changing the originals.
 *
 * <p>This is a preparation step, not a claim that every legacy mod is compatible. It maps
 * 1.21.11 Intermediary references through the supplied legacy mappings into Mojang names,
 * optionally applies reviewed aliases, and retains non-class resources. API behavior changes,
 * mixin targets, access wideners and incompatible Fabric API internals still need dedicated
 * adapters and runtime validation.</p>
 */
public final class LegacyModRemapper {
    public Result remap(Path modsDirectory, Path intermediaryMappings, Path legacyMojangMappings,
                        Path aliasesFile, Path outputDirectory, Path targetGameJar) throws IOException {
        requireDirectory(modsDirectory, "Legacy mods directory");
        requireFile(intermediaryMappings, "1.21.11 Intermediary mappings");
        requireFile(legacyMojangMappings, "1.21.11 Mojang mappings");
        requireFile(targetGameJar, "Minecraft 26.2 game JAR");

        Path inputRoot = modsDirectory.toAbsolutePath().normalize();
        Path outputRoot = outputDirectory.toAbsolutePath().normalize();
        if (outputRoot.startsWith(inputRoot)) {
            throw new IllegalArgumentException("Output directory must not be inside the original mods directory.");
        }
        if (outputRoot.equals(inputRoot)) {
            throw new IllegalArgumentException("Output directory must differ from the original mods directory.");
        }

        CompatibilityScanner.AliasSet aliases = aliasesFile == null
                ? CompatibilityScanner.AliasSet.empty() : CompatibilityScanner.AliasSet.load(aliasesFile);
        MojangMappings mojang = MojangMappings.load(legacyMojangMappings);
        IntermediaryMappings intermediary = IntermediaryMappings.load(intermediaryMappings, mojang);
        var mappingProvider = intermediary.tinyRemapperProvider(aliases);

        List<Path> inputs;
        try (Stream<Path> stream = Files.walk(inputRoot)) {
            inputs = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("No .jar files found in " + inputRoot);
        }

        Files.createDirectories(outputRoot);
        Path target = targetGameJar.toAbsolutePath().normalize();
        List<String> completed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Path input : inputs) {
            Path relative = inputRoot.relativize(input);
            Path output = outputRoot.resolve(relative).normalize();
            if (!output.startsWith(outputRoot)) {
                throw new IOException("Refusing to write outside output directory: " + output);
            }
            Files.createDirectories(output.getParent());

            // Fabric API modules and libraries are used as classpath context while remapping,
            // so descriptors and hierarchy lookups can resolve cross-mod types.
            TinyRemapper remapper = TinyRemapper.newRemapper()
                    .withMappings(mappingProvider)
                    .renameInvalidLocals(false)
                    .rebuildSourceFilenames(true)
                    .resolveMissing(true)
                    .checkPackageAccess(false)
                    .threads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))
                    .build();
            try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
                consumer.addNonClassFiles(input);
                remapper.readInputs(input);
                remapper.readClassPath(target);
                for (Path classpathJar : inputs) {
                    if (!classpathJar.equals(input)) {
                        remapper.readClassPath(classpathJar);
                    }
                }
                remapper.apply(consumer);
                completed.add(input + " -> " + output);
            } catch (RuntimeException | IOException exception) {
                Files.deleteIfExists(output);
                throw new IOException("Failed to remap legacy mod " + input + ": " + exception.getMessage(),
                        exception);
            } finally {
                remapper.finish();
            }
        }

        warnings.add("Original mod JARs were not modified; remapped copies are in " + outputRoot);
        warnings.add("This remapper changes bytecode names, not API behavior or missing functionality.");
        warnings.add("Do not put both original and remapped copies in the same active mods directory.");
        warnings.add("Nested JARs are preserved as resources and are not recursively remapped yet.");
        return new Result(inputRoot.toString(), outputRoot.toString(), completed.size(),
                List.copyOf(completed), List.copyOf(warnings), intermediary.classCount(),
                intermediary.memberCount(), aliases.classAliasCount(), aliases.memberAliasCount());
    }

    private static void requireDirectory(Path path, String description) {
        if (path == null || !Files.isDirectory(path)) {
            throw new IllegalArgumentException(description + " does not exist: " + path);
        }
    }

    private static void requireFile(Path path, String description) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(description + " does not exist: " + path);
        }
    }

    public record Result(String inputDirectory, String outputDirectory, int jarsRemapped,
                         List<String> outputs, List<String> warnings, int mappedClasses,
                         int mappedMembers, int classAliases, int memberAliases) {}
}
