package dev.gafipro.gafiloader;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.tinyremapper.IMappingProvider;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translates production Intermediary references from 1.21.11 mods into Mojang names.
 *
 * <p>Fabric's last Intermediary mappings for 1.21.11 map the old obfuscated namespace
 * to Intermediary. These names are then composed with the official Mojang mappings
 * so references can be compared with the unobfuscated 26.2 game.</p>
 */
final class IntermediaryMappings {
    private static final String EMBEDDED_MAPPING_PATH = "mappings/mappings.tiny";

    private final Map<String, String> intermediaryToNamedClasses;
    private final Map<MemberKey, MappedMember> intermediaryToNamedMembers;

    private IntermediaryMappings(Map<String, String> intermediaryToNamedClasses,
                                 Map<MemberKey, MappedMember> intermediaryToNamedMembers) {
        this.intermediaryToNamedClasses = Map.copyOf(intermediaryToNamedClasses);
        this.intermediaryToNamedMembers = Map.copyOf(intermediaryToNamedMembers);
    }

    static IntermediaryMappings load(Path tinyFileOrJar, MojangMappings mojangMappings) throws IOException {
        if (tinyFileOrJar == null || !Files.isRegularFile(tinyFileOrJar)) {
            throw new IllegalArgumentException("Intermediary mapping file/JAR does not exist: " + tinyFileOrJar);
        }
        if (mojangMappings == null) {
            throw new IllegalArgumentException("Official 1.21.11 Mojang mappings are required to normalize Intermediary.");
        }

        MemoryMappingTree tree = new MemoryMappingTree();
        String header = readTinyHeader(tinyFileOrJar);
        MappingFormat format;
        if (header.startsWith("tiny\t2\t")) {
            format = MappingFormat.TINY_2_FILE;
        } else if (header.startsWith("v1\t")) {
            format = MappingFormat.TINY_FILE;
        } else {
            throw new IOException("Unsupported Tiny mapping header in " + tinyFileOrJar + ": " + header);
        }

        if (isZip(tinyFileOrJar)) {
            try (ZipFile zip = new ZipFile(tinyFileOrJar.toFile())) {
                ZipEntry entry = zip.getEntry(EMBEDDED_MAPPING_PATH);
                if (entry == null) {
                    throw new IOException("Mapping JAR has no " + EMBEDDED_MAPPING_PATH + ": " + tinyFileOrJar);
                }
                try (InputStreamReader reader = new InputStreamReader(
                        zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    MappingReader.read(reader, format, tree);
                }
            }
        } else {
            MappingReader.read(tinyFileOrJar, format, tree);
        }

        if (tree.getClasses().isEmpty()) {
            throw new IOException("No classes were read from Intermediary mappings: " + tinyFileOrJar);
        }

        int officialId = tree.getNamespaceId("official");
        int intermediaryId = tree.getNamespaceId("intermediary");
        if (officialId == MappingTreeView.NULL_NAMESPACE_ID
                || intermediaryId == MappingTreeView.NULL_NAMESPACE_ID) {
            throw new IOException("Tiny mappings must contain 'official' and 'intermediary' namespaces: "
                    + tinyFileOrJar);
        }

        Map<String, String> classes = new HashMap<>();
        Map<MemberKey, MappedMember> members = new HashMap<>();

        for (MappingTree.ClassMapping classMapping : tree.getClasses()) {
            String officialOwner = classMapping.getName(officialId);
            String intermediaryOwner = classMapping.getName(intermediaryId);
            if (officialOwner == null || intermediaryOwner == null) {
                continue;
            }
            String namedOwner = mojangMappings.toNamedClass(officialOwner);
            putClassConsistent(classes, intermediaryOwner, namedOwner, tinyFileOrJar);

            for (MappingTree.FieldMapping field : classMapping.getFields()) {
                String intermediaryName = field.getName(intermediaryId);
                String officialName = field.getName(officialId);
                String intermediaryDescriptor = field.getDesc(intermediaryId);
                String officialDescriptor = field.getDesc(officialId);
                if (intermediaryName == null || officialName == null
                        || intermediaryDescriptor == null || officialDescriptor == null) {
                    continue;
                }
                String namedDescriptor = mojangMappings.toNamedDescriptor(officialDescriptor);
                String namedName = mojangMappings.toNamedMember(officialOwner,
                        MojangMappings.SymbolKind.FIELD, officialName, namedDescriptor);
                putMemberConsistent(members,
                        new MemberKey(intermediaryOwner, CompatibilityScanner.SymbolKind.FIELD,
                                intermediaryName, intermediaryDescriptor),
                        new MappedMember(namedOwner, namedName, namedDescriptor), tinyFileOrJar);
            }

            for (MappingTree.MethodMapping method : classMapping.getMethods()) {
                String intermediaryName = method.getName(intermediaryId);
                String officialName = method.getName(officialId);
                String intermediaryDescriptor = method.getDesc(intermediaryId);
                String officialDescriptor = method.getDesc(officialId);
                if (intermediaryName == null || officialName == null
                        || intermediaryDescriptor == null || officialDescriptor == null) {
                    continue;
                }
                String namedDescriptor = mojangMappings.toNamedDescriptor(officialDescriptor);
                String namedName = mojangMappings.toNamedMember(officialOwner,
                        MojangMappings.SymbolKind.METHOD, officialName, namedDescriptor);
                putMemberConsistent(members,
                        new MemberKey(intermediaryOwner, CompatibilityScanner.SymbolKind.METHOD,
                                intermediaryName, intermediaryDescriptor),
                        new MappedMember(namedOwner, namedName, namedDescriptor), tinyFileOrJar);
            }
        }

        return new IntermediaryMappings(classes, members);
    }

    /**
     * Creates a TinyRemapper provider for Intermediary -> 26.2 named mappings.
     * Reviewed aliases are composed after the 1.21.11 intermediary-to-Mojang maps.
     * Descriptor-shape changes are rejected because a rename mapping cannot adapt call semantics.
     */
    IMappingProvider tinyRemapperProvider(CompatibilityScanner.AliasSet aliases) {
        return out -> {
            for (Map.Entry<String, String> classMapping : intermediaryToNamedClasses.entrySet()) {
                String targetName = aliases.mapClass(classMapping.getValue());
                if (!classMapping.getKey().equals(targetName)) {
                    out.acceptClass(classMapping.getKey(), targetName);
                }
            }

            for (Map.Entry<MemberKey, MappedMember> entry : intermediaryToNamedMembers.entrySet()) {
                MemberKey source = entry.getKey();
                MappedMember legacy = entry.getValue();
                CompatibilityScanner.SymbolKind kind = source.kind();

                CompatibilityScanner.Reference oldReference = new CompatibilityScanner.Reference(
                        kind, legacy.owner(), legacy.name(), legacy.descriptor());
                CompatibilityScanner.Reference targetReference = aliases.normalize(oldReference);

                String classTargetOwner = aliases.mapClass(legacy.owner());
                if (!classTargetOwner.equals(targetReference.owner())) {
                    throw new IllegalArgumentException("Cannot remap member across owners without a dedicated "
                            + "compatibility adapter: " + legacy.owner() + "#" + legacy.name()
                            + " -> " + targetReference.owner() + "#" + targetReference.name());
                }

                String structurallyRemappedDescriptor = aliases.mapDescriptor(
                        normalizeDescriptor(source.descriptor()));
                if (!structurallyRemappedDescriptor.equals(targetReference.descriptor())) {
                    throw new IllegalArgumentException("Cannot safely remap changed descriptor for "
                            + legacy.owner() + "#" + legacy.name() + legacy.descriptor()
                            + ": bytecode descriptor remapping yields " + structurallyRemappedDescriptor
                            + ", but the alias requests " + targetReference.descriptor()
                            + ". A semantic bytecode adapter is required.");
                }

                IMappingProvider.Member providerMember = new IMappingProvider.Member(
                        source.owner(), source.name(), source.descriptor());
                if (kind == CompatibilityScanner.SymbolKind.METHOD) {
                    if (!source.name().equals(targetReference.name())) {
                        out.acceptMethod(providerMember, targetReference.name());
                    }
                } else if (!source.name().equals(targetReference.name())) {
                    out.acceptField(providerMember, targetReference.name());
                }
            }
        };
    }

    CompatibilityScanner.Reference normalize(CompatibilityScanner.Reference reference) {
        if (reference.kind() == CompatibilityScanner.SymbolKind.CLASS) {
            return new CompatibilityScanner.Reference(reference.kind(),
                    intermediaryToNamedClasses.getOrDefault(reference.owner(), reference.owner()),
                    null, null);
        }

        MappedMember member = intermediaryToNamedMembers.get(new MemberKey(
                reference.owner(), reference.kind(), reference.name(), reference.descriptor()));
        if (member != null) {
            return new CompatibilityScanner.Reference(reference.kind(),
                    member.owner(), member.name(), member.descriptor());
        }

        // Normalize the owner and descriptor even when this particular member has no mapping.
        // Retaining the old member name leaves a useful unresolved-symbol diagnostic.
        return new CompatibilityScanner.Reference(reference.kind(),
                intermediaryToNamedClasses.getOrDefault(reference.owner(), reference.owner()),
                reference.name(), normalizeDescriptor(reference.descriptor()));
    }

    int classCount() {
        return intermediaryToNamedClasses.size();
    }

    int memberCount() {
        return intermediaryToNamedMembers.size();
    }

    private String normalizeDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return descriptor;
        }
        org.objectweb.asm.Type type = descriptor.charAt(0) == '('
                ? org.objectweb.asm.Type.getMethodType(descriptor)
                : org.objectweb.asm.Type.getType(descriptor);
        return remapType(type).getDescriptor();
    }

    private org.objectweb.asm.Type remapType(org.objectweb.asm.Type type) {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.OBJECT -> org.objectweb.asm.Type.getObjectType(
                    intermediaryToNamedClasses.getOrDefault(type.getInternalName(), type.getInternalName()));
            case org.objectweb.asm.Type.ARRAY -> {
                org.objectweb.asm.Type element = remapType(type.getElementType());
                yield org.objectweb.asm.Type.getType(
                        "[".repeat(type.getDimensions()) + element.getDescriptor());
            }
            case org.objectweb.asm.Type.METHOD -> {
                org.objectweb.asm.Type[] arguments = type.getArgumentTypes();
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = remapType(arguments[i]);
                }
                yield org.objectweb.asm.Type.getMethodType(
                        org.objectweb.asm.Type.getMethodDescriptor(
                                remapType(type.getReturnType()), arguments));
            }
            default -> type;
        };
    }

    private static String readTinyHeader(Path path) throws IOException {
        if (isZip(path)) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                ZipEntry entry = zip.getEntry(EMBEDDED_MAPPING_PATH);
                if (entry == null) {
                    throw new IOException("Mapping JAR has no " + EMBEDDED_MAPPING_PATH + ": " + path);
                }
                try (var input = zip.getInputStream(entry);
                     var reader = new java.io.BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line == null) {
                        throw new IOException("Empty mapping file in " + path);
                    }
                    return line;
                }
            }
        }

        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Empty mapping file: " + path);
            }
            return line;
        }
    }

    private static boolean isZip(Path path) {
        return path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static void putClassConsistent(Map<String, String> map, String key, String value,
                                           Path mappingFile) throws IOException {
        String previous = map.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) {
            throw new IOException("Conflicting Intermediary class mapping for " + key + " in " + mappingFile);
        }
    }

    private static void putMemberConsistent(Map<MemberKey, MappedMember> map, MemberKey key,
                                            MappedMember value, Path mappingFile) throws IOException {
        MappedMember previous = map.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) {
            throw new IOException("Conflicting Intermediary member mapping for " + key + " in " + mappingFile);
        }
    }

    private record MemberKey(String owner, CompatibilityScanner.SymbolKind kind,
                             String name, String descriptor) {}

    private record MappedMember(String owner, String name, String descriptor) {}

}
