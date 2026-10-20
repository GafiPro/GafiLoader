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

            for (Reference reference : scan.references()) {
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
                if (reference.kind() == SymbolKind.METHOD && !targetClass.methods().contains(member)) {
                    findings.add(new Finding(
                            modJar.toString(), "MISSING_METHOD", reference.owner(), reference.name(),
                            reference.descriptor(),
                            "The target class exists, but the exact method name/descriptor was not found."));
                } else if (reference.kind() == SymbolKind.FIELD && !targetClass.fields().contains(member)) {
                    findings.add(new Finding(
                            modJar.toString(), "MISSING_FIELD", reference.owner(), reference.name(),
                            reference.descriptor(),
                            "The target class exists, but the exact field name/descriptor was not found."));
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
                gameClasses.size(), findings.size(), missingClasses, missingMethods, missingFields);

        return new Report(
                gameJar.toAbsolutePath().normalize().toString(),
                orderedMods.stream().map(Path::toString).toList(),
                summary,
                findings);
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
                        private final Set<Member> fields = new HashSet<>();
                        private final Set<Member> methods = new HashSet<>();

                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String superName, String[] interfaces) {
                            className = name;
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
                                    new ClassInfo(Set.copyOf(fields), Set.copyOf(methods)));
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
        public void visitRecordComponent(String name, String descriptor, String signature) {
            addDescriptor(descriptor);
            addSignature(signature);
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
            addSignature(signature);
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
                    addSignature(variableSignature);
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
            new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
                @Override
                public void visitClassType(String name) {
                    addClass(name);
                }

                @Override
                public void visitInnerClassType(String name) {
                    // The outer class is already recorded; inner names are represented with '$'
                    // in the JVM descriptor, so record the likely binary name as well.
                    // Nested generic signature names may not have enough context to reconstruct
                    // every binary name, which is why this remains a best-effort extra reference.
                }
            });
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
    private record ClassInfo(Set<Member> fields, Set<Member> methods) {}
    private record Member(String name, String descriptor) {}
    private record Reference(SymbolKind kind, String owner, String name, String descriptor) {}

    private enum SymbolKind {
        CLASS,
        METHOD,
        FIELD
    }

    public record Finding(String modJar, String kind, String owner, String name,
                          String descriptor, String detail) {}

    public record Summary(int modJarsScanned, int modClassesScanned, int referencesInspected,
                          int targetClassesIndexed, int findingCount, int missingClasses,
                          int missingMethods, int missingFields) {}

    public record Report(String targetGameJar, List<String> modJars, Summary summary,
                         List<Finding> findings) {}
}
