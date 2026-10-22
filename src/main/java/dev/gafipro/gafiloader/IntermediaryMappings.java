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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

    // The inherited-member fallback is used for bytecode which names a subclass as the owner
    // even though Intermediary records the method only on its declaring superclass. Keep a
    // precomputed index instead of scanning every mapped member for every bytecode reference.
    private final Map<MemberLookupKey, MappedMember> unambiguousMembersByNameAndDescriptor;
    private final Set<MemberLookupKey> ambiguousMembersByNameAndDescriptor;
    private final Map<MemberOwnerNameKey, String> memberNamesByOwnerAndName;
    private final Set<MemberOwnerNameKey> ambiguousMemberNamesByOwnerAndName;
    private final Map<MemberNameKey, String> memberNamesByName;
    private final Set<MemberNameKey> ambiguousMemberNamesByName;

    private IntermediaryMappings(Map<String, String> intermediaryToNamedClasses,
                                 Map<MemberKey, MappedMember> intermediaryToNamedMembers) {
        this.intermediaryToNamedClasses = Map.copyOf(intermediaryToNamedClasses);
        this.intermediaryToNamedMembers = Map.copyOf(intermediaryToNamedMembers);

        Map<MemberLookupKey, MappedMember> unambiguous = new HashMap<>();
        Set<MemberLookupKey> ambiguous = new HashSet<>();
        for (Map.Entry<MemberKey, MappedMember> entry : intermediaryToNamedMembers.entrySet()) {
            MemberKey source = entry.getKey();
            MemberLookupKey lookupKey = new MemberLookupKey(
                    source.kind(), source.name(), source.descriptor());
            if (ambiguous.contains(lookupKey)) {
                continue;
            }

            MappedMember candidate = entry.getValue();
            MappedMember previous = unambiguous.putIfAbsent(lookupKey, candidate);
            // Owners may differ for inherited/overridden methods; only a disagreement on the
            // resulting member name or descriptor makes the owner-less fallback unsafe.
            if (previous != null && (!previous.name().equals(candidate.name())
                    || !previous.descriptor().equals(candidate.descriptor()))) {
                unambiguous.remove(lookupKey);
                ambiguous.add(lookupKey);
            }
        }
        this.unambiguousMembersByNameAndDescriptor = Map.copyOf(unambiguous);
        this.ambiguousMembersByNameAndDescriptor = Set.copyOf(ambiguous);

        Map<MemberOwnerNameKey, String> byOwnerAndName = new HashMap<>();
        Set<MemberOwnerNameKey> ambiguousByOwnerAndName = new HashSet<>();
        Map<MemberNameKey, String> byName = new HashMap<>();
        Set<MemberNameKey> ambiguousByName = new HashSet<>();
        for (Map.Entry<MemberKey, MappedMember> entry : intermediaryToNamedMembers.entrySet()) {
            MemberKey source = entry.getKey();
            String mappedName = entry.getValue().name();
            MemberOwnerNameKey ownerNameKey = new MemberOwnerNameKey(
                    source.kind(), source.owner(), source.name());
            if (!ambiguousByOwnerAndName.contains(ownerNameKey)) {
                String previous = byOwnerAndName.putIfAbsent(ownerNameKey, mappedName);
                if (previous != null && !previous.equals(mappedName)) {
                    byOwnerAndName.remove(ownerNameKey);
                    ambiguousByOwnerAndName.add(ownerNameKey);
                }
            }

            MemberNameKey nameKey = new MemberNameKey(source.kind(), source.name());
            if (!ambiguousByName.contains(nameKey)) {
                String previous = byName.putIfAbsent(nameKey, mappedName);
                if (previous != null && !previous.equals(mappedName)) {
                    byName.remove(nameKey);
                    ambiguousByName.add(nameKey);
                }
            }
        }
        this.memberNamesByOwnerAndName = Map.copyOf(byOwnerAndName);
        this.ambiguousMemberNamesByOwnerAndName = Set.copyOf(ambiguousByOwnerAndName);
        this.memberNamesByName = Map.copyOf(byName);
        this.ambiguousMemberNamesByName = Set.copyOf(ambiguousByName);
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
                    mapClassName(reference.owner()), null, null);
        }

        MemberKey directKey = new MemberKey(
                reference.owner(), reference.kind(), reference.name(), reference.descriptor());
        MappedMember member = intermediaryToNamedMembers.get(directKey);
        if (member != null) {
            return new CompatibilityScanner.Reference(reference.kind(),
                    member.owner(), member.name(), member.descriptor());
        }

        // Intermediary names identify override groups, but the mapping row for an inherited
        // method is usually owned by its declaring superclass. A production mod can legally
        // reference that method through a subclass owner (for example Button.method_25355,
        // inherited from AbstractWidget). Only use this fallback when every mapping row with
        // the exact old name + descriptor agrees on the same named method and descriptor.
        MappedMember inherited = findUnambiguousMemberByIntermediaryName(directKey);
        if (inherited != null) {
            return new CompatibilityScanner.Reference(reference.kind(),
                    mapClassName(reference.owner()), inherited.name(), inherited.descriptor());
        }

        // Normalize the owner and descriptor even when this particular member has no mapping.
        // Retaining the old member name leaves a useful unresolved-symbol diagnostic.
        return new CompatibilityScanner.Reference(reference.kind(),
                mapClassName(reference.owner()), reference.name(), normalizeDescriptor(reference.descriptor()));
    }

    private MappedMember findUnambiguousMemberByIntermediaryName(MemberKey requested) {
        MemberLookupKey key = new MemberLookupKey(
                requested.kind(), requested.name(), requested.descriptor());
        if (ambiguousMembersByNameAndDescriptor.contains(key)) {
            return null;
        }
        return unambiguousMembersByNameAndDescriptor.get(key);
    }

    /**
     * Map a class name, including anonymous/synthetic inner classes omitted from some mapping artifacts.
     * Inner binary names keep the outer-class prefix and the same '$' suffix after namespace remapping.
     */
    String mapClassName(String intermediaryInternalName) {
        String direct = intermediaryToNamedClasses.get(intermediaryInternalName);
        if (direct != null) {
            return direct;
        }

        int dollar = intermediaryInternalName.lastIndexOf('$');
        while (dollar > 0) {
            String outer = intermediaryToNamedClasses.get(intermediaryInternalName.substring(0, dollar));
            if (outer != null) {
                return outer + intermediaryInternalName.substring(dollar);
            }
            dollar = intermediaryInternalName.lastIndexOf('$', dollar - 1);
        }
        return intermediaryInternalName;
    }

    String mapMethodName(String owner, String name, String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return mapMemberName(CompatibilityScanner.SymbolKind.METHOD, owner, name);
        }
        return normalize(new CompatibilityScanner.Reference(
                CompatibilityScanner.SymbolKind.METHOD, owner, name, descriptor)).name();
    }

    /**
     * Remap a selector that has only a member name and no descriptor. Such selectors are legal
     * in Mixin annotations. Prefer the direct owner/name row, then fall back only when the mapping
     * is globally unambiguous for this Intermediary name.
     */
    String mapMemberName(CompatibilityScanner.SymbolKind kind, String owner, String name) {
        MemberOwnerNameKey ownerKey = new MemberOwnerNameKey(kind, owner, name);
        if (!ambiguousMemberNamesByOwnerAndName.contains(ownerKey)) {
            String direct = memberNamesByOwnerAndName.get(ownerKey);
            if (direct != null) {
                return direct;
            }
        }

        MemberNameKey nameKey = new MemberNameKey(kind, name);
        if (!ambiguousMemberNamesByName.contains(nameKey)) {
            return memberNamesByName.getOrDefault(nameKey, name);
        }
        return name;
    }

    String mapFieldName(String owner, String name, String descriptor) {
        return normalize(new CompatibilityScanner.Reference(
                CompatibilityScanner.SymbolKind.FIELD, owner, name, descriptor)).name();
    }

    String mapDescriptor(String descriptor) {
        return normalizeDescriptor(descriptor);
    }

    int classCount() {
        return intermediaryToNamedClasses.size();
    }

    int memberCount() {
        return intermediaryToNamedMembers.size();
    }

    String normalizeDescriptor(String descriptor) {
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
                    mapClassName(type.getInternalName()));
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

    private record MemberLookupKey(CompatibilityScanner.SymbolKind kind, String name, String descriptor) {}

    private record MappedMember(String owner, String name, String descriptor) {}

    private record MemberOwnerNameKey(CompatibilityScanner.SymbolKind kind, String owner, String name) {}

    private record MemberNameKey(CompatibilityScanner.SymbolKind kind, String name) {}

}
