package dev.gafipro.gafiloader;

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
 * Applies narrow, version-specific bytecode owner fixes to staged mod copies.
 *
 * <p>Minecraft 26.2 moved vanilla entity registry constants from EntityType to EntityTypes.
 * Legacy 1.21.11 bytecode can therefore link to EntityType.PLAYER even though the equivalent
 * field now lives on EntityTypes. This rewrites only static field references whose descriptor
 * is EntityType, recursively including nested mod JARs. Source mod JARs are never modified.</p>
 */
final class LegacyModCompatibilityTransformer {
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
            return new Result(false, sourceModsDirectory, 0, 0, List.of());
        }

        Path source = sourceModsDirectory.toAbsolutePath().normalize();
        Path game = gameDirectory.toAbsolutePath().normalize();
        Path internalRoot = game.resolve(INTERNAL_DIRECTORY).normalize();
        Path staging = internalRoot.resolve(STAGED_MODS_DIRECTORY).normalize();
        if (!staging.startsWith(game) || !internalRoot.startsWith(game)) {
            throw new IOException("Refusing to create compatibility files outside the active game directory.");
        }
        if (source.startsWith(staging)) {
            return new Result(false, source, 0, 0,
                    List.of("The configured mods directory points inside GafiLoader's own compatibility cache; "
                            + "the automatic entity-registry rewrite was skipped."));
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
            return new Result(false, source, 0, 0, List.of());
        }

        Map<Path, byte[]> changedArchives = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int rewrittenReferences = 0;
        for (Path jarPath : jarPaths) {
            byte[] originalBytes;
            try {
                originalBytes = Files.readAllBytes(jarPath);
            } catch (IOException exception) {
                warnings.add("Could not read mod archive " + jarPath + ": " + exception.getMessage());
                continue;
            }

            ArchiveTransform transformed = transformArchive(originalBytes, 0);
            warnings.addAll(transformed.warnings());
            if (transformed.rewrittenReferences() > 0) {
                changedArchives.put(source.relativize(jarPath), transformed.bytes());
                rewrittenReferences += transformed.rewrittenReferences();
            }
        }

        if (changedArchives.isEmpty()) {
            return new Result(false, source, 0, 0, List.copyOf(warnings));
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

        return new Result(true, staging, rewrittenReferences, changedArchives.size(), List.copyOf(warnings));
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

    private static ArchiveTransform transformArchive(byte[] originalBytes, int depth) {
        if (depth > MAX_NESTED_ARCHIVE_DEPTH) {
            return new ArchiveTransform(originalBytes, 0,
                    List.of("Nested JAR depth limit reached; deeper archive was left unchanged."));
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean archiveChanged = false;
        int rewrittenReferences = 0;
        List<String> warnings = new ArrayList<>();

        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(originalBytes));
             ZipOutputStream zipOutput = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (isSignatureFile(entryName)) {
                    // If this archive is unchanged, the original byte array is returned unchanged.
                    // If it changes, its old signatures must not be retained.
                    input.closeEntry();
                    continue;
                }

                byte[] data = entry.isDirectory() ? new byte[0] : input.readAllBytes();
                if (!entry.isDirectory()) {
                    String lowerName = entryName.toLowerCase(Locale.ROOT);
                    if (lowerName.endsWith(".class")) {
                        ClassTransform classTransform = transformClass(data);
                        if (classTransform.rewrittenReferences() > 0) {
                            data = classTransform.bytes();
                            archiveChanged = true;
                            rewrittenReferences += classTransform.rewrittenReferences();
                        }
                    } else if (lowerName.endsWith(".jar")) {
                        ArchiveTransform nested = transformArchive(data, depth + 1);
                        warnings.addAll(nested.warnings());
                        if (nested.rewrittenReferences() > 0) {
                            data = nested.bytes();
                            archiveChanged = true;
                            rewrittenReferences += nested.rewrittenReferences();
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
        } catch (IOException | RuntimeException exception) {
            warnings.add("Could not inspect a JAR for compatibility rewrites: " + exception.getMessage());
            return new ArchiveTransform(originalBytes, 0, List.copyOf(warnings));
        }

        if (!archiveChanged) {
            return new ArchiveTransform(originalBytes, 0, List.copyOf(warnings));
        }
        return new ArchiveTransform(output.toByteArray(), rewrittenReferences, List.copyOf(warnings));
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
                  int modifiedJars, List<String> warnings) {}

    private record FieldReference(String owner, String name) {}

    private record ArchiveTransform(byte[] bytes, int rewrittenReferences, List<String> warnings) {}

    private record ClassTransform(byte[] bytes, int rewrittenReferences) {}
}
