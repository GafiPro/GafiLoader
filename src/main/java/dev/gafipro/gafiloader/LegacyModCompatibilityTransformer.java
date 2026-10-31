package dev.gafipro.gafiloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Converts legacy 1.21.11 Intermediary bytecode/resources in staged mod copies and then
 * applies narrow version-specific compatibility fixes. Original mod JARs are never modified.
 */
final class LegacyModCompatibilityTransformer {
    private final LegacyClassTweakerMappings suppliedClassTweakerMappings;

    LegacyModCompatibilityTransformer() {
        this(null);
    }

    LegacyModCompatibilityTransformer(LegacyClassTweakerMappings suppliedClassTweakerMappings) {
        this.suppliedClassTweakerMappings = suppliedClassTweakerMappings;
    }

    private static final String LEGACY_ENTITY_TYPE_OWNER = "net/minecraft/world/entity/EntityType";
    private static final String LEGACY_ENTITY_TYPE_INTERMEDIARY_OWNER = "net/minecraft/class_1299";
    private static final String ENTITY_TYPES_OWNER = "net/minecraft/world/entity/EntityTypes";
    private static final String ENTITY_TYPE_DESCRIPTOR = "Lnet/minecraft/world/entity/EntityType;";
    private static final String ENTITY_TYPE_INTERMEDIARY_DESCRIPTOR = "Lnet/minecraft/class_1299;";
    // In the 1.21.11 production Intermediary namespace, EntityType.PLAYER is field_6097.
    private static final String INTERMEDIARY_PLAYER_FIELD = "field_6097";
    private static final int MAX_NESTED_ARCHIVE_DEPTH = 12;
    private static final String INTERNAL_DIRECTORY = ".gafiloader";
    private static final String STAGED_MODS_DIRECTORY = "compat-mods";

    Result prepare(Path sourceModsDirectory, Path gameDirectory) throws IOException {
        if (sourceModsDirectory == null || !Files.isDirectory(sourceModsDirectory)) {
            return new Result(false, sourceModsDirectory, 0, 0, 0, 0, List.of());
        }

        Path source = sourceModsDirectory.toAbsolutePath().normalize();
        Path game = gameDirectory.toAbsolutePath().normalize();
        Path internalRoot = game.resolve(INTERNAL_DIRECTORY).normalize();
        Path staging = internalRoot.resolve(STAGED_MODS_DIRECTORY).normalize();
        if (!staging.startsWith(game) || !internalRoot.startsWith(game)) {
            throw new IOException("Refusing to create compatibility files outside the active game directory.");
        }
        if (source.startsWith(staging)) {
            return new Result(false, source, 0, 0, 0, 0,
                    List.of("The configured mods directory points inside GafiLoader's own compatibility cache; "
                            + "automatic compatibility rewrites were skipped."));
        }

        List<Path> jarPaths;
        try (var files = Files.walk(source)) {
            jarPaths = files.filter(path -> !path.startsWith(staging))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> source.relativize(path).toString()))
                    .toList();
        }
        if (jarPaths.isEmpty()) {
            return new Result(false, source, 0, 0, 0, 0, List.of());
        }

        // Load the full 1.21.11 namespace bridge when any top-level or nested mod
        // explicitly identifies itself as a 1.21.11 build. This keeps unrelated mods
        // outside the generic bytecode remapping pass.
        boolean hasLegacyMod = false;
        for (Path jarPath : jarPaths) {
            if (containsLegacyModMetadata(Files.readAllBytes(jarPath), 0)) {
                hasLegacyMod = true;
                break;
            }
        }

        LegacyClassTweakerMappings activeMappings = suppliedClassTweakerMappings;
        if (hasLegacyMod && activeMappings == null) {
            LegacyMappingRepository.MappingFiles files = LegacyMappingRepository.ensure(game);
            activeMappings = LegacyClassTweakerMappings.load(
                    files.intermediaryJar(), files.mojangMappings());
        }
        if (hasLegacyMod && activeMappings != null) {
            LegacyBytecodeRemapper.verifyRuntimeSupport();
        }
        LegacyClassTweakerRemapper classTweakerRemapper =
                new LegacyClassTweakerRemapper(game, activeMappings);
        LegacyBytecodeRemapper bytecodeRemapper = activeMappings == null
                ? null : new LegacyBytecodeRemapper(activeMappings);

        Map<Path, byte[]> changedArchives = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int rewrittenReferences = 0;
        int rewrittenClassTweakerFiles = 0;
        int remappedClasses = 0;
        for (Path jarPath : jarPaths) {
            byte[] originalBytes;
            try {
                originalBytes = Files.readAllBytes(jarPath);
            } catch (IOException exception) {
                warnings.add("Could not read mod archive " + jarPath + ": " + exception.getMessage());
                continue;
            }

            boolean remapIntermediaryBytecode = declaresLegacyMinecraft(originalBytes);
            ArchiveTransform transformed = transformArchive(originalBytes, 0,
                    classTweakerRemapper, bytecodeRemapper, remapIntermediaryBytecode);
            warnings.addAll(transformed.warnings());
            if (transformed.rewrittenReferences() > 0 || transformed.rewrittenClassTweakerFiles() > 0
                    || transformed.remappedClasses() > 0) {
                changedArchives.put(source.relativize(jarPath), transformed.bytes());
                rewrittenReferences += transformed.rewrittenReferences();
                rewrittenClassTweakerFiles += transformed.rewrittenClassTweakerFiles();
                remappedClasses += transformed.remappedClasses();
            }
        }

        if (changedArchives.isEmpty()) {
            return new Result(false, source, 0, 0, 0, 0, List.copyOf(warnings));
        }

        if (Files.isSymbolicLink(internalRoot)) {
            throw new IOException("Refusing to write compatibility copies through a symbolic link: " + internalRoot);
        }
        if (Files.exists(internalRoot) && !Files.isDirectory(internalRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GafiLoader's internal compatibility path is not a directory: " + internalRoot);
        }
        if (Files.isSymbolicLink(staging)) {
            throw new IOException("Refusing to replace a symbolic-link compatibility directory: " + staging);
        }
        if (Files.exists(staging) && !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("GafiLoader's staged mods path is not a directory: " + staging);
        }

        Files.createDirectories(internalRoot);
        clearOwnedDirectory(staging);
        Files.createDirectories(staging);

        try (var files = Files.walk(source)) {
            for (Path path : files.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                if (path.equals(source) || path.startsWith(staging)) {
                    continue;
                }
                Path relative = source.relativize(path);
                Path destination = staging.resolve(relative).normalize();
                if (!destination.startsWith(staging)) {
                    throw new IOException("Refusing to stage a path outside the compatibility directory: " + path);
                }

                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination);
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }

                Files.createDirectories(destination.getParent());
                byte[] patched = changedArchives.get(relative);
                if (patched != null) {
                    Files.write(destination, patched);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException exception) {
            try {
                clearOwnedDirectory(staging);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new IOException("Could not prepare the compatibility mod directory at " + staging
                    + ". Original mod files were left unchanged.", exception);
        }

        List<String> remainingIntermediaryTweakers = new ArrayList<>();
        try (var stagedFiles = Files.walk(staging)) {
            for (Path stagedJar : stagedFiles
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                auditArchiveForIntermediaryClassTweakers(Files.readAllBytes(stagedJar), 0,
                        staging.relativize(stagedJar).toString(), remainingIntermediaryTweakers);
            }
        }
        if (!remainingIntermediaryTweakers.isEmpty()) {
            try {
                clearOwnedDirectory(staging);
            } catch (IOException cleanupException) {
                warnings.add("Could not remove an incomplete staged mod directory: "
                        + cleanupException.getMessage());
            }
            throw new IOException("ClassTweaker compatibility verification failed. The following resource(s) "
                    + "still declare the Intermediary namespace after remapping: "
                    + String.join(", ", remainingIntermediaryTweakers)
                    + ". Fabric was not started with unremapped legacy files.");
        }

        return new Result(true, staging, rewrittenReferences, rewrittenClassTweakerFiles,
                remappedClasses, changedArchives.size(), List.copyOf(warnings));
    }

    private static void auditArchiveForIntermediaryClassTweakers(byte[] archive, int depth,
            String archivePath, List<String> findings) throws IOException {
        if (depth > MAX_NESTED_ARCHIVE_DEPTH) return;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    input.closeEntry();
                    continue;
                }
                byte[] contents = input.readAllBytes();
                String entryName = entry.getName();
                String lowerName = entryName.toLowerCase(Locale.ROOT);
                if (lowerName.endsWith(".jar")) {
                    auditArchiveForIntermediaryClassTweakers(contents, depth + 1,
                            archivePath + "!/" + entryName, findings);
                } else if (hasIntermediaryClassTweakerHeader(contents)) {
                    findings.add(archivePath + "!/" + entryName);
                }
                input.closeEntry();
            }
        }
    }

    private static boolean hasIntermediaryClassTweakerHeader(byte[] contents) {
        if (contents.length == 0) return false;
        String text = new String(contents, 0, Math.min(contents.length, 512), StandardCharsets.UTF_8);
        int start = 0;
        while (start < text.length()) {
            char character = text.charAt(start);
            if (character == (char) 0xFEFF || Character.isWhitespace(character)) {
                start++;
            } else {
                break;
            }
        }
        int lineEnd = text.indexOf(10, start);
        String firstLine = (lineEnd >= 0 ? text.substring(start, lineEnd) : text.substring(start)).strip();
        String[] header = firstLine.split("\\s+");
        return header.length == 3
                && ("classTweaker".equals(header[0]) || "accessWidener".equals(header[0]))
                && header[1].matches("v[0-9]+")
                && "intermediary".equals(header[2]);
    }

    private static void clearOwnedDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Refusing to traverse a symbolic link: " + directory);
        }
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path);
                } else {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static ArchiveTransform transformArchive(byte[] originalBytes, int depth,
            LegacyClassTweakerRemapper classTweakerRemapper,
            LegacyBytecodeRemapper bytecodeRemapper,
            boolean remapIntermediaryBytecode) throws IOException {
        if (depth > MAX_NESTED_ARCHIVE_DEPTH) {
            return new ArchiveTransform(originalBytes, 0, 0, 0,
                    List.of("Nested JAR depth limit reached; deeper archive was left unchanged."));
        }

        String declaredClassTweakerResource = declaredClassTweakerResource(originalBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean archiveChanged = false;
        int rewrittenReferences = 0;
        int rewrittenClassTweakerFiles = 0;
        int remappedClasses = 0;
        List<String> warnings = new ArrayList<>();

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(originalBytes));
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (isSignatureFile(entryName)) {
                    input.closeEntry();
                    continue;
                }

                byte[] data = entry.isDirectory() ? new byte[0] : input.readAllBytes();
                if (!entry.isDirectory()) {
                    String lowerName = entryName.toLowerCase(Locale.ROOT);
                    if (lowerName.endsWith(".class")) {
                        if (remapIntermediaryBytecode) {
                            if (bytecodeRemapper == null) {
                                throw new IOException("A legacy mod was marked for Intermediary bytecode remapping, "
                                        + "but the 1.21.11 mappings were not initialized.");
                            }
                            LegacyBytecodeRemapper.Result namespaceResult = bytecodeRemapper.remap(data);
                            if (namespaceResult.changed()) {
                                data = namespaceResult.bytes();
                                archiveChanged = true;
                                remappedClasses++;
                            }
                        }

                        // Apply narrow 26.2 API moves after namespace conversion, so the
                        // compatibility rule sees stable Mojang names, not class_ IDs.
                        ClassTransform classTransform = transformClass(data);
                        if (classTransform.rewrittenReferences() > 0) {
                            data = classTransform.bytes();
                            archiveChanged = true;
                            rewrittenReferences += classTransform.rewrittenReferences();
                        }
                    } else if (lowerName.endsWith(".jar")) {
                        boolean nestedLegacy = remapIntermediaryBytecode
                                || declaresLegacyMinecraft(data);
                        ArchiveTransform nested = transformArchive(data, depth + 1,
                                classTweakerRemapper, bytecodeRemapper, nestedLegacy);
                        warnings.addAll(nested.warnings());
                        if (nested.rewrittenReferences() > 0 || nested.rewrittenClassTweakerFiles() > 0
                                || nested.remappedClasses() > 0) {
                            data = nested.bytes();
                            archiveChanged = true;
                            rewrittenReferences += nested.rewrittenReferences();
                            rewrittenClassTweakerFiles += nested.rewrittenClassTweakerFiles();
                            remappedClasses += nested.remappedClasses();
                        }
                    } else if (isClassTweakerResource(entryName, data, declaredClassTweakerResource)) {
                        String sourceText = new String(data, StandardCharsets.UTF_8);
                        LegacyClassTweakerRemapper.Result remapped = classTweakerRemapper.remap(sourceText);
                        if (remapped.remapped()) {
                            data = remapped.contents().getBytes(StandardCharsets.UTF_8);
                            archiveChanged = true;
                            rewrittenClassTweakerFiles++;
                        }
                    }
                }

                ZipEntry rewrittenEntry = new ZipEntry(entryName);
                if (entry.getTime() >= 0) {
                    rewrittenEntry.setTime(entry.getTime());
                }
                zipOutput.putNextEntry(rewrittenEntry);
                if (data.length > 0) {
                    zipOutput.write(data);
                }
                zipOutput.closeEntry();
                input.closeEntry();
            }
        }

        if (!archiveChanged) {
            return new ArchiveTransform(originalBytes, 0, 0, 0, List.copyOf(warnings));
        }
        return new ArchiveTransform(output.toByteArray(), rewrittenReferences,
                rewrittenClassTweakerFiles, remappedClasses, List.copyOf(warnings));
    }

    private static boolean declaresLegacyMinecraft(byte[] archive) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && "fabric.mod.json".equals(entry.getName())) {
                    return metadataDeclaresLegacyMinecraft(input.readAllBytes());
                }
                input.closeEntry();
            }
        }
        return false;
    }

    private static boolean containsLegacyModMetadata(byte[] archive, int depth) throws IOException {
        if (depth > MAX_NESTED_ARCHIVE_DEPTH) return false;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    input.closeEntry();
                    continue;
                }
                byte[] contents = input.readAllBytes();
                String name = entry.getName();
                if ("fabric.mod.json".equals(name) && metadataDeclaresLegacyMinecraft(contents)) {
                    return true;
                }
                if (name.toLowerCase(Locale.ROOT).endsWith(".jar")
                        && containsLegacyModMetadata(contents, depth + 1)) {
                    return true;
                }
                input.closeEntry();
            }
        }
        return false;
    }

    private static boolean metadataDeclaresLegacyMinecraft(byte[] metadataBytes) {
        try {
            JsonElement parsed = JsonParser.parseString(
                    new String(metadataBytes, StandardCharsets.UTF_8));
            return parsed.isJsonObject()
                    && LegacyMinecraftVersionDetector.matches(parsed.getAsJsonObject());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Reads the resource path declared by the mod itself. Fabric API modules use the
     * legacy "accessWidener" metadata key even when the resource contains ClassTweaker
     * syntax, and custom resource names are valid. Looking at metadata avoids relying
     * solely on file extensions or a clean first byte.
     */
    static String declaredClassTweakerResource(byte[] archive) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && "fabric.mod.json".equals(entry.getName())) {
                    byte[] metadataBytes = input.readAllBytes();
                    try {
                        JsonElement parsed = JsonParser.parseString(
                                new String(metadataBytes, StandardCharsets.UTF_8));
                        if (!parsed.isJsonObject()) return null;
                        JsonObject metadata = parsed.getAsJsonObject();
                        JsonElement resource = metadata.get("accessWidener");
                        if (resource == null || !resource.isJsonPrimitive()
                                || !resource.getAsJsonPrimitive().isString()) {
                            resource = metadata.get("classTweaker");
                        }
                        if (resource == null || !resource.isJsonPrimitive()
                                || !resource.getAsJsonPrimitive().isString()) {
                            return null;
                        }
                        return normalizeResourcePath(resource.getAsString());
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                }
                input.closeEntry();
            }
        }
        return null;
    }

    private static String normalizeResourcePath(String resourcePath) {
        if (resourcePath == null) return null;
        String normalized = resourcePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Detects ClassTweaker resources by the metadata-declared path, conventional names,
     * and their header. Metadata is checked first so a custom-named ClassTweaker is not
     * silently skipped by the compatibility pass.
     */
    private static boolean isClassTweakerResource(String entryName, byte[] contents,
                                                   String declaredResource) {
        String lowerName = entryName.toLowerCase(Locale.ROOT);
        String normalizedEntryName = normalizeResourcePath(entryName);
        return (declaredResource != null && declaredResource.equals(normalizedEntryName))
                || lowerName.endsWith(".classtweaker")
                || lowerName.endsWith(".accesswidener")
                || looksLikeClassTweaker(contents);
    }

    private static boolean looksLikeClassTweaker(byte[] contents) {
        if (contents.length == 0 || contents.length > 4 * 1024 * 1024) return false;
        String text = new String(contents, 0, Math.min(contents.length, 512), StandardCharsets.UTF_8);
        int start = 0;
        while (start < text.length()) {
            char character = text.charAt(start);
            if (character == (char) 0xFEFF || Character.isWhitespace(character)) {
                start++;
            } else {
                break;
            }
        }
        int lineEnd = text.indexOf(10, start);
        String firstLine = (lineEnd >= 0 ? text.substring(start, lineEnd) : text.substring(start)).strip();
        String[] header = firstLine.split("\\s+");
        return header.length == 3
                && ("classTweaker".equals(header[0]) || "accessWidener".equals(header[0]))
                && header[1].matches("v[0-9]+")
                && !header[2].isBlank();
    }

    private static ClassTransform transformClass(byte[] originalBytes) {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            int[] rewrites = {0};

            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (delegate == null) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            FieldReference mapped = rewriteFieldReference(opcode, owner, name, descriptor);
                            if (mapped != null) {
                                owner = mapped.owner();
                                name = mapped.name();
                                rewrites[0]++;
                            }
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                                           Object... bootstrapMethodArguments) {
                            Handle mappedBootstrap = rewriteHandle(bootstrapMethodHandle, rewrites);
                            Object[] mappedArguments = rewriteConstants(bootstrapMethodArguments, rewrites);
                            super.visitInvokeDynamicInsn(name, descriptor, mappedBootstrap, mappedArguments);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            super.visitLdcInsn(rewriteConstant(value, rewrites));
                        }
                    };
                }
            }, 0);

            return rewrites[0] == 0
                    ? new ClassTransform(originalBytes, 0)
                    : new ClassTransform(writer.toByteArray(), rewrites[0]);
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            // An unrelated class that ASM cannot read is not modified. The original archive remains intact.
            return new ClassTransform(originalBytes, 0);
        }
    }

    private static FieldReference rewriteFieldReference(int opcode, String owner, String name,
                                                        String descriptor) {
        if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.PUTSTATIC) {
            return null;
        }

        // Development/source-style names as seen in unremapped Mojang-named JARs.
        if (LEGACY_ENTITY_TYPE_OWNER.equals(owner)
                && "PLAYER".equals(name)
                && ENTITY_TYPE_DESCRIPTOR.equals(descriptor)) {
            return new FieldReference(ENTITY_TYPES_OWNER, "PLAYER");
        }

        // Published 1.21.11 Fabric mods are normally remapped to Intermediary. The old
        // owner/name would otherwise be remapped by Fabric Loader back to EntityType.PLAYER
        // after this bootstrap has already scanned the archive.
        if (LEGACY_ENTITY_TYPE_INTERMEDIARY_OWNER.equals(owner)
                && INTERMEDIARY_PLAYER_FIELD.equals(name)
                && ENTITY_TYPE_INTERMEDIARY_DESCRIPTOR.equals(descriptor)) {
            return new FieldReference(ENTITY_TYPES_OWNER, "PLAYER");
        }
        return null;
    }

    private static Handle rewriteHandle(Handle handle, int[] rewrites) {
        if (handle.getTag() != Opcodes.H_GETSTATIC && handle.getTag() != Opcodes.H_PUTSTATIC) {
            return handle;
        }
        int opcode = handle.getTag() == Opcodes.H_GETSTATIC ? Opcodes.GETSTATIC : Opcodes.PUTSTATIC;
        FieldReference mapped = rewriteFieldReference(opcode, handle.getOwner(), handle.getName(), handle.getDesc());
        if (mapped == null) {
            return handle;
        }
        rewrites[0]++;
        return new Handle(handle.getTag(), mapped.owner(), mapped.name(),
                handle.getDesc(), handle.isInterface());
    }

    private static Object[] rewriteConstants(Object[] constants, int[] rewrites) {
        Object[] rewritten = new Object[constants.length];
        for (int i = 0; i < constants.length; i++) {
            rewritten[i] = rewriteConstant(constants[i], rewrites);
        }
        return rewritten;
    }

    private static Object rewriteConstant(Object constant, int[] rewrites) {
        if (constant instanceof Handle handle) {
            return rewriteHandle(handle, rewrites);
        }
        if (constant instanceof ConstantDynamic dynamic) {
            Handle bootstrap = rewriteHandle(dynamic.getBootstrapMethod(), rewrites);
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            boolean changed = bootstrap != dynamic.getBootstrapMethod();
            for (int i = 0; i < arguments.length; i++) {
                Object oldArgument = dynamic.getBootstrapMethodArgument(i);
                arguments[i] = rewriteConstant(oldArgument, rewrites);
                changed |= arguments[i] != oldArgument;
            }
            if (changed) {
                return new ConstantDynamic(dynamic.getName(), dynamic.getDescriptor(), bootstrap, arguments);
            }
        }
        return constant;
    }

    private static boolean isSignatureFile(String entryName) {
        String upper = entryName.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String leaf = upper.substring("META-INF/".length());
        if (leaf.contains("/")) {
            return false;
        }
        return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC") || leaf.startsWith("SIG-");
    }

    record Result(boolean transformed, Path modsDirectory, int rewrittenReferences,
                  int rewrittenClassTweakerFiles, int remappedClasses,
                  int modifiedJars, List<String> warnings) {}

    private record FieldReference(String owner, String name) {}

    private record ArchiveTransform(byte[] bytes, int rewrittenReferences,
                                    int rewrittenClassTweakerFiles, int remappedClasses,
                                    List<String> warnings) {}

    private record ClassTransform(byte[] bytes, int rewrittenReferences) {}
}
