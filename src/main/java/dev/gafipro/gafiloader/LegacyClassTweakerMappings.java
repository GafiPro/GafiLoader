package dev.gafipro.gafiloader;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Maps 1.21.11 Intermediary ClassTweaker references into Mojang's official
 * (named) namespace. The output namespace matches the unobfuscated 26.2 runtime.
 */
final class LegacyClassTweakerMappings {
    private static final String TINY_PATH = "mappings/mappings.tiny";

    private final Map<String, String> classes;
    private final Map<MemberKey, MappedMember> members;
    private final Map<MemberSignature, Set<MemberName>> membersBySignature;

    private LegacyClassTweakerMappings(Map<String, String> classes,
                                       Map<MemberKey, MappedMember> members,
                                       Map<MemberSignature, Set<MemberName>> membersBySignature) {
        this.classes = Map.copyOf(classes);
        this.members = Map.copyOf(members);
        Map<MemberSignature, Set<MemberName>> immutableSignatures = new HashMap<>();
        membersBySignature.forEach((signature, names) ->
                immutableSignatures.put(signature, Set.copyOf(names)));
        this.membersBySignature = Map.copyOf(immutableSignatures);
    }

    static LegacyClassTweakerMappings load(Path intermediaryJar, Path mojangMappingsFile)
            throws IOException {
        MojangMappings mojang = MojangMappings.load(mojangMappingsFile);
        MemoryMappingTree tree = new MemoryMappingTree();
        try (ZipFile zip = new ZipFile(intermediaryJar.toFile())) {
            ZipEntry entry = zip.getEntry(TINY_PATH);
            if (entry == null) {
                throw new IOException("Intermediary mapping archive has no " + TINY_PATH + ".");
            }
            try (InputStreamReader reader = new InputStreamReader(
                    zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                MappingReader.read(reader, MappingFormat.TINY_2_FILE, tree);
            }
        }

        int officialId = tree.getNamespaceId("official");
        int intermediaryId = tree.getNamespaceId("intermediary");
        if (officialId == MappingTreeView.NULL_NAMESPACE_ID
                || intermediaryId == MappingTreeView.NULL_NAMESPACE_ID
                || tree.getClasses().isEmpty()) {
            throw new IOException("1.21.11 Tiny mappings must contain official and intermediary namespaces.");
        }

        Map<String, String> classes = new HashMap<>();
        Map<MemberKey, MappedMember> members = new HashMap<>();
        Map<MemberSignature, Set<MemberName>> membersBySignature = new HashMap<>();
        for (MappingTree.ClassMapping classMapping : tree.getClasses()) {
            String officialOwner = classMapping.getName(officialId);
            String intermediaryOwner = classMapping.getName(intermediaryId);
            if (officialOwner == null || intermediaryOwner == null) continue;

            String namedOwner = mojang.toNamedClass(officialOwner);
            putConsistent(classes, intermediaryOwner, namedOwner,
                    "class " + intermediaryOwner);

            for (MappingTree.FieldMapping field : classMapping.getFields()) {
                String oldName = field.getName(officialId);
                String oldIntermediaryName = field.getName(intermediaryId);
                String oldDescriptor = field.getDesc(officialId);
                String intermediaryDescriptor = field.getDesc(intermediaryId);
                if (oldName == null || oldIntermediaryName == null
                        || oldDescriptor == null || intermediaryDescriptor == null) continue;

                String namedDescriptor = mojang.toNamedDescriptor(oldDescriptor);
                String namedName = mojang.toNamedMember(officialOwner,
                        MojangMappings.SymbolKind.FIELD, oldName, namedDescriptor);
                putConsistent(members,
                        new MemberKey(intermediaryOwner, MemberKind.FIELD,
                                oldIntermediaryName, intermediaryDescriptor),
                        new MappedMember(namedOwner, namedName, namedDescriptor),
                        "field " + intermediaryOwner + "#" + oldIntermediaryName);
                addSignatureMapping(membersBySignature, MemberKind.FIELD,
                        oldIntermediaryName, intermediaryDescriptor, namedName, namedDescriptor);
            }

            for (MappingTree.MethodMapping method : classMapping.getMethods()) {
                String oldName = method.getName(officialId);
                String oldIntermediaryName = method.getName(intermediaryId);
                String oldDescriptor = method.getDesc(officialId);
                String intermediaryDescriptor = method.getDesc(intermediaryId);
                if (oldName == null || oldIntermediaryName == null
                        || oldDescriptor == null || intermediaryDescriptor == null) continue;

                String namedDescriptor = mojang.toNamedDescriptor(oldDescriptor);
                String namedName = mojang.toNamedMember(officialOwner,
                        MojangMappings.SymbolKind.METHOD, oldName, namedDescriptor);
                putConsistent(members,
                        new MemberKey(intermediaryOwner, MemberKind.METHOD,
                                oldIntermediaryName, intermediaryDescriptor),
                        new MappedMember(namedOwner, namedName, namedDescriptor),
                        "method " + intermediaryOwner + "#" + oldIntermediaryName);
                addSignatureMapping(membersBySignature, MemberKind.METHOD,
                        oldIntermediaryName, intermediaryDescriptor, namedName, namedDescriptor);
            }
        }
        return new LegacyClassTweakerMappings(classes, members, membersBySignature);
    }

    String mapClass(String internalName) throws IOException {
        String mapped = classes.get(internalName);
        if (mapped != null) return mapped;
        if (internalName.startsWith("net/minecraft/class_")) {
            throw new IOException("No 1.21.11 Intermediary mapping exists for ClassTweaker class "
                    + internalName + ".");
        }
        return internalName;
    }

    String mapInterfaceName(String value) throws IOException {
        int genericStart = value.indexOf('<');
        String base = genericStart >= 0 ? value.substring(0, genericStart) : value;
        String suffix = genericStart >= 0 ? value.substring(genericStart) : "";
        return mapClass(base) + suffix;
    }

    MappedMember mapMember(String owner, String name, String descriptor, boolean method)
            throws IOException {
        MemberKind kind = method ? MemberKind.METHOD : MemberKind.FIELD;
        MappedMember mapped = members.get(new MemberKey(owner, kind, name, descriptor));
        if (mapped != null) {
            return mapped;
        }

        // JVM constructor and class-initializer names are fixed by the class-file format.
        // Intermediary mapping files may omit them because their names never change. Their
        // descriptors still contain Intermediary class names and must be remapped.
        if (method && (name.equals("<init>") || name.equals("<clinit>"))) {
            return new MappedMember(mapClass(owner), name, mapDescriptor(descriptor));
        }

        // ClassTweaker may name a concrete class even when the target method is declared
        // by an implemented interface or a superclass. Intermediary assigns stable member
        // IDs, so use the complete member signature as a fallback while retaining the
        // requested owner. Never guess when that signature has conflicting named targets.
        Set<MemberName> candidates = membersBySignature.get(
                new MemberSignature(kind, name, descriptor));
        if (candidates != null && candidates.size() == 1) {
            MemberName candidate = candidates.iterator().next();
            return new MappedMember(mapClass(owner), candidate.name(), candidate.descriptor());
        }
        if (candidates != null && candidates.size() > 1) {
            throw new IOException("Ambiguous 1.21.11 Intermediary mapping for ClassTweaker "
                    + (method ? "method " : "field ") + owner + "#" + name + descriptor
                    + " across " + candidates.size() + " distinct named targets; refusing to guess.");
        }

        throw new IOException("No 1.21.11 Intermediary mapping exists for ClassTweaker "
                + (method ? "method " : "field ") + owner + "#" + name + descriptor + ".");
    }

    /**
     * Maps Intermediary member IDs referenced by legacy bytecode. Non-Minecraft members
     * and already named Java/library methods are intentionally preserved.
     */
    String mapBytecodeMemberName(String owner, String name, String descriptor, boolean method)
            throws IOException {
        if (owner == null || !owner.startsWith("net/minecraft/class_")) return name;
        String prefix = method ? "method_" : "field_";
        if (name == null || !name.startsWith(prefix)) return name;
        return mapMember(owner, name, descriptor, method).name();
    }

    String mapDescriptor(String descriptor) throws IOException {
        if (descriptor == null || descriptor.isBlank()) return descriptor;
        try {
            Type type = descriptor.startsWith("(")
                    ? Type.getMethodType(descriptor) : Type.getType(descriptor);
            return mapType(type).getDescriptor();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid ClassTweaker bytecode descriptor: " + descriptor, exception);
        }
    }

    private Type mapType(Type type) throws IOException {
        return switch (type.getSort()) {
            case Type.OBJECT -> Type.getObjectType(mapClass(type.getInternalName()));
            case Type.ARRAY -> {
                Type element = mapType(type.getElementType());
                yield Type.getType("[".repeat(type.getDimensions()) + element.getDescriptor());
            }
            case Type.METHOD -> {
                Type[] arguments = type.getArgumentTypes();
                for (int i = 0; i < arguments.length; i++) arguments[i] = mapType(arguments[i]);
                yield Type.getMethodType(Type.getMethodDescriptor(mapType(type.getReturnType()), arguments));
            }
            default -> type;
        };
    }

    private static void addSignatureMapping(Map<MemberSignature, Set<MemberName>> target,
                                             MemberKind kind, String intermediaryName,
                                             String intermediaryDescriptor, String namedName,
                                             String namedDescriptor) {
        MemberSignature signature = new MemberSignature(kind, intermediaryName, intermediaryDescriptor);
        target.computeIfAbsent(signature, ignored -> new HashSet<>())
                .add(new MemberName(namedName, namedDescriptor));
    }

    private static <K, V> void putConsistent(Map<K, V> target, K key, V value, String label)
            throws IOException {
        V old = target.putIfAbsent(key, value);
        if (old != null && !old.equals(value)) {
            throw new IOException("Conflicting 1.21.11 mappings for " + label + ".");
        }
    }

    enum MemberKind { FIELD, METHOD }

    record MappedMember(String owner, String name, String descriptor) {}

    private record MemberKey(String owner, MemberKind kind, String name, String descriptor) {}

    private record MemberSignature(MemberKind kind, String name, String descriptor) {}

    private record MemberName(String name, String descriptor) {}
}
