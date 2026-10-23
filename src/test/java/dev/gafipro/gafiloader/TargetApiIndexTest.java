package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetApiIndexTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void distinguishesDirectMethodDeclarationsFromMethodsFoundThroughInterfaces() throws Exception {
        Path targetJar = temporaryDirectory.resolve("inherited-static-method.jar");
        String owner = "net/minecraft/world/scores/ReadOnlyScoreInfo";
        String parent = "net/minecraft/example/ParentApi";
        String numberFormat = "net/minecraft/network/chat/numbers/NumberFormat";
        String mutableComponent = "net/minecraft/network/chat/MutableComponent";
        String descriptor = "(L" + owner + ";L" + numberFormat + ";)L" + mutableComponent + ";";
        writeJar(targetJar,
                owner, interfaceWithParent(owner, parent),
                parent, interfaceWithStaticMethod(parent, "safeFormatValue", descriptor),
                numberFormat, emptyInterface(numberFormat),
                mutableComponent, emptyInterface(mutableComponent));

        TargetApiIndex api = TargetApiIndex.load(targetJar);
        assertNotNull(api.methodAccess(owner, "safeFormatValue", descriptor),
                "The general lookup traverses superinterfaces for diagnostics");
        assertNull(api.declaredMethodAccess(owner, "safeFormatValue", descriptor),
                "The structural bridge must only be suppressed for a direct declaration");
    }

    @Test
    void detectsConstructorAllocationAnchorsInsideTheSelectedTargetMethod() throws Exception {
        Path targetJar = temporaryDirectory.resolve("new-expression-index.jar");
        String owner = "net/minecraft/world/entity/LivingEntity";
        String allocated = "net/minecraft/core/particles/BlockParticleOption";
        String descriptor = "(Lnet/minecraft/core/particles/ParticleType;"
                + "Lnet/minecraft/world/level/block/state/BlockState;)"
                + "Lnet/minecraft/core/particles/BlockParticleOption;";
        writeJar(targetJar,
                owner, classWithNewExpression(owner, "checkFallDamage", allocated,
                        "(Lnet/minecraft/core/particles/ParticleType;"
                                + "Lnet/minecraft/world/level/block/state/BlockState;)V"),
                allocated, emptyClass(allocated));

        TargetApiIndex api = TargetApiIndex.load(targetJar);
        assertTrue(api.hasNewExpression(owner, "checkFallDamage", descriptor));
        assertFalse(api.hasNewExpression(owner, "otherMethod", descriptor));
        assertFalse(api.hasNewExpression(owner, "checkFallDamage",
                "(Lnet/minecraft/core/particles/ParticleType;)Lnet/minecraft/core/particles/BlockParticleOption;"));
    }

    @Test
    void acceptsAliasesOnlyWhenTargetsExistInTheRealGameApiIndex() throws Exception {
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2-fixture.jar");
        Path aliasesFile = temporaryDirectory.resolve("aliases.tsv");
        writeJar(targetJar,
                "net/minecraft/Foo", targetFoo(),
                "net/minecraft/Bar", emptyClass("net/minecraft/Bar"));
        Files.writeString(aliasesFile, String.join("\n",
                "CLASS\tnet/minecraft/OldBar\tnet/minecraft/Bar",
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/OldBar;)V"
                        + "\tnet/minecraft/Foo\tcurrentMethod\t(Lnet/minecraft/Bar;)V",
                "FIELD\tnet/minecraft/Foo\toldField\tI\tnet/minecraft/Foo\tcurrentField\tI",
                ""));

        CompatibilityScanner.AliasSet aliases = CompatibilityScanner.AliasSet.load(aliasesFile);
        assertDoesNotThrow(() -> aliases.validateAgainstTarget(targetJar));
    }

    @Test
    void rejectsStaleMemberOrClassAliasTargetsBeforeRuntime() throws Exception {
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2-fixture.jar");
        Path missingMethod = temporaryDirectory.resolve("missing-method.tsv");
        Path missingClass = temporaryDirectory.resolve("missing-class.tsv");
        writeJar(targetJar,
                "net/minecraft/Foo", targetFoo(),
                "net/minecraft/Bar", emptyClass("net/minecraft/Bar"));
        Files.writeString(missingMethod,
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/Foo\trenamedWrong\t(Lnet/minecraft/Bar;)V\n");
        Files.writeString(missingClass, "CLASS\tnet/minecraft/OldBar\tnet/minecraft/RemovedBar\n");

        assertThrows(IOException.class,
                () -> CompatibilityScanner.AliasSet.load(missingMethod).validateAgainstTarget(targetJar));
        assertThrows(IOException.class,
                () -> CompatibilityScanner.AliasSet.load(missingClass).validateAgainstTarget(targetJar));
    }

    private static byte[] interfaceWithParent(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", new String[]{parent});
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceWithStaticMethod(String owner, String name, String descriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                owner, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyInterface(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithNewExpression(String owner, String methodName,
                                                String allocatedType, String constructorDescriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, allocatedType);
        method.visitInsn(Opcodes.DUP);
        org.objectweb.asm.Type constructorType = org.objectweb.asm.Type.getMethodType(constructorDescriptor);
        for (org.objectweb.asm.Type ignored : constructorType.getArgumentTypes()) {
            method.visitInsn(Opcodes.ACONST_NULL);
        }
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, allocatedType, "<init>",
                constructorDescriptor, false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] targetFoo() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/Foo", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "currentField", "I", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "currentMethod",
                "(Lnet/minecraft/Bar;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeJar(Path path, Object... keyValues) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (int i = 0; i < keyValues.length; i += 2) {
                String owner = (String) keyValues[i];
                byte[] classBytes = (byte[]) keyValues[i + 1];
                jar.putNextEntry(new JarEntry(owner + ".class"));
                jar.write(classBytes);
                jar.closeEntry();
            }
        }
    }
}
