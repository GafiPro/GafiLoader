package dev.gafipro.gafiloader;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.mappingio.tree.MappingTree;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads Mojang's ProGuard-format mappings for an obfuscated Minecraft release.
 *
 * <p>Mojang mappings use human-readable names as the source namespace and the
 * game's obfuscated names as the destination namespace. This class provides the
 * reverse lookup needed to normalize a 1.21.11 game JAR into names comparable
 * with the unobfuscated 26.2 JAR.</p>
 */
final class MojangMappings {
    private final Map<String, String> obfuscatedToNamedClasses;
    private final Map<String, String> namedToObfuscatedClasses;
    private final Map<MemberKey, String> obfuscatedToNamedMembers;
    private final String sourceNamespace;
    private final String obfuscatedNamespace;

    private MojangMappings(Map<String, String> obfuscatedToNamedClasses,
                           Map<String, String> namedToObfuscatedClasses,
                           Map<MemberKey, String> obfuscatedToNamedMembers,
                           String sourceNamespace,
                           String obfuscatedNamespace) {
        this.obfuscatedToNamedClasses = Map.copyOf(obfuscatedToNamedClasses);
        this.namedToObfuscatedClasses = Map.copyOf(namedToObfuscatedClasses);
        this.obfuscatedToNamedMembers = Map.copyOf(obfuscatedToNamedMembers);
        this.sourceNamespace = sourceNamespace;
        this.obfuscatedNamespace = obfuscatedNamespace;
    }

    static MojangMappings load(Path mappingFile) throws IOException {
        if (mappingFile == null || !Files.isRegularFile(mappingFile)) {
            throw new IllegalArgumentException("Mojang mappings file does not exist: " + mappingFile);
        }

        MemoryMappingTree tree = new MemoryMappingTree();
        // Mojang's client mappings append obfuscated line ranges after method descriptors,
        // while Mapping-IO's ProGuard reader does not accept that suffix on ordinary members.
        // Normalize only those line-number suffixes; retain all names and descriptors unchanged.
        String mappingText = normalizeMethodLineNumberSuffixes(Files.readString(mappingFile));
        MappingReader.read(new StringReader(mappingText), MappingFormat.PROGUARD_FILE, tree);
        if (tree.getClasses().isEmpty() || tree.getDstNamespaces().isEmpty()) {
            throw new IOException("No class mappings were read from " + mappingFile);
        }

        String sourceNamespace = tree.getSrcNamespace();
        String obfuscatedNamespace = tree.getDstNamespaces().getFirst();
        int obfuscatedNamespaceId = tree.getNamespaceId(obfuscatedNamespace);

        Map<String, String> obfuscatedToNamedClasses = new HashMap<>();
        Map<String, String> namedToObfuscatedClasses = new HashMap<>();
        for (MappingTree.ClassMapping classMapping : tree.getClasses()) {
            String namedName = classMapping.getSrcName();
            String obfuscatedName = classMapping.getDstName(obfuscatedNamespaceId);
            if (namedName == null || obfuscatedName == null) {
                continue;
            }
            putConsistent(obfuscatedToNamedClasses, obfuscatedName, namedName, mappingFile);
            putConsistent(namedToObfuscatedClasses, namedName, obfuscatedName, mappingFile);
        }

        Map<MemberKey, String> obfuscatedToNamedMembers = new HashMap<>();
        for (MappingTree.ClassMapping classMapping : tree.getClasses()) {
            String obfuscatedOwner = classMapping.getDstName(obfuscatedNamespaceId);
            if (obfuscatedOwner == null) {
                continue;
            }

            for (MappingTree.FieldMapping field : classMapping.getFields()) {
                String obfuscatedName = field.getDstName(obfuscatedNamespaceId);
                String namedName = field.getSrcName();
                String namedDescriptor = field.getSrcDesc();
                if (obfuscatedName != null && namedName != null && namedDescriptor != null) {
                    putMemberConsistent(obfuscatedToNamedMembers,
                            new MemberKey(obfuscatedOwner, SymbolKind.FIELD, obfuscatedName, namedDescriptor),
                            namedName, mappingFile);
                }
            }

            for (MappingTree.MethodMapping method : classMapping.getMethods()) {
                String obfuscatedName = method.getDstName(obfuscatedNamespaceId);
                String namedName = method.getSrcName();
                String namedDescriptor = method.getSrcDesc();
                if (obfuscatedName != null && namedName != null && namedDescriptor != null) {
                    putMemberConsistent(obfuscatedToNamedMembers,
                            new MemberKey(obfuscatedOwner, SymbolKind.METHOD, obfuscatedName, namedDescriptor),
                            namedName, mappingFile);
                }
            }
        }

        return new MojangMappings(obfuscatedToNamedClasses, namedToObfuscatedClasses,
                obfuscatedToNamedMembers, sourceNamespace, obfuscatedNamespace);
    }

    private static String normalizeMethodLineNumberSuffixes(String content) {
        StringBuilder normalized = new StringBuilder(content.length());
        for (String line : content.split("\\R", -1)) {
            int arrow = line.indexOf(" -> ");
            if (arrow > 0) {
                String declaration = line.substring(0, arrow);
                int open = declaration.indexOf('(');
                int close = declaration.lastIndexOf(')');
                if (open >= 0 && close > open && close + 1 < declaration.length()) {
                    String suffix = declaration.substring(close + 1);
                    if (suffix.matches("(?::[0-9]+){1,2}")) {
                        line = declaration.substring(0, close + 1) + line.substring(arrow);
                    }
                }
            }
            normalized.append(line).append('\\n');
        }
        return normalized.toString();
    }

    String toNamedClass(String obfuscatedInternalName) {
        return obfuscatedToNamedClasses.getOrDefault(obfuscatedInternalName, obfuscatedInternalName);
    }

    String toNamedDescriptor(String obfuscatedDescriptor) {
        return mapDescriptor(obfuscatedDescriptor, obfuscatedToNamedClasses);
    }

    String toObfuscatedDescriptor(String namedDescriptor) {
        return mapDescriptor(namedDescriptor, namedToObfuscatedClasses);
    }

    String toNamedMember(String obfuscatedOwner, SymbolKind kind, String obfuscatedName,
                         String namedDescriptor) {
        return obfuscatedToNamedMembers.getOrDefault(
                new MemberKey(obfuscatedOwner, kind, obfuscatedName, namedDescriptor), obfuscatedName);
    }

    int mappedClassCount() {
        return obfuscatedToNamedClasses.size();
    }

    int mappedMemberCount() {
        return obfuscatedToNamedMembers.size();
    }

    String sourceNamespace() {
        return sourceNamespace;
    }

    String obfuscatedNamespace() {
        return obfuscatedNamespace;
    }

    private static String mapDescriptor(String descriptor, Map<String, String> classNameMap) {
        if (descriptor == null || descriptor.isEmpty()) {
            return descriptor;
        }

        if (descriptor.charAt(0) == '(') {
            Type[] oldArguments = Type.getArgumentTypes(descriptor);
            Type[] mappedArguments = new Type[oldArguments.length];
            for (int i = 0; i < oldArguments.length; i++) {
                mappedArguments[i] = mapType(oldArguments[i], classNameMap);
            }
            Type mappedReturn = mapType(Type.getReturnType(descriptor), classNameMap);
            return Type.getMethodDescriptor(mappedReturn, mappedArguments);
        }

        return mapType(Type.getType(descriptor), classNameMap).getDescriptor();
    }

    private static Type mapType(Type type, Map<String, String> classNameMap) {
        return switch (type.getSort()) {
            case Type.OBJECT -> Type.getObjectType(
                    classNameMap.getOrDefault(type.getInternalName(), type.getInternalName()));
            case Type.ARRAY -> {
                Type mappedElement = mapType(type.getElementType(), classNameMap);
                yield Type.getType("[".repeat(type.getDimensions()) + mappedElement.getDescriptor());
            }
            default -> type;
        };
    }

    private static void putConsistent(Map<String, String> values, String key, String value,
                                      Path file) throws IOException {
        String oldValue = values.putIfAbsent(key, value);
        if (oldValue != null && !oldValue.equals(value)) {
            throw new IOException("Conflicting class mappings for " + key + " in " + file);
        }
    }

    private static void putMemberConsistent(Map<MemberKey, String> values, MemberKey key, String value,
                                            Path file) throws IOException {
        String oldValue = values.putIfAbsent(key, value);
        if (oldValue != null && !oldValue.equals(value)) {
            throw new IOException("Conflicting member mappings for " + key + " in " + file);
        }
    }

    record MemberKey(String owner, SymbolKind kind, String name, String namedDescriptor) {}

    enum SymbolKind {
        METHOD,
        FIELD
    }
}
