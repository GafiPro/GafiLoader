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
                            methods.put(new MemberKey(name, descriptor), access);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                    TargetClass previous = classes.putIfAbsent(reader.getClassName(),
                            new TargetClass(classAccess[0], superName[0], List.of(interfaces[0]),
                                    Map.copyOf(fields), Map.copyOf(methods)));
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
    private record TargetClass(int access, String superName, List<String> interfaces,
                               Map<MemberKey, Integer> fields, Map<MemberKey, Integer> methods) {}
}
