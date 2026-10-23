package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Lightweight index of the actual target game artifact used to validate compatibility rules.
 * It records JVM descriptors and staticness, without loading Minecraft classes.
 */
final class TargetApiIndex {
    private static final int API = Opcodes.ASM9;

    private final Map<String, TargetClass> classes;

    private TargetApiIndex(Map<String, TargetClass> classes) {
        this.classes = Map.copyOf(classes);
    }

    static TargetApiIndex load(Path gameJar) throws IOException {
        if (gameJar == null || !Files.isRegularFile(gameJar)) {
            throw new IllegalArgumentException("Minecraft 26.2 target game JAR does not exist: " + gameJar);
        }

        Map<String, TargetClass> classes = new HashMap<>();
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
                    Map<MemberKey, Integer> fields = new HashMap<>();
                    Map<MemberKey, Integer> methods = new HashMap<>();
                    Map<MemberKey, Set<NewExpressionKey>> newExpressions = new HashMap<>();
                    Map<MemberKey, Set<InvocationKey>> invocations = new HashMap<>();
                    Map<MemberKey, Set<FieldAccessKey>> fieldAccesses = new HashMap<>();
                    int[] classAccess = new int[1];
                    String[] superName = new String[1];
                    String[][] interfaces = new String[1][];
                    reader.accept(new ClassVisitor(API) {
                        @Override
                        public void visit(int version, int access, String name, String signature,
                                          String declaredSuperName, String[] declaredInterfaces) {
                            classAccess[0] = access;
                            superName[0] = declaredSuperName;
                            interfaces[0] = declaredInterfaces == null ? new String[0] : declaredInterfaces.clone();
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name, String descriptor,
                                                       String signature, Object value) {
                            fields.put(new MemberKey(name, descriptor), access);
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                         String signature, String[] exceptions) {
                            MemberKey methodKey = new MemberKey(name, descriptor);
                            methods.put(methodKey, access);
                            Set<NewExpressionKey> expressions = new HashSet<>();
                            Set<InvocationKey> methodInvocations = new HashSet<>();
                            Set<FieldAccessKey> methodFieldAccesses = new HashSet<>();
                            newExpressions.put(methodKey, expressions);
                            invocations.put(methodKey, methodInvocations);
                            fieldAccesses.put(methodKey, methodFieldAccesses);
                            return new MethodVisitor(API) {
                                private final List<String> pendingNewOwners = new java.util.ArrayList<>();

                                @Override
                                public void visitTypeInsn(int opcode, String type) {
                                    if (opcode == Opcodes.NEW) {
                                        pendingNewOwners.add(type);
                                    }
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String methodName,
                                                            String methodDescriptor, boolean isInterface) {
                                    methodInvocations.add(new InvocationKey(owner, methodName, methodDescriptor));
                                    if (opcode == Opcodes.INVOKESPECIAL && methodName.equals("<init>")) {
                                        int pendingIndex = pendingNewOwners.lastIndexOf(owner);
                                        if (pendingIndex >= 0) {
                                            pendingNewOwners.remove(pendingIndex);
                                            expressions.add(new NewExpressionKey(owner, methodDescriptor));
                                        }
                                    }
                                }

                                @Override
                                public void visitFieldInsn(int opcode, String owner, String fieldName,
                                                           String fieldDescriptor) {
                                    methodFieldAccesses.add(new FieldAccessKey(owner, fieldName, fieldDescriptor));
                                }

                                @Override
                                public void visitEnd() {
                                    newExpressions.put(methodKey, Set.copyOf(expressions));
                                    invocations.put(methodKey, Set.copyOf(methodInvocations));
                                    fieldAccesses.put(methodKey, Set.copyOf(methodFieldAccesses));
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                    Map<MemberKey, Set<NewExpressionKey>> immutableNewExpressions = new HashMap<>();
                    newExpressions.forEach((key, value) -> immutableNewExpressions.put(key, Set.copyOf(value)));
                    Map<MemberKey, Set<InvocationKey>> immutableInvocations = new HashMap<>();
                    invocations.forEach((key, value) -> immutableInvocations.put(key, Set.copyOf(value)));
                    Map<MemberKey, Set<FieldAccessKey>> immutableFieldAccesses = new HashMap<>();
                    fieldAccesses.forEach((key, value) -> immutableFieldAccesses.put(key, Set.copyOf(value)));
                    TargetClass previous = classes.putIfAbsent(reader.getClassName(),
                            new TargetClass(classAccess[0], superName[0], List.of(interfaces[0]),
                                    Map.copyOf(fields), Map.copyOf(methods), Map.copyOf(immutableNewExpressions),
                                    Map.copyOf(immutableInvocations), Map.copyOf(immutableFieldAccesses)));
                    if (previous != null) {
                        throw new IOException("Duplicate class entry for " + reader.getClassName()
                                + " in target game JAR " + gameJar);
                    }
                } catch (RuntimeException exception) {
                    throw new IOException("Could not index target class " + entry.getName()
                            + " in " + gameJar, exception);
                }
            }
        }

        if (classes.isEmpty()) {
            throw new IOException("No class files found in target game JAR: " + gameJar);
        }
        return new TargetApiIndex(classes);
    }

    boolean hasClass(String owner) {
        return classes.containsKey(owner);
    }

    /**
     * Looks up a method declared directly by this class, without walking its superclass
     * or superinterfaces. Structural bridge injection uses this because static interface
     * methods are not inherited, even if a general symbolic lookup finds a matching parent
     * declaration.
     */
    Integer declaredMethodAccess(String owner, String name, String descriptor) {
        TargetClass target = classes.get(owner);
        return target == null ? null : target.methods().get(new MemberKey(name, descriptor));
    }

    Integer methodAccess(String owner, String name, String descriptor) {
        return methodAccess(owner, new MemberKey(name, descriptor), new HashSet<>());
    }

    /**
     * Checks whether a target method contains a matching object-allocation expression,
     * used to validate Mixin @At(value="NEW", target="(args)ReturnType") anchors.
     */
    boolean hasNewExpression(String owner, String methodName, String atDescriptor) {
        if (atDescriptor == null) {
            return false;
        }
        final Type atType;
        try {
            atType = Type.getMethodType(atDescriptor);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (atType.getReturnType().getSort() != Type.OBJECT) {
            return false;
        }
        String allocatedType = atType.getReturnType().getInternalName();
        String constructorDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, atType.getArgumentTypes());
        return hasNewExpression(owner, methodName, allocatedType, constructorDescriptor);
    }

    /** Checks a constructor-specific NEW anchor against instructions in the selected target method. */
    boolean hasNewExpression(String owner, String methodName, String allocatedType,
                             String constructorDescriptor) {
        TargetClass target = classes.get(owner);
        if (target == null || methodName == null || allocatedType == null
                || constructorDescriptor == null) {
            return false;
        }
        NewExpressionKey wanted = new NewExpressionKey(allocatedType, constructorDescriptor);
        return target.newExpressions().entrySet().stream()
                .anyMatch(entry -> entry.getKey().name().equals(methodName)
                        && entry.getValue().contains(wanted));
    }

    /** Checks whether the selected method allocates an object of the requested type. */
    boolean hasNewExpressionOfType(String owner, String methodName, String allocatedType) {
        TargetClass target = classes.get(owner);
        if (target == null || methodName == null || allocatedType == null) {
            return false;
        }
        return target.newExpressions().entrySet().stream()
                .anyMatch(entry -> entry.getKey().name().equals(methodName)
                        && entry.getValue().stream().anyMatch(expression ->
                                expression.allocatedType().equals(allocatedType)));
    }

    /** Checks whether a concrete invocation occurs inside any overload with the selected method name. */
    boolean hasInvocationInNamedMethod(String owner, String methodName,
                                       String invocationOwner, String invocationName,
                                       String invocationDescriptor) {
        TargetClass target = classes.get(owner);
        if (target == null) {
            return false;
        }
        InvocationKey wanted = new InvocationKey(invocationOwner, invocationName, invocationDescriptor);
        return target.invocations().entrySet().stream()
                .anyMatch(entry -> entry.getKey().name().equals(methodName)
                        && entry.getValue().contains(wanted));
    }

    /** Checks whether a concrete field access occurs inside any overload with the selected method name. */
    boolean hasFieldAccessInNamedMethod(String owner, String methodName,
                                        String fieldOwner, String fieldName,
                                        String fieldDescriptor) {
        TargetClass target = classes.get(owner);
        if (target == null) {
            return false;
        }
        FieldAccessKey wanted = new FieldAccessKey(fieldOwner, fieldName, fieldDescriptor);
        return target.fieldAccesses().entrySet().stream()
                .anyMatch(entry -> entry.getKey().name().equals(methodName)
                        && entry.getValue().contains(wanted));
    }

    boolean hasMethodNamed(String owner, String name) {
        return hasMethodNamed(owner, name, new HashSet<>());
    }

    private boolean hasMethodNamed(String owner, String name, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return false;
        }
        TargetClass target = classes.get(owner);
        if (target == null) {
            return false;
        }
        if (target.methods().keySet().stream().anyMatch(member -> member.name().equals(name))) {
            return true;
        }
        for (String interfaceName : target.interfaces()) {
            if (hasMethodNamed(interfaceName, name, visited)) {
                return true;
            }
        }
        return hasMethodNamed(target.superName(), name, visited);
    }

    private Integer methodAccess(String owner, MemberKey key, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return null;
        }
        TargetClass target = classes.get(owner);
        if (target == null) {
            return null;
        }
        Integer direct = target.methods().get(key);
        if (direct != null) {
            return direct;
        }
        for (String interfaceName : target.interfaces()) {
            Integer inherited = methodAccess(interfaceName, key, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return methodAccess(target.superName(), key, visited);
    }

    Integer fieldAccess(String owner, String name, String descriptor) {
        return fieldAccess(owner, new MemberKey(name, descriptor), new HashSet<>());
    }

    private Integer fieldAccess(String owner, MemberKey key, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return null;
        }
        TargetClass target = classes.get(owner);
        if (target == null) {
            return null;
        }
        Integer direct = target.fields().get(key);
        if (direct != null) {
            return direct;
        }
        for (String interfaceName : target.interfaces()) {
            Integer inherited = fieldAccess(interfaceName, key, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return fieldAccess(target.superName(), key, visited);
    }

    boolean isInterface(String owner) {
        TargetClass target = classes.get(owner);
        return target != null && (target.access() & Opcodes.ACC_INTERFACE) != 0;
    }

    int classCount() {
        return classes.size();
    }

    private record MemberKey(String name, String descriptor) {}
    private record NewExpressionKey(String allocatedType, String constructorDescriptor) {}
    private record InvocationKey(String owner, String name, String descriptor) {}
    private record FieldAccessKey(String owner, String name, String descriptor) {}
    private record TargetClass(int access, String superName, List<String> interfaces,
                               Map<MemberKey, Integer> fields, Map<MemberKey, Integer> methods,
                               Map<MemberKey, Set<NewExpressionKey>> newExpressions,
                               Map<MemberKey, Set<InvocationKey>> invocations,
                               Map<MemberKey, Set<FieldAccessKey>> fieldAccesses) {}
}
