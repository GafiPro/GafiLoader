package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Applies explicitly reviewed, same-descriptor method aliases to a COPY of the target game JAR.
 *
 * <p>The original game JAR is never overwritten and mod JARs are never opened for writing.
 * This is a preparation-time patcher, not yet a Fabric launch integration. Rules are strict:
 * each target method must exist in the requested owner, and an alias must not already exist.</p>
 */
public final class GameJarPatcher {
    private static final int API = Opcodes.ASM9;

    public PatchReport patch(Path targetGameJar, Path outputJar, Path rulesFile) throws IOException {
        requireRegularFile(targetGameJar, "Minecraft 26.2 game JAR");
        requireRegularFile(rulesFile, "Game-side compatibility rules");
        if (outputJar == null) {
            throw new IllegalArgumentException("Output JAR path is required.");
        }

        Path input = targetGameJar.toRealPath();
        Path output = outputJar.toAbsolutePath().normalize();
        if (Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IllegalArgumentException("Output JAR must not be the original game JAR.");
        }
        if (input.equals(output)) {
            throw new IllegalArgumentException("Output JAR must not be the original game JAR.");
        }

        List<MethodAlias> aliases = loadRules(rulesFile);
        if (aliases.isEmpty()) {
            throw new IllegalArgumentException("No METHOD_ALIAS rules were found in " + rulesFile);
        }

        Map<String, List<MethodAlias>> aliasesByOwner = groupByOwner(aliases);

        Path parent = output.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "gafi-loader-patched-", ".jar");
        Set<String> ownersFound = new HashSet<>();
        Set<String> appliedRules = new LinkedHashSet<>();
        int patchedClasses = 0;
        int aliasesAdded = 0;

        try {
            try (JarFile inputJar = new JarFile(input.toFile());
                 OutputStream fileOutput = Files.newOutputStream(temporary);
                 JarOutputStream jarOutput = new JarOutputStream(fileOutput)) {
                var entries = inputJar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || isSignatureFile(entry.getName())) {
                        continue;
                    }

                    byte[] contents;
                    try (InputStream stream = inputJar.getInputStream(entry)) {
                        contents = stream.readAllBytes();
                    }

                    List<MethodAlias> classAliases = List.of();
                    if (entry.getName().endsWith(".class")
                            && !entry.getName().startsWith("META-INF/versions/")) {
                        ClassReader reader = new ClassReader(contents);
                        classAliases = aliasesByOwner.getOrDefault(reader.getClassName(), List.of());
                        if (!classAliases.isEmpty()) {
                            if (!entry.getName().equals(reader.getClassName() + ".class")) {
                                throw new IOException("Unexpected JAR entry for class " + reader.getClassName()
                                        + ": " + entry.getName());
                            }
                            contents = patchClass(contents, classAliases, appliedRules);
                            ownersFound.add(reader.getClassName());
                            patchedClasses++;
                            aliasesAdded += classAliases.size();
                        }
                    }

                    JarEntry copiedEntry = new JarEntry(entry.getName());
                    if (entry.getTime() >= 0) {
                        copiedEntry.setTime(entry.getTime());
                    }
                    if (entry.getComment() != null) {
                        copiedEntry.setComment(entry.getComment());
                    }
                    jarOutput.putNextEntry(copiedEntry);
                    jarOutput.write(contents);
                    jarOutput.closeEntry();
                }
            }

            Set<String> missingOwners = new LinkedHashSet<>(aliasesByOwner.keySet());
            missingOwners.removeAll(ownersFound);
            if (!missingOwners.isEmpty()) {
                throw new IOException("Rule owner class(es) not found in the target game JAR: "
                        + String.join(", ", missingOwners));
            }
            if (appliedRules.size() != aliases.size()) {
                throw new IOException("Internal error: not all game-side alias rules were applied.");
            }

            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        return new PatchReport(input.toString(), output.toString(), patchedClasses,
                aliasesAdded, List.copyOf(appliedRules));
    }

    static RuntimeRules loadRuntimeRules(Path rulesFile) throws IOException {
        List<MethodAlias> aliases = loadRules(rulesFile);
        if (aliases.isEmpty()) {
            throw new IOException("No METHOD_ALIAS rules were found in " + rulesFile);
        }
        return new RuntimeRules(groupByOwner(aliases));
    }

    static RuntimeRules emptyRuntimeRules() {
        return new RuntimeRules(Map.of());
    }

    static byte[] transformClassForRuntime(String className, byte[] original, RuntimeRules rules)
            throws IOException {
        List<MethodAlias> aliases = rules.aliasesByOwner.get(className);
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        return patchClass(original, aliases, rules.appliedRules);
    }

    private static Map<String, List<MethodAlias>> groupByOwner(List<MethodAlias> aliases) {
        Map<String, List<MethodAlias>> aliasesByOwner = new LinkedHashMap<>();
        for (MethodAlias alias : aliases) {
            aliasesByOwner.computeIfAbsent(alias.owner(), ignored -> new ArrayList<>()).add(alias);
        }
        Map<String, List<MethodAlias>> immutable = new LinkedHashMap<>();
        aliasesByOwner.forEach((owner, values) -> immutable.put(owner, List.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private static byte[] patchClass(byte[] original, List<MethodAlias> aliases,
                                     Set<String> appliedRules) throws IOException {
        ClassReader reader = new ClassReader(original);
        ClassMetadata metadata = inspect(reader);

        for (MethodAlias alias : aliases) {
            Integer targetAccess = metadata.methods().get(new MethodKey(alias.targetName(), alias.descriptor()));
            if (targetAccess == null) {
                throw new IOException("Cannot apply " + alias.describe() + ": target method "
                        + alias.targetName() + alias.descriptor() + " does not exist in " + alias.owner());
            }
            if (metadata.methods().containsKey(new MethodKey(alias.aliasName(), alias.descriptor()))) {
                throw new IOException("Cannot apply " + alias.describe() + ": alias signature already exists.");
            }
            // JVM invocation opcode must match static/instance shape, not only the descriptor.
            boolean targetIsStatic = (targetAccess & Opcodes.ACC_STATIC) != 0;
            if (targetIsStatic != (alias.dispatch() == Dispatch.STATIC)) {
                throw new IOException("Cannot apply " + alias.describe() + ": dispatch kind does not match target method.");
            }
            if (alias.targetName().startsWith("<") || alias.aliasName().startsWith("<")) {
                throw new IOException("Constructors and class initializers cannot be aliased: " + alias.describe());
            }
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(API, writer) {
            @Override
            public void visitEnd() {
                for (MethodAlias alias : aliases) {
                    int targetAccess = metadata.methods().get(new MethodKey(alias.targetName(), alias.descriptor()));
                    addDelegatingAlias(writer, metadata, alias, targetAccess);
                    appliedRules.add(alias.describe());
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static ClassMetadata inspect(ClassReader reader) {
        Map<MethodKey, Integer> methods = new HashMap<>();
        int[] classAccess = new int[1];
        reader.accept(new ClassVisitor(API) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                classAccess[0] = access;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.put(new MethodKey(name, descriptor), access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassMetadata(reader.getClassName(),
                (classAccess[0] & Opcodes.ACC_INTERFACE) != 0, Map.copyOf(methods));
    }

    private static void addDelegatingAlias(ClassWriter writer, ClassMetadata owner,
                                           MethodAlias alias, int targetAccess) {
        boolean isStatic = (targetAccess & Opcodes.ACC_STATIC) != 0;
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE;
        if (isStatic) {
            access |= Opcodes.ACC_STATIC;
        }

        MethodVisitor method = writer.visitMethod(access, alias.aliasName(), alias.descriptor(), null, null);
        method.visitCode();

        int local = 0;
        if (!isStatic) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            local = 1;
        }

        Type[] arguments = Type.getArgumentTypes(alias.descriptor());
        for (Type argument : arguments) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local);
            local += argument.getSize();
        }

        int invokeOpcode;
        boolean interfaceOwner = owner.isInterface();
        if (isStatic) {
            invokeOpcode = Opcodes.INVOKESTATIC;
        } else if ((targetAccess & Opcodes.ACC_PRIVATE) != 0) {
            invokeOpcode = Opcodes.INVOKESPECIAL;
        } else if (interfaceOwner) {
            invokeOpcode = Opcodes.INVOKEINTERFACE;
        } else {
            invokeOpcode = Opcodes.INVOKEVIRTUAL;
        }

        method.visitMethodInsn(invokeOpcode, owner.name(), alias.targetName(),
                alias.descriptor(), interfaceOwner);
        method.visitInsn(Type.getReturnType(alias.descriptor()).getOpcode(Opcodes.IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static List<MethodAlias> loadRules(Path rulesFile) throws IOException {
        List<MethodAlias> aliases = new ArrayList<>();
        Map<MethodKeyWithOwner, MethodAlias> bySignature = new HashMap<>();
        List<String> lines = Files.readAllLines(rulesFile);
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] columns = raw.split("\\t", -1);
            if (columns.length != 6 || !columns[0].trim().equals("METHOD_ALIAS")) {
                throw invalidRule(rulesFile, i + 1,
                        "expected METHOD_ALIAS<TAB>owner<TAB>current-name<TAB>descriptor<TAB>legacy-name<TAB>STATIC|INSTANCE");
            }

            String owner = requireColumn(columns[1], rulesFile, i + 1, "owner");
            String targetName = requireColumn(columns[2], rulesFile, i + 1, "current method name");
            String descriptor = requireColumn(columns[3], rulesFile, i + 1, "method descriptor");
            String aliasName = requireColumn(columns[4], rulesFile, i + 1, "legacy method name");
            String dispatchText = requireColumn(columns[5], rulesFile, i + 1, "dispatch kind")
                    .toUpperCase(java.util.Locale.ROOT);
            Dispatch dispatch;
            try {
                dispatch = Dispatch.valueOf(dispatchText);
            } catch (IllegalArgumentException exception) {
                throw invalidRule(rulesFile, i + 1, "dispatch kind must be STATIC or INSTANCE");
            }
            if (owner.contains(".") || owner.startsWith("/") || owner.endsWith("/")
                    || owner.contains("//")) {
                throw invalidRule(rulesFile, i + 1, "owner must be an internal class name using '/' separators");
            }
            if (targetName.startsWith("<") || aliasName.startsWith("<")) {
                throw invalidRule(rulesFile, i + 1, "constructors and class initializers cannot be aliases");
            }
            try {
                Type methodType = Type.getMethodType(descriptor);
                if (!Type.getMethodDescriptor(methodType.getReturnType(), methodType.getArgumentTypes())
                        .equals(descriptor)) {
                    throw new IllegalArgumentException("Non-canonical method descriptor");
                }
            } catch (IllegalArgumentException exception) {
                throw invalidRule(rulesFile, i + 1, "invalid JVM method descriptor: " + descriptor);
            }

            MethodAlias alias = new MethodAlias(owner, targetName, descriptor, aliasName, dispatch);
            MethodKeyWithOwner key = new MethodKeyWithOwner(owner, aliasName, descriptor);
            MethodAlias previous = bySignature.putIfAbsent(key, alias);
            if (previous != null) {
                throw invalidRule(rulesFile, i + 1, "duplicate alias signature for "
                        + owner + "#" + aliasName + descriptor);
            }
            aliases.add(alias);
        }
        return List.copyOf(aliases);
    }

    private static String requireColumn(String value, Path file, int line, String label) throws IOException {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.equals("-")) {
            throw invalidRule(file, line, label + " is empty");
        }
        return trimmed;
    }

    private static IOException invalidRule(Path file, int line, String message) {
        return new IOException(file + ":" + line + ": " + message);
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.endsWith("/")) {
            return false;
        }
        String leaf = upper.substring(upper.lastIndexOf('/') + 1);
        return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC") || leaf.startsWith("SIG-");
    }

    private static void requireRegularFile(Path path, String label) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " does not exist: " + path);
        }
    }

    static final class RuntimeRules {
        private final Map<String, List<MethodAlias>> aliasesByOwner;
        private final Set<String> appliedRules = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private RuntimeRules(Map<String, List<MethodAlias>> aliasesByOwner) {
            this.aliasesByOwner = aliasesByOwner;
        }

        int ruleCount() {
            return aliasesByOwner.values().stream().mapToInt(List::size).sum();
        }

        Set<String> appliedRules() {
            return Set.copyOf(appliedRules);
        }
    }

    private record MethodKey(String name, String descriptor) {}
    private record MethodKeyWithOwner(String owner, String name, String descriptor) {}
    private enum Dispatch { STATIC, INSTANCE }
    private record MethodAlias(String owner, String targetName, String descriptor,
                               String aliasName, Dispatch dispatch) {
        String describe() {
            return owner + "#" + aliasName + descriptor + " -> " + targetName + descriptor
                    + " [" + dispatch + "]";
        }
    }
    private record ClassMetadata(String name, boolean isInterface, Map<MethodKey, Integer> methods) {}

    public record PatchReport(String inputGameJar, String outputGameJar, int classesPatched,
                              int methodAliasesAdded, List<String> appliedAliases) {
        public PatchReport {
            appliedAliases = List.copyOf(appliedAliases);
        }
    }
}
