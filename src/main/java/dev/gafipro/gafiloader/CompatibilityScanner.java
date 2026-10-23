package dev.gafipro.gafiloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
 * Compares direct Minecraft API references in mod bytecode with a target game JAR.
 *
 * <p>This is a diagnostic tool, not a runtime compatibility transformer. It deliberately
 * reports unresolved symbols rather than guessing how changed APIs should behave.</p>
 */
public final class CompatibilityScanner {
    private static final String MINECRAFT_PACKAGE = "net/minecraft/";

    public Report scan(Path gameJar, Collection<Path> modJars) throws IOException {
        return scan(gameJar, modJars, null, null, null);
    }

    public Report scan(Path gameJar, Collection<Path> modJars, Path aliasesFile) throws IOException {
        return scan(gameJar, modJars, aliasesFile, null, null);
    }

    /**
     * Scans mods and optionally normalizes production Intermediary names through the 1.21.11
     * official mappings before comparing references with the unobfuscated 26.2 target.
     */
    public Report scan(Path gameJar, Collection<Path> modJars, Path aliasesFile,
                       Path intermediaryMappingsFile, Path legacyMojangMappingsFile) throws IOException {
        AliasSet aliases = aliasesFile == null ? AliasSet.empty() : AliasSet.load(aliasesFile);
        IntermediaryMappings intermediaryMappings = null;
        if (intermediaryMappingsFile != null) {
            if (legacyMojangMappingsFile == null) {
                throw new IllegalArgumentException(
                        "Official 1.21.11 Mojang mappings are required with --intermediary-mappings.");
            }
            MojangMappings mojangMappings = MojangMappings.load(legacyMojangMappingsFile);
            intermediaryMappings = IntermediaryMappings.load(intermediaryMappingsFile, mojangMappings);
        }
        return scanInternal(gameJar, modJars, aliases, intermediaryMappings);
    }

    private Report scanInternal(Path gameJar, Collection<Path> modJars, AliasSet aliases,
                                IntermediaryMappings intermediaryMappings) throws IOException {
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            throw new IllegalArgumentException("The target game JAR does not exist: " + gameJar);
        }
        if (modJars == null || modJars.isEmpty()) {
            throw new IllegalArgumentException("No mod JARs were provided.");
        }

        Map<String, ClassInfo> gameClasses = indexGame(gameJar);
        List<Path> orderedMods = modJars.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        List<Finding> findings = new ArrayList<>();
        int scannedClasses = 0;
        int inspectedReferences = 0;

        for (Path modJar : orderedMods) {
            if (!Files.isRegularFile(modJar)) {
                throw new IllegalArgumentException("Mod JAR does not exist: " + modJar);
            }

            ModScan scan = scanMod(modJar);
            scannedClasses += scan.classCount();
            inspectedReferences += scan.references().size();

            for (Reference sourceReference : scan.references()) {
                Reference normalized = intermediaryMappings == null
                        ? sourceReference : intermediaryMappings.normalize(sourceReference);
                Reference reference = aliases.normalize(normalized);
                if (!reference.owner().startsWith(MINECRAFT_PACKAGE)) {
                    continue;
                }

                ClassInfo targetClass = gameClasses.get(reference.owner());
                if (reference.kind() == SymbolKind.CLASS) {
                    if (targetClass == null) {
                        findings.add(new Finding(
                                modJar.toString(), "MISSING_CLASS", reference.owner(), null, null,
                                "The referenced Minecraft class is absent from the target JAR."));
                    }
                    continue;
                }

                // A missing owner is reported once as a missing class; member-level diagnostics
                // would only duplicate the same underlying problem.
                if (targetClass == null) {
                    continue;
                }

                Member member = new Member(reference.name(), reference.descriptor());
                if (reference.kind() == SymbolKind.METHOD
                        && !memberExists(gameClasses, reference.owner(), member, SymbolKind.METHOD, new HashSet<>())
                        && !hasStructuralAdapter(reference, gameClasses)) {
                    findings.add(new Finding(
                            modJar.toString(), "MISSING_METHOD", reference.owner(), reference.name(),
                            reference.descriptor(),
                            "The target class and its hierarchy do not declare the exact method name/descriptor."));
                } else if (reference.kind() == SymbolKind.FIELD
                        && !memberExists(gameClasses, reference.owner(), member, SymbolKind.FIELD, new HashSet<>())) {
                    findings.add(new Finding(
                            modJar.toString(), "MISSING_FIELD", reference.owner(), reference.name(),
                            reference.descriptor(),
                            "The target class and its hierarchy do not declare the exact field name/descriptor."));
                }
            }
        }

        findings = findings.stream()
                .distinct()
                .sorted(Comparator.comparing(Finding::modJar)
                        .thenComparing(Finding::kind)
                        .thenComparing(Finding::owner)
                        .thenComparing(f -> nullToEmpty(f.name()))
                        .thenComparing(f -> nullToEmpty(f.descriptor())))
                .toList();

        int missingClasses = countKind(findings, "MISSING_CLASS");
        int missingMethods = countKind(findings, "MISSING_METHOD");
        int missingFields = countKind(findings, "MISSING_FIELD");
        Summary summary = new Summary(orderedMods.size(), scannedClasses, inspectedReferences,
                gameClasses.size(), findings.size(), missingClasses, missingMethods, missingFields,
                aliases.classAliasCount(), aliases.memberAliasCount(),
                intermediaryMappings == null ? 0 : intermediaryMappings.classCount(),
                intermediaryMappings == null ? 0 : intermediaryMappings.memberCount());

        return new Report(
                gameJar.toAbsolutePath().normalize().toString(),
                orderedMods.stream().map(Path::toString).toList(),
                summary,
                findings);
    }

    private static boolean hasStructuralAdapter(Reference reference, Map<String, ClassInfo> gameClasses) {
        if (reference.kind() != SymbolKind.METHOD) {
            return false;
        }

        String minecraft = "net/minecraft/client/Minecraft";
        String screen = "net/minecraft/client/gui/screens/Screen";
        String gui = "net/minecraft/client/gui/Gui";
        String screenDescriptor = "(L" + screen + ";)V";
        if (reference.owner().equals(minecraft) && reference.name().equals("setScreen")
                && reference.descriptor().equals(screenDescriptor)) {
            return memberExists(gameClasses, minecraft,
                            new Member("gui", "L" + gui + ";"), SymbolKind.FIELD, new HashSet<>())
                    && memberExists(gameClasses, gui,
                            new Member("setScreen", screenDescriptor), SymbolKind.METHOD, new HashSet<>());
        }

        String displaySlot = "net/minecraft/world/scores/DisplaySlot";
        String chatFormatting = "net/minecraft/ChatFormatting";
        String teamColorDescriptor = "(L" + chatFormatting + ";)L" + displaySlot + ";";
        if (reference.owner().equals(displaySlot) && reference.name().equals("teamColorToSlot")
                && reference.descriptor().equals(teamColorDescriptor)) {
            for (String color : List.of("BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA",
                    "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY", "DARK_GRAY", "BLUE",
                    "GREEN", "AQUA", "RED", "LIGHT_PURPLE", "YELLOW", "WHITE")) {
                if (!memberExists(gameClasses, displaySlot,
                            new Member("TEAM_" + color, "L" + displaySlot + ";"), SymbolKind.FIELD, new HashSet<>())
                        || !memberExists(gameClasses, chatFormatting,
                            new Member(color, "L" + chatFormatting + ";"), SymbolKind.FIELD, new HashSet<>())) {
                    return false;
                }
            }
            return true;
        }

        String readOnlyScoreInfo = "net/minecraft/world/scores/ReadOnlyScoreInfo";
        String numberFormat = "net/minecraft/network/chat/numbers/NumberFormat";
        String mutableComponent = "net/minecraft/network/chat/MutableComponent";
        String safeDescriptor = "(L" + readOnlyScoreInfo + ";L" + numberFormat + ";)L"
                + mutableComponent + ";";
        String formatDescriptor = "(L" + numberFormat + ";)L" + mutableComponent + ";";
        String commonComponents = "net/minecraft/network/chat/CommonComponents";
        return reference.owner().equals(readOnlyScoreInfo)
                && reference.name().equals("safeFormatValue")
                && reference.descriptor().equals(safeDescriptor)
                && memberExists(gameClasses, readOnlyScoreInfo,
                        new Member("formatValue", formatDescriptor), SymbolKind.METHOD, new HashSet<>())
                && memberExists(gameClasses, commonComponents,
                        new Member("EMPTY", "L" + mutableComponent + ";"), SymbolKind.FIELD, new HashSet<>());
    }

    private static boolean memberExists(Map<String, ClassInfo> classes, String owner,
                                        Member member, SymbolKind kind, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return false;
        }
        ClassInfo classInfo = classes.get(owner);
        if (classInfo == null) {
            return false;
        }
        Set<Member> members = kind == SymbolKind.METHOD ? classInfo.methods() : classInfo.fields();
        if (members.contains(member)) {
            return true;
        }
        if (memberExists(classes, classInfo.superName(), member, kind, visited)) {
            return true;
        }
        for (String interfaceName : classInfo.interfaces()) {
            if (memberExists(classes, interfaceName, member, kind, visited)) {
                return true;
            }
        }
        return false;
    }

    private static int countKind(List<Finding> findings, String kind) {
        return (int) findings.stream().filter(finding -> finding.kind().equals(kind)).count();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, ClassInfo> indexGame(Path gameJar) throws IOException {
        Map<String, ClassInfo> classes = new HashMap<>();
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
                        private String className;
                        private String superName;
                        private List<String> interfaces = List.of();
                        private final Set<Member> fields = new HashSet<>();
                        private final Set<Member> methods = new HashSet<>();

                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superName, String[] interfaces) {
                            className = name;
                            this.superName = superName;
                            this.interfaces = interfaces == null ? List.of() : List.of(interfaces.clone());
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor,
                                                       String signature, Object value) {
                            fields.add(new Member(name, descriptor));
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            methods.add(new Member(name, descriptor));
                            return null;
                        }

                        @Override
                        public void visitEnd() {
                            classes.putIfAbsent(className,
                                    new ClassInfo(Set.copyOf(fields), Set.copyOf(methods),
                                            superName, List.copyOf(interfaces)));
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not index class " + entry.getName()
                            + " in " + gameJar, exception);
                }
            }
        }
        if (classes.isEmpty()) {
            throw new IOException("No class files were found in the target game JAR: " + gameJar);
        }
        return classes;
    }

    private ModScan scanMod(Path modJar) throws IOException {
        Set<Reference> references = new LinkedHashSet<>();
        int[] classCount = {0};
        try (JarFile jar = new JarFile(modJar.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/versions/")) {
                    continue;
                }
                classCount[0]++;
                try (InputStream input = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(input);
                    reader.accept(new ReferenceCollector(references), ClassReader.SKIP_DEBUG);
                } catch (RuntimeException exception) {
                    throw new IOException("Could not inspect class " + entry.getName()
                            + " in " + modJar, exception);
                }
            }
        }
        return new ModScan(classCount[0], Set.copyOf(references));
    }

    private static final class ReferenceCollector extends ClassVisitor {
        private final Set<Reference> references;

        private ReferenceCollector(Set<Reference> references) {
            super(Opcodes.ASM9);
            this.references = references;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            addClass(superName);
            if (interfaces != null) {
                for (String value : interfaces) {
                    addClass(value);
                }
            }
            addSignature(signature);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            addClass(owner);
            if (descriptor != null) {
                addDescriptor(descriptor);
            }
            if (name != null && owner != null && descriptor != null) {
                addMethod(owner, name, descriptor);
            }
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            addClass(name);
            addClass(outerName);
        }

        @Override
        public void visitNestHost(String nestHost) {
            addClass(nestHost);
        }

        @Override
        public void visitNestMember(String nestMember) {
            addClass(nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            addClass(permittedSubclass);
        }

        @Override
        public org.objectweb.asm.RecordComponentVisitor visitRecordComponent(
                String name, String descriptor, String signature) {
            addDescriptor(descriptor);
            addTypeSignature(signature);
            return new org.objectweb.asm.RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                                             org.objectweb.asm.TypePath typePath,
                                                             String annotationDescriptor,
                                                             boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return annotationVisitor();
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                                     org.objectweb.asm.TypePath typePath,
                                                     String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return annotationVisitor();
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            addDescriptor(descriptor);
            addTypeSignature(signature);
            if (value instanceof Type type) {
                addType(type);
            }
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                                             org.objectweb.asm.TypePath typePath,
                                                             String annotationDescriptor,
                                                             boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            addDescriptor(descriptor);
            addSignature(signature);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    addClass(exception);
                }
            }

            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                                             org.objectweb.asm.TypePath typePath,
                                                             String annotationDescriptor,
                                                             boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter,
                                                                  String annotationDescriptor,
                                                                  boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    if (type.startsWith("[")) {
                        addDescriptor(type);
                    } else {
                        addClass(type);
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName,
                                           String fieldDescriptor) {
                    addClass(owner);
                    addDescriptor(fieldDescriptor);
                    addField(owner, fieldName, fieldDescriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                                            String methodDescriptor, boolean isInterface) {
                    addClass(owner);
                    addDescriptor(methodDescriptor);
                    addMethod(owner, methodName, methodDescriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                                                   Handle bootstrapMethodHandle,
                                                   Object... bootstrapMethodArguments) {
                    addDescriptor(dynamicDescriptor);
                    addHandle(bootstrapMethodHandle);
                    for (Object argument : bootstrapMethodArguments) {
                        addConstant(argument);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    addConstant(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    addDescriptor(descriptor);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                                               org.objectweb.asm.Label end,
                                               org.objectweb.asm.Label handler,
                                               String type) {
                    addClass(type);
                }

                @Override
                public void visitLocalVariable(String variableName, String variableDescriptor,
                                               String variableSignature, org.objectweb.asm.Label start,
                                               org.objectweb.asm.Label end, int index) {
                    addDescriptor(variableDescriptor);
                    addTypeSignature(variableSignature);
                }

                @Override
                public AnnotationVisitor visitInsnAnnotation(int typeRef,
                                                             org.objectweb.asm.TypePath typePath,
                                                             String annotationDescriptor,
                                                             boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitTryCatchAnnotation(int typeRef,
                                                                 org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor,
                                                                 boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitLocalVariableAnnotation(int typeRef,
                                                                      org.objectweb.asm.TypePath typePath,
                                                                      org.objectweb.asm.Label[] start,
                                                                      org.objectweb.asm.Label[] end,
                                                                      int[] index,
                                                                      String annotationDescriptor,
                                                                      boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }
            };
        }

        private AnnotationVisitor annotationVisitor() {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof Type type) {
                        addType(type);
                    }
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    addDescriptor(descriptor);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    addDescriptor(descriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return annotationVisitor();
                }
            };
        }

        private void addSignature(String signature) {
            if (signature == null || signature.isEmpty()) {
                return;
            }
            try {
                new SignatureReader(signature).accept(signatureVisitor());
            } catch (IllegalArgumentException ignored) {
                // Some optional signature metadata is malformed or uses a different signature kind.
            }
        }

        private void addTypeSignature(String signature) {
            if (signature == null || signature.isEmpty()) {
                return;
            }
            try {
                new SignatureReader(signature).acceptType(signatureVisitor());
            } catch (IllegalArgumentException ignored) {
                // Raw descriptors are scanned separately; generic signatures are best-effort only.
            }
        }

        private SignatureVisitor signatureVisitor() {
            return new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitClassType(String name) {
                    addClass(name);
                }

                @Override
                public void visitInnerClassType(String name) {
                    // The outer type is already recorded from the generic signature.
                }
            };
        }

        private void addDescriptor(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return;
            }
            try {
                if (descriptor.charAt(0) == '(') {
                    Type methodType = Type.getMethodType(descriptor);
                    for (Type argument : methodType.getArgumentTypes()) {
                        addType(argument);
                    }
                    addType(methodType.getReturnType());
                } else {
                    addType(Type.getType(descriptor));
                }
            } catch (IllegalArgumentException ignored) {
                // Generic signatures and malformed optional metadata can reach this path.
                // The class reader will still report actual bytecode references below.
            }
        }

        private void addType(Type type) {
            switch (type.getSort()) {
                case Type.ARRAY -> addType(type.getElementType());
                case Type.OBJECT -> addClass(type.getInternalName());
                case Type.METHOD -> {
                    for (Type argument : type.getArgumentTypes()) {
                        addType(argument);
                    }
                    addType(type.getReturnType());
                }
                default -> {
                    // Primitives and void do not reference classes.
                }
            }
        }

        private void addClass(String owner) {
            if (owner == null || owner.isEmpty()) {
                return;
            }
            if (owner.startsWith("[")) {
                addDescriptor(owner);
                return;
            }
            if (owner.startsWith(MINECRAFT_PACKAGE)) {
                references.add(new Reference(SymbolKind.CLASS, owner, null, null));
            }
        }

        private void addMethod(String owner, String name, String descriptor) {
            if (owner != null && owner.startsWith(MINECRAFT_PACKAGE)) {
                references.add(new Reference(SymbolKind.METHOD, owner, name, descriptor));
            }
        }

        private void addField(String owner, String name, String descriptor) {
            if (owner != null && owner.startsWith(MINECRAFT_PACKAGE)) {
                references.add(new Reference(SymbolKind.FIELD, owner, name, descriptor));
            }
        }

        private void addHandle(Handle handle) {
            if (handle == null) {
                return;
            }
            addClass(handle.getOwner());
            switch (handle.getTag()) {
                case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC,
                     Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
                        addField(handle.getOwner(), handle.getName(), handle.getDesc());
                default -> addMethod(handle.getOwner(), handle.getName(), handle.getDesc());
            }
            addDescriptor(handle.getDesc());
        }

        private void addConstant(Object value) {
            if (value instanceof Type type) {
                addType(type);
            } else if (value instanceof Handle handle) {
                addHandle(handle);
            } else if (value instanceof ConstantDynamic dynamic) {
                addDescriptor(dynamic.getDescriptor());
                addHandle(dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                    addConstant(dynamic.getBootstrapMethodArgument(i));
                }
            }
        }
    }

    private record ModScan(int classCount, Set<Reference> references) {}
    private record ClassInfo(Set<Member> fields, Set<Member> methods, String superName,
                             List<String> interfaces) {}
    private record Member(String name, String descriptor) {}
    record Reference(SymbolKind kind, String owner, String name, String descriptor) {}
    private record AliasTarget(String owner, String name, String descriptor) {}

    /**
     * Explicit symbol equivalences. The scanner never guesses aliases because a wrong alias can
     * turn a detectable incompatibility into a misleading "compatible" result.
     */
    static final class AliasSet {
        private final Map<String, String> classes;
        private final Map<Reference, AliasTarget> members;

        private AliasSet(Map<String, String> classes, Map<Reference, AliasTarget> members) {
            this.classes = Map.copyOf(classes);
            this.members = Map.copyOf(members);
        }

        static AliasSet empty() {
            return new AliasSet(Map.of(), Map.of());
        }

        /**
         * Applies narrowly reviewed class moves that are known to exist in the pinned target
         * game. These are runtime namespace aliases, not mod-JAR rewrites.
         */
        AliasSet withBuiltInCompatibilityAliases(TargetApiIndex targetApi) {
            Map<String, String> merged = new HashMap<>(classes);
            String legacy = "net/minecraft/world/level/BlockAndTintGetter";
            String replacement = "net/minecraft/client/renderer/block/BlockAndTintGetter";
            // Synthetic/unit-test game JARs may not include the replacement class. Only enable
            // the built-in alias when the real configured target actually contains that class.
            if (targetApi != null && !targetApi.hasClass(legacy) && targetApi.hasClass(replacement)) {
                putBuiltInClassAlias(merged, legacy, replacement);
            }
            return new AliasSet(merged, members);
        }

        private static void putBuiltInClassAlias(Map<String, String> aliases,
                                                  String legacyName, String targetName) {
            String previous = aliases.putIfAbsent(legacyName, targetName);
            if (previous != null && !previous.equals(targetName)) {
                throw new IllegalArgumentException("Conflicting built-in class alias for "
                        + legacyName + ": " + previous + " vs " + targetName);
            }
        }

        static AliasSet load(Path file) throws IOException {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Alias file does not exist: " + file);
            }
            Map<String, String> classAliases = new HashMap<>();
            Map<Reference, AliasTarget> memberAliases = new HashMap<>();
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // Tabs are intentional: descriptors and owners must not be split ambiguously.
                String[] columns = lines.get(i).split("\\t", -1);
                String kind = columns[0].trim().toUpperCase(java.util.Locale.ROOT);
                if (kind.equals("CLASS")) {
                    if (columns.length != 3) {
                        throw invalidAlias(file, i + 1, "CLASS requires: CLASS<TAB>old-owner<TAB>new-owner");
                    }
                    String oldOwner = requireValue(columns[1], file, i + 1);
                    String newOwner = requireValue(columns[2], file, i + 1);
                    putConsistent(classAliases, oldOwner, newOwner, file, i + 1);
                } else if (kind.equals("METHOD") || kind.equals("FIELD")) {
                    if (columns.length != 7) {
                        throw invalidAlias(file, i + 1,
                                kind + " requires: " + kind
                                        + "<TAB>old-owner<TAB>old-name<TAB>old-descriptor"
                                        + "<TAB>new-owner<TAB>new-name<TAB>new-descriptor");
                    }
                    String oldOwner = requireValue(columns[1], file, i + 1);
                    String oldName = requireValue(columns[2], file, i + 1);
                    String oldDescriptor = requireValue(columns[3], file, i + 1);
                    String newOwner = requireValue(columns[4], file, i + 1);
                    String newName = requireValue(columns[5], file, i + 1);
                    String newDescriptor = requireValue(columns[6], file, i + 1);
                    SymbolKind symbolKind = kind.equals("METHOD") ? SymbolKind.METHOD : SymbolKind.FIELD;
                    Reference key = new Reference(symbolKind, oldOwner, oldName, oldDescriptor);
                    AliasTarget target = new AliasTarget(newOwner, newName, newDescriptor);
                    AliasTarget previous = memberAliases.putIfAbsent(key, target);
                    if (previous != null && !previous.equals(target)) {
                        throw invalidAlias(file, i + 1, "Conflicting alias for " + oldOwner + "#" + oldName);
                    }
                } else {
                    throw invalidAlias(file, i + 1, "Unknown alias kind: " + kind);
                }
            }
            return new AliasSet(classAliases, memberAliases);
        }

        Reference normalize(Reference reference) {
            if (reference.kind() == SymbolKind.CLASS) {
                return new Reference(SymbolKind.CLASS, mapClass(reference.owner()), null, null);
            }

            AliasTarget explicit = members.get(reference);
            if (explicit != null) {
                return new Reference(reference.kind(), explicit.owner(), explicit.name(), explicit.descriptor());
            }
            return new Reference(reference.kind(), mapClass(reference.owner()), reference.name(),
                    mapDescriptor(reference.descriptor()));
        }

        int classAliasCount() {
            return classes.size();
        }

        int memberAliasCount() {
            return members.size();
        }

        /**
         * Validate every alias target against the actual 26.2 game artifact before the runtime
         * transformer is installed. This catches typos and stale rules before mods start linking.
         */
        void validateAgainstTarget(Path targetGameJar) throws IOException {
            validateAgainstTarget(TargetApiIndex.load(targetGameJar));
        }

        void validateAgainstTarget(TargetApiIndex target) throws IOException {
            if (target == null) {
                throw new IllegalArgumentException("Target Minecraft API index is required.");
            }
            for (Map.Entry<String, String> entry : classes.entrySet()) {
                if (!target.hasClass(entry.getValue())) {
                    throw new IOException("Class alias target is absent from Minecraft 26.2: "
                            + entry.getKey() + " -> " + entry.getValue());
                }
            }

            for (Map.Entry<Reference, AliasTarget> entry : members.entrySet()) {
                Reference source = entry.getKey();
                AliasTarget destination = entry.getValue();
                if (!target.hasClass(destination.owner())) {
                    throw new IOException("Member alias target owner is absent from Minecraft 26.2: "
                            + destination.owner() + " (from " + source.owner() + "#" + source.name() + ")");
                }
                String descriptorExpectedFromClassAliases = mapDescriptor(source.descriptor());
                if (!descriptorExpectedFromClassAliases.equals(destination.descriptor())) {
                    throw new IOException("Unsupported descriptor-changing alias for " + source.owner()
                            + "#" + source.name() + source.descriptor() + ": target descriptor "
                            + destination.descriptor() + " does not match class-only mapping "
                            + descriptorExpectedFromClassAliases);
                }

                boolean found = source.kind() == SymbolKind.METHOD
                        ? target.methodAccess(destination.owner(), destination.name(), destination.descriptor()) != null
                        : target.fieldAccess(destination.owner(), destination.name(), destination.descriptor()) != null;
                if (!found) {
                    throw new IOException("Member alias target is absent from Minecraft 26.2: "
                            + destination.owner() + "#" + destination.name() + destination.descriptor()
                            + " (from " + source.owner() + "#" + source.name() + source.descriptor() + ")");
                }
            }
        }

        String mapClass(String owner) {
            String exact = classes.get(owner);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, String> entry : classes.entrySet()) {
                if (owner.startsWith(entry.getKey() + "$")) {
                    return entry.getValue() + owner.substring(entry.getKey().length());
                }
            }
            return owner;
        }

        String mapDescriptor(String descriptor) {
            if (descriptor == null) {
                return null;
            }
            String mapped = descriptor;
            for (Map.Entry<String, String> entry : classes.entrySet()) {
                mapped = mapped.replace("L" + entry.getKey() + ";", "L" + entry.getValue() + ";");
                mapped = mapped.replace("L" + entry.getKey() + "$", "L" + entry.getValue() + "$");
            }
            return mapped;
        }

        private static void putConsistent(Map<String, String> aliases, String key, String value,
                                          Path file, int line) throws IOException {
            String previous = aliases.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw invalidAlias(file, line, "Conflicting class alias for " + key);
            }
        }

        private static String requireValue(String value, Path file, int line) throws IOException {
            String trimmed = value.trim();
            if (trimmed.isEmpty() || trimmed.equals("-")) {
                throw invalidAlias(file, line, "Empty alias value");
            }
            return trimmed;
        }

        private static IOException invalidAlias(Path file, int line, String message) {
            return new IOException(file + ":" + line + ": " + message);
        }
    }

    enum SymbolKind {
        CLASS,
        METHOD,
        FIELD
    }

    public record Finding(String modJar, String kind, String owner, String name,
                          String descriptor, String detail) {}

    public record Summary(int modJarsScanned, int modClassesScanned, int referencesInspected,
                          int targetClassesIndexed, int findingCount, int missingClasses,
                          int missingMethods, int missingFields, int classAliasesLoaded,
                          int memberAliasesLoaded, int intermediaryClassesMapped,
                          int intermediaryMembersMapped) {}

    public record Report(String targetGameJar, List<String> modJars, Summary summary,
                         List<Finding> findings) {}
}
