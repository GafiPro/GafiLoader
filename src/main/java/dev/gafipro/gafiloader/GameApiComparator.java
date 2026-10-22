package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Compares a 1.21.11 game JAR (normalized with Mojang mappings) with a 26.2 JAR.
 * This is an API-difference report, not a bytecode compatibility patcher.
 */
public final class GameApiComparator {
    public Report compare(Path legacyGameJar, Path targetGameJar, Path legacyMappingsFile)
            throws IOException {
        if (legacyGameJar == null || !Files.isRegularFile(legacyGameJar)) {
            throw new IllegalArgumentException("Legacy Minecraft JAR does not exist: " + legacyGameJar);
        }
        if (targetGameJar == null || !Files.isRegularFile(targetGameJar)) {
            throw new IllegalArgumentException("Target Minecraft 26.2 JAR does not exist: " + targetGameJar);
        }
        if (legacyMappingsFile == null || !Files.isRegularFile(legacyMappingsFile)) {
            throw new IllegalArgumentException("Official 1.21.11 Mojang mappings file does not exist: "
                    + legacyMappingsFile);
        }

        MojangMappings mappings = MojangMappings.load(legacyMappingsFile);
        Map<String, ApiClass> legacyClasses = indexGame(legacyGameJar, mappings);
        Map<String, ApiClass> targetClasses = indexGame(targetGameJar, null);

        List<Difference> differences = new ArrayList<>();
        for (ApiClass legacyClass : legacyClasses.values()) {
            ApiClass targetClass = targetClasses.get(legacyClass.name());
            if (targetClass == null) {
                differences.add(new Difference(
                        "MISSING_CLASS", legacyClass.name(), null, null, List.of(),
                        "The legacy class has no class with the same Mojang name in the target game JAR."));
                continue;
            }

            compareMembers(legacyClass, targetClass, legacyClasses, targetClasses,
                    MojangMappings.SymbolKind.METHOD, differences);
            compareMembers(legacyClass, targetClass, legacyClasses, targetClasses,
                    MojangMappings.SymbolKind.FIELD, differences);
        }

        differences = differences.stream()
                .distinct()
                .sorted(Comparator.comparing(Difference::kind)
                        .thenComparing(Difference::owner)
                        .thenComparing(d -> nullToEmpty(d.name()))
                        .thenComparing(d -> nullToEmpty(d.descriptor())))
                .toList();

        int missingClasses = count(differences, "MISSING_CLASS");
        int missingMethods = count(differences, "MISSING_METHOD");
        int missingFields = count(differences, "MISSING_FIELD");
        int renamedCandidates = countSuffix(differences, "_NAME_CHANGED_CANDIDATE");
        int descriptorCandidates = countSuffix(differences, "_DESCRIPTOR_CHANGED_CANDIDATE");

        return new Report(
                legacyGameJar.toAbsolutePath().normalize().toString(),
                targetGameJar.toAbsolutePath().normalize().toString(),
                legacyMappingsFile.toAbsolutePath().normalize().toString(),
                mappings.mappedClassCount(),
                mappings.mappedMemberCount(),
                legacyClasses.size(),
                targetClasses.size(),
                new Summary(differences.size(), missingClasses, missingMethods, missingFields,
                        renamedCandidates, descriptorCandidates),
                differences);
    }

    private static Map<String, ApiClass> indexGame(Path gameJar, MojangMappings mappings)
            throws IOException {
        Map<String, ApiClass> classes = new HashMap<>();
        try (JarFile jar = new JarFile(gameJar.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/versions/")) {
                    continue;
                }

                try (InputStream input = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(input);
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        private String originalName;
                        private String normalizedName;
                        private String superName;
                        private final Set<Member> methods = new LinkedHashSet<>();
                        private final Set<Member> fields = new LinkedHashSet<>();

                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superName, String[] interfaces) {
                            originalName = name;
                            normalizedName = mappings == null ? name : mappings.toNamedClass(name);
                            this.superName = superName == null ? null
                                    : mappings == null ? superName : mappings.toNamedClass(superName);
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor,
                                                       String signature, Object value) {
                            String normalizedDescriptor = mappings == null
                                    ? descriptor : mappings.toNamedDescriptor(descriptor);
                            String normalizedMemberName = mappings == null ? name
                                    : mappings.toNamedMember(originalName, MojangMappings.SymbolKind.FIELD,
                                            name, normalizedDescriptor);
                            fields.add(new Member(normalizedMemberName, normalizedDescriptor, access));
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            String normalizedDescriptor = mappings == null
                                    ? descriptor : mappings.toNamedDescriptor(descriptor);
                            String normalizedMemberName = mappings == null ? name
                                    : mappings.toNamedMember(originalName, MojangMappings.SymbolKind.METHOD,
                                            name, normalizedDescriptor);
                            methods.add(new Member(normalizedMemberName, normalizedDescriptor, access));
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            ApiClass value = new ApiClass(normalizedName, superName,
                                    Set.copyOf(fields), Set.copyOf(methods));
                            ApiClass previous = classes.putIfAbsent(normalizedName, value);
                            if (previous != null && !previous.equals(value)) {
                                throw new DuplicateClassException(normalizedName);
                            }
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (DuplicateClassException exception) {
                    throw new IOException("Multiple classes normalize to " + exception.className
                            + " in " + gameJar + "; check the supplied Mojang mappings.", exception);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not index class " + entry.getName()
                            + " in " + gameJar, exception);
                }
            }
        }

        if (classes.isEmpty()) {
            throw new IOException("No class files were found in game JAR: " + gameJar);
        }
        return classes;
    }

    private static void compareMembers(ApiClass legacyClass, ApiClass targetClass,
                                      Map<String, ApiClass> legacyClasses,
                                      Map<String, ApiClass> targetClasses,
                                      MojangMappings.SymbolKind kind,
                                      List<Difference> differences) {
        Set<Member> oldMembers = kind == MojangMappings.SymbolKind.METHOD
                ? legacyClass.methods() : legacyClass.fields();
        Set<Member> exactTargetMembers = resolveMembers(targetClass.name(), kind, targetClasses, new HashSet<>());

        for (Member oldMember : oldMembers) {
            // This report models the callable game API surface. Private/package-private
            // implementation details in vanilla are not generally linkable from ordinary mods;
            // direct references inside real mod bytecode are handled separately by CompatibilityScanner.
            if ((oldMember.access() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
                continue;
            }

            // Constructors and class initializers are important bytecode details, but they do not
            // normally represent public compatibility promises. Report them only when missing.
            Member sameSignature = exactTargetMembers.stream()
                    .filter(candidate -> candidate.name().equals(oldMember.name())
                            && candidate.descriptor().equals(oldMember.descriptor()))
                    .findFirst().orElse(null);
            if (sameSignature != null) {
                if (isStatic(sameSignature) != isStatic(oldMember)) {
                    String changedKind = kind == MojangMappings.SymbolKind.METHOD
                            ? "METHOD_DISPATCH_CHANGED" : "FIELD_STORAGE_KIND_CHANGED";
                    differences.add(new Difference(changedKind, legacyClass.name(), oldMember.name(),
                            oldMember.descriptor(), List.of(),
                            "The name and descriptor still exist, but static/instance semantics changed. "
                                    + "A name alias cannot safely adapt this difference."));
                }
                continue;
            }

            List<Member> sameDescriptorMembers = exactTargetMembers.stream()
                    .filter(candidate -> candidate.descriptor().equals(oldMember.descriptor()))
                    .filter(candidate -> !candidate.name().equals(oldMember.name()))
                    .toList();
            List<String> sameDispatchNames = sameDescriptorMembers.stream()
                    .filter(candidate -> isStatic(candidate) == isStatic(oldMember))
                    .map(Member::name)
                    .distinct().sorted().toList();
            if (!sameDispatchNames.isEmpty()) {
                String suffix = kind == MojangMappings.SymbolKind.METHOD
                        ? "METHOD_NAME_CHANGED_CANDIDATE" : "FIELD_NAME_CHANGED_CANDIDATE";
                differences.add(new Difference(suffix, legacyClass.name(), oldMember.name(),
                        oldMember.descriptor(), sameDispatchNames,
                        "Same descriptor and static/instance kind exist under another target name. "
                                + "This is a candidate alias, not proof of semantic equivalence."));
                continue;
            }
            if (!sameDescriptorMembers.isEmpty()) {
                String changedKind = kind == MojangMappings.SymbolKind.METHOD
                        ? "METHOD_DISPATCH_CHANGED_CANDIDATE" : "FIELD_STORAGE_KIND_CHANGED_CANDIDATE";
                List<String> candidates = sameDescriptorMembers.stream().map(Member::name).distinct().sorted().toList();
                differences.add(new Difference(changedKind, legacyClass.name(), oldMember.name(),
                        oldMember.descriptor(), candidates,
                        "Same descriptor names were found, but only with a different static/instance kind. "
                                + "Do not generate an alias from this candidate."));
                continue;
            }

            List<String> sameNameDifferentDescriptor = exactTargetMembers.stream()
                    .filter(candidate -> candidate.name().equals(oldMember.name()))
                    .map(Member::descriptor)
                    .filter(descriptor -> !descriptor.equals(oldMember.descriptor()))
                    .distinct().sorted().toList();
            if (!sameNameDifferentDescriptor.isEmpty()) {
                String suffix = kind == MojangMappings.SymbolKind.METHOD
                        ? "METHOD_DESCRIPTOR_CHANGED_CANDIDATE" : "FIELD_DESCRIPTOR_CHANGED_CANDIDATE";
                differences.add(new Difference(suffix, legacyClass.name(), oldMember.name(),
                        oldMember.descriptor(), sameNameDifferentDescriptor,
                        "The name remains present with a different descriptor; review the semantic change before adapting callers."));
                continue;
            }

            if (kind == MojangMappings.SymbolKind.METHOD
                    && hasVerifiedStructuralBridge(legacyClass.name(), oldMember, targetClasses)) {
                continue;
            }

            String missingKind = kind == MojangMappings.SymbolKind.METHOD ? "MISSING_METHOD" : "MISSING_FIELD";
            differences.add(new Difference(missingKind, legacyClass.name(), oldMember.name(),
                    oldMember.descriptor(), List.of(),
                    "No equivalent name/descriptor was found in the target class or its superclass chain."));
        }
    }

    private static Set<Member> resolveMembers(String owner, MojangMappings.SymbolKind kind,
                                              Map<String, ApiClass> classes, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return Set.of();
        }
        ApiClass classInfo = classes.get(owner);
        if (classInfo == null) {
            return Set.of();
        }

        Set<Member> members = new LinkedHashSet<>(
                kind == MojangMappings.SymbolKind.METHOD ? classInfo.methods() : classInfo.fields());
        members.addAll(resolveMembers(classInfo.superName(), kind, classes, visited));
        return members;
    }

    private static boolean hasVerifiedStructuralBridge(String owner, Member oldMember,
                                                         Map<String, ApiClass> targetClasses) {
        String displaySlot = "net/minecraft/world/scores/DisplaySlot";
        String chatFormatting = "net/minecraft/ChatFormatting";
        String screenBridge = "(Lnet/minecraft/ChatFormatting;)Lnet/minecraft/world/scores/DisplaySlot;";
        if (owner.equals(displaySlot) && oldMember.name().equals("teamColorToSlot")
                && oldMember.descriptor().equals(screenBridge) && isStatic(oldMember)) {
            ApiClass targetSlot = targetClasses.get(displaySlot);
            ApiClass targetFormatting = targetClasses.get(chatFormatting);
            if (targetSlot == null || targetFormatting == null) {
                return false;
            }
            for (String color : List.of("BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA",
                    "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY", "DARK_GRAY", "BLUE",
                    "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE")) {
                Member slotField = findMember(targetSlot, MojangMappings.SymbolKind.FIELD,
                        "TEAM_" + color, "L" + displaySlot + ";");
                Member formattingField = findMember(targetFormatting, MojangMappings.SymbolKind.FIELD,
                        color, "L" + chatFormatting + ";");
                if (slotField == null || (slotField.access() & Opcodes.ACC_STATIC) == 0
                        || formattingField == null || (formattingField.access() & Opcodes.ACC_STATIC) == 0) {
                    return false;
                }
            }
            return true;
        }

        String readOnlyScoreInfo = "net/minecraft/world/scores/ReadOnlyScoreInfo";
        String numberFormat = "net/minecraft/network/chat/numbers/NumberFormat";
        String mutableComponent = "net/minecraft/network/chat/MutableComponent";
        String commonComponents = "net/minecraft/network/chat/CommonComponents";
        String safeDescriptor = "(L" + readOnlyScoreInfo + ";L" + numberFormat + ";)L"
                + mutableComponent + ";";
        String formatDescriptor = "(L" + numberFormat + ";)L" + mutableComponent + ";";
        if (owner.equals(readOnlyScoreInfo) && oldMember.name().equals("safeFormatValue")
                && oldMember.descriptor().equals(safeDescriptor) && isStatic(oldMember)) {
            ApiClass targetScore = targetClasses.get(readOnlyScoreInfo);
            ApiClass targetCommon = targetClasses.get(commonComponents);
            if (targetScore == null || targetCommon == null) {
                return false;
            }
            Member format = findMember(targetScore, MojangMappings.SymbolKind.METHOD,
                    "formatValue", formatDescriptor);
            Member empty = findMember(targetCommon, MojangMappings.SymbolKind.FIELD,
                    "EMPTY", "L" + mutableComponent + ";");
            return format != null
                    // Interface implementations may be supplied by concrete score-info records.
                    && (format.access() & Opcodes.ACC_STATIC) == 0
                    && empty != null && (empty.access() & Opcodes.ACC_STATIC) != 0;
        }
        return false;
    }

    private static Member findMember(ApiClass owner, MojangMappings.SymbolKind kind,
                                     String name, String descriptor) {
        Set<Member> members = kind == MojangMappings.SymbolKind.METHOD ? owner.methods() : owner.fields();
        return members.stream().filter(member -> member.name().equals(name)
                && member.descriptor().equals(descriptor)).findFirst().orElse(null);
    }

    private static boolean isStatic(Member member) {
        return (member.access() & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean isPrivateSyntheticLambda(Member member) {
        return (member.access() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                == (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)
                && member.name().startsWith("lambda$");
    }

    private static int count(List<Difference> differences, String kind) {
        return (int) differences.stream().filter(difference -> difference.kind().equals(kind)).count();
    }

    private static int countSuffix(List<Difference> differences, String suffix) {
        return (int) differences.stream().filter(difference -> difference.kind().endsWith(suffix)).count();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ApiClass(String name, String superName, Set<Member> fields, Set<Member> methods) {}
    private record Member(String name, String descriptor, int access) {}

    private static final class DuplicateClassException extends RuntimeException {
        private final String className;

        private DuplicateClassException(String className) {
            this.className = className;
        }
    }

    public record Difference(String kind, String owner, String name, String descriptor,
                             List<String> candidates, String detail) {}

    public record Summary(int differences, int missingClasses, int missingMethods, int missingFields,
                          int renamedCandidates, int descriptorChangedCandidates) {}

    public record Report(String legacyGameJar, String targetGameJar, String legacyMappingsFile,
                         int legacyClassesMapped, int legacyMembersMapped,
                         int legacyClassesIndexed, int targetClassesIndexed,
                         Summary summary, List<Difference> differences) {}
}
