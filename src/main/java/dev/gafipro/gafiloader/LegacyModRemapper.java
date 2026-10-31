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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private static final long MAX_TOP_LEVEL_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_NESTED_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_METADATA_BYTES = 4L * 1024 * 1024;

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
            requireArchiveSize(input, MAX_TOP_LEVEL_ARCHIVE_BYTES, "Top-level mod archive");
            Path relative = inputRoot.relativize(input);
            Path output = outputRoot.resolve(relative).normalize();
            if (!output.startsWith(outputRoot)) {
                throw new IOException("Refusing to write outside output directory: " + output);
            }
            Files.createDirectories(output.getParent());

            List<Path> classpath = new ArrayList<>();
            classpath.add(target);
            for (Path classpathJar : inputs) {
                if (!classpathJar.equals(input)) classpath.add(classpathJar);
            }

            try {
                if (!declaresLegacyMinecraft(input)) {
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                    completed.add(input + " -> " + output + " (copied unchanged; not marked 1.21.11)");
                    continue;
                }
                remapOne(input, output, classpath, mappingProvider);
                remapNestedJars(output, outputRoot.resolve(".gafiloader-tmp"), classpath,
                        mappingProvider, 0);
                completed.add(input + " -> " + output);
            } catch (RuntimeException | IOException exception) {
                Files.deleteIfExists(output);
                throw new IOException("Failed to remap legacy mod " + input + ": " + exception.getMessage(),
                        exception);
            }
        }

        warnings.add("Original mod JARs were not modified; remapped copies are in " + outputRoot);
        warnings.add("This remapper changes bytecode names, not API behavior or missing functionality.");
        warnings.add("Do not put both original and remapped copies in the same active mods directory.");
        warnings.add("Nested JARs under META-INF/jars are recursively remapped when found inside legacy mod bundles.");
        return new Result(inputRoot.toString(), outputRoot.toString(), completed.size(),
                List.copyOf(completed), List.copyOf(warnings), intermediary.classCount(),
                intermediary.memberCount(), aliases.classAliasCount(), aliases.memberAliasCount());
    }

    private static void remapOne(Path input, Path output, List<Path> classpath,
                                 net.fabricmc.tinyremapper.IMappingProvider mappings) throws IOException {
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(mappings)
                .renameInvalidLocals(false)
                .rebuildSourceFilenames(true)
                .resolveMissing(true)
                .checkPackageAccess(false)
                .threads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))
                .build();
        try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
            consumer.addNonClassFiles(input);
            remapper.readInputs(input);
            remapper.readClassPath(classpath.toArray(Path[]::new));
            remapper.apply(consumer);
        } catch (RuntimeException exception) {
            Files.deleteIfExists(output);
            throw new IOException("TinyRemapper failed for " + input, exception);
        } finally {
            remapper.finish();
        }
    }

    private static boolean declaresLegacyMinecraft(Path jarPath) throws IOException {
        requireArchiveSize(jarPath, MAX_TOP_LEVEL_ARCHIVE_BYTES, "Mod archive");
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) return false;
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] metadataBytes = readBounded(input, MAX_METADATA_BYTES,
                        jarPath + "!/fabric.mod.json");
                var metadata = com.google.gson.JsonParser.parseString(
                        new String(metadataBytes, java.nio.charset.StandardCharsets.UTF_8));
                return metadata.isJsonObject()
                        && LegacyMinecraftVersionDetector.matches(metadata.getAsJsonObject());
            }
        }
    }

    /**
     * Fabric API is commonly a bundle of nested module JARs. Re-map those nested archives too,
     * otherwise the umbrella JAR would be remapped while the actual API classes stayed Intermediary.
     */
    private static void remapNestedJars(Path outerJar, Path temporaryRoot, List<Path> classpath,
                                        net.fabricmc.tinyremapper.IMappingProvider mappings,
                                        int depth) throws IOException {
        if (depth >= 8) throw new IOException("Nested mod JAR depth exceeded the safety limit: " + outerJar);
        long outerLimit = depth == 0 ? MAX_TOP_LEVEL_ARCHIVE_BYTES : MAX_NESTED_ARCHIVE_BYTES;
        requireArchiveSize(outerJar, outerLimit, "Nested remapper input archive");
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(outerJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        || !(name.startsWith("META-INF/jars/") || name.startsWith("META-INF/JARS/"))) {
                    continue;
                }
                if (entry.getSize() > MAX_NESTED_ARCHIVE_BYTES) {
                    throw new IOException("Nested mod archive exceeds the "
                            + MAX_NESTED_ARCHIVE_BYTES + "-byte safety limit: " + name);
                }
                Path work = Files.createTempDirectory(temporaryRoot.getParent(), "gafiloader-nested-");
                Path nestedInput = work.resolve("nested-input.jar");
                Path nestedOutput = work.resolve("nested-output.jar");
                try (InputStream input = zip.getInputStream(entry)) {
                    copyBounded(input, nestedInput, MAX_NESTED_ARCHIVE_BYTES,
                            outerJar + "!/" + name);
                }
                try {
                    // This nested archive belongs to an outer mod already confirmed as 1.21.11.
                    // Remap it even if the module omits its own minecraft dependency metadata.
                    remapOne(nestedInput, nestedOutput, classpath, mappings);
                    remapNestedJars(nestedOutput, temporaryRoot, classpath, mappings, depth + 1);
                    requireArchiveSize(nestedOutput, MAX_NESTED_ARCHIVE_BYTES,
                            "Remapped nested archive");
                    replacements.put(name, Files.readAllBytes(nestedOutput));
                } finally {
                    try (Stream<Path> files = Files.walk(work)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            }
        }
        if (replacements.isEmpty()) return;

        Path rewritten = outerJar.resolveSibling(outerJar.getFileName() + ".nested.tmp");
        try (ZipFile inputZip = new ZipFile(outerJar.toFile());
             OutputStream fileOutput = Files.newOutputStream(rewritten);
             ZipOutputStream outputZip = new ZipOutputStream(fileOutput)) {
            var entries = inputZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry old = entries.nextElement();
                ZipEntry replacement = new ZipEntry(old.getName());
                if (old.getTime() >= 0) replacement.setTime(old.getTime());
                outputZip.putNextEntry(replacement);
                byte[] changed = replacements.get(old.getName());
                if (changed != null) {
                    outputZip.write(changed);
                } else if (!old.isDirectory()) {
                    try (InputStream stream = inputZip.getInputStream(old)) {
                        stream.transferTo(outputZip);
                    }
                }
                outputZip.closeEntry();
            }
        }
        Files.move(rewritten, outerJar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void requireArchiveSize(Path path, long maximumBytes, String description)
            throws IOException {
        long size = Files.size(path);
        if (size > maximumBytes) {
            throw new IOException(description + " exceeds the " + maximumBytes
                    + "-byte safety limit: " + path);
        }
    }

    private static byte[] readBounded(InputStream input, long maximumBytes, String source)
            throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximumBytes) {
                throw new IOException("Archive entry exceeds the " + maximumBytes
                        + "-byte safety limit: " + source);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void copyBounded(InputStream input, Path destination, long maximumBytes,
                                    String source) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        try (OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Archive entry exceeds the " + maximumBytes
                            + "-byte safety limit: " + source);
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(destination);
            throw exception;
        }
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
