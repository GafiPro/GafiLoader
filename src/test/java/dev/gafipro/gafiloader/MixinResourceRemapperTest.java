package dev.gafipro.gafiloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinResourceRemapperTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetGlobalMappings() {
        MixinResourceRemapper.configure(null, CompatibilityScanner.AliasSet.empty());
    }

    @Test
    void remapsMixinTargetsAndRefmapSelectorsInMemory() throws Exception {
        Path mojangFile = temporaryDirectory.resolve("client.txt");
        Path intermediaryFile = temporaryDirectory.resolve("intermediary.tiny");
        Path aliasesFile = temporaryDirectory.resolve("aliases.tsv");

        Files.writeString(mojangFile, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediaryFile, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));
        Files.writeString(aliasesFile, String.join("\n",
                "METHOD\tnet/minecraft/Foo\toldMethod\t(Lnet/minecraft/Bar;)V"
                        + "\tnet/minecraft/Foo\tcurrentMethod\t(Lnet/minecraft/Bar;)V",
                "FIELD\tnet/minecraft/Foo\toldField\tI\tnet/minecraft/Foo\tcurrentField\tI",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediaryFile, MojangMappings.load(mojangFile));
        CompatibilityScanner.AliasSet aliases = CompatibilityScanner.AliasSet.load(aliasesFile);
        MixinResourceRemapper.configure(mappings, aliases);

        String json = """
                {
                  "target": "Lnet/minecraft/class_1;method_2(Lnet/minecraft/class_2;)V",
                  "field": "Lnet/minecraft/class_1;field_1:I",
                  "package": "example.mixins",
                  "nested": ["net.minecraft.class_1"]
                }
                """;
        byte[] source = json.getBytes(StandardCharsets.UTF_8);
        InputStream resultStream = MixinResourceRemapper.transform(
                "example.refmap.json", new ByteArrayInputStream(source));
        String result = new String(resultStream.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(result.contains("Lnet/minecraft/Foo;currentMethod(Lnet/minecraft/Bar;)V"), result);
        assertTrue(result.contains("Lnet/minecraft/Foo;currentField:I"), result);
        assertTrue(result.contains("net.minecraft.Foo"), result);
        assertFalse(result.contains("class_1"), result);
        assertFalse(result.contains("method_2"), result);
        assertFalse(result.contains("field_1"), result);
        // The incoming resource bytes are independent from the returned in-memory view.
        assertTrue(new String(source, StandardCharsets.UTF_8).contains("class_1"));
    }

    @Test
    void remapsLegacyClassBytecodeBeforeMixinInspectsItWithoutMutatingSourceBytes() throws Exception {
        Path mojangFile = temporaryDirectory.resolve("mixin-client.txt");
        Path intermediaryFile = temporaryDirectory.resolve("mixin-intermediary.tiny");
        Files.writeString(mojangFile, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediaryFile, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediaryFile, MojangMappings.load(mojangFile));
        MixinResourceRemapper.configure(mappings, CompatibilityScanner.AliasSet.empty());

        byte[] original = legacyMixinClass();
        byte[] originalSnapshot = original.clone();
        byte[] normalized = MixinResourceRemapper.transformClassBytes(
                "example.LegacyMixin", original);
        byte[] normalizedThroughResource;
        try (InputStream stream = MixinResourceRemapper.transform(
                "example/LegacyMixin.class", new ByteArrayInputStream(originalSnapshot))) {
            normalizedThroughResource = stream.readAllBytes();
        }

        assertFalse(java.util.Arrays.equals(originalSnapshot, normalized),
                "Mixin should receive normalized bytes for legacy class metadata");
        assertArrayEquals(originalSnapshot, original,
                "The source class bytes must remain unchanged");
        assertArrayEquals(normalized, normalizedThroughResource,
                "The resource hook should apply the same transformation to class resources");

        java.util.concurrent.atomic.AtomicBoolean mappedField = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean mappedMethod = new java.util.concurrent.atomic.AtomicBoolean();
        new ClassReader(normalized).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (owner.equals("net/minecraft/Foo") && fieldName.equals("oldField")
                                && fieldDescriptor.equals("I")) {
                            mappedField.set(true);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (owner.equals("net/minecraft/Foo") && methodName.equals("oldMethod")
                                && methodDescriptor.equals("(Lnet/minecraft/Bar;)V")) {
                            mappedMethod.set(true);
                        }
                    }
                };
            }
        }, 0);
        assertTrue(mappedField.get(), "Expected Intermediary field owner/name normalization");
        assertTrue(mappedMethod.get(), "Expected Intermediary method owner/name/descriptor normalization");
    }

    private static byte[] legacyMixinClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/LegacyMixin", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "invoke", "(Lnet/minecraft/class_1;Lnet/minecraft/class_2;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_1", "field_1", "I");
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1", "method_2",
                "(Lnet/minecraft/class_2;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void remapsLegacyClassTweakerEntriesInMemoryWithoutMutatingTheSourceFile() throws Exception {
        Path mojangFile = temporaryDirectory.resolve("classtweaker-client.txt");
        Path intermediaryFile = temporaryDirectory.resolve("classtweaker-intermediary.tiny");
        Files.writeString(mojangFile, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    int oldField -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediaryFile, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tI\tc\tfield_1",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediaryFile, MojangMappings.load(mojangFile));
        MixinResourceRemapper.configure(mappings, CompatibilityScanner.AliasSet.empty());

        Path sourceFile = temporaryDirectory.resolve("legacy.classtweaker");
        String original = String.join("\n",
                "classTweaker v1 intermediary",
                "accessible class net/minecraft/class_1 # preserve this comment",
                "accessible method net/minecraft/class_1 method_2 (Lnet/minecraft/class_2;)V",
                "mutable field net/minecraft/class_1 field_1 I",
                "inject-interface net/minecraft/class_1 example/LegacyInterface<Lnet/minecraft/class_2;>",
                "");
        Files.writeString(sourceFile, original, StandardCharsets.UTF_8);

        String transformed;
        try (BufferedReader reader = MixinResourceRemapper.openClassTweakerReader(sourceFile)) {
            transformed = reader.lines().collect(Collectors.joining("\n"));
        }

        assertTrue(transformed.startsWith("classTweaker v1 official"), transformed);
        assertTrue(transformed.contains("accessible class net/minecraft/Foo # preserve this comment"), transformed);
        assertTrue(transformed.contains(
                "accessible method net/minecraft/Foo oldMethod (Lnet/minecraft/Bar;)V"), transformed);
        assertTrue(transformed.contains("mutable field net/minecraft/Foo oldField I"), transformed);
        assertTrue(transformed.contains(
                "inject-interface net/minecraft/Foo example/LegacyInterface<Lnet/minecraft/Bar;>"), transformed);
        assertEquals(original, Files.readString(sourceFile, StandardCharsets.UTF_8),
                "The downloaded mod/API file must remain byte-for-byte intact");
    }

    @Test
    void skipsClassMixinWhenItsTargetBecameAnInterfaceInTheTargetGame() throws Exception {
        Path mojangFile = temporaryDirectory.resolve("interface-client.txt");
        Path intermediaryFile = temporaryDirectory.resolve("interface-intermediary.tiny");
        Path targetJar = temporaryDirectory.resolve("interface-target.jar");
        Files.writeString(mojangFile, String.join("\n",
                "net.minecraft.BlockModel -> a:",
                "net.minecraft.SystemReport -> b:",
                ""));
        Files.writeString(intermediaryFile, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "c\tb\tnet/minecraft/class_2",
                ""));

        byte[] targetInterface = interfaceClass("net/minecraft/BlockModel");
        byte[] targetSystemReport = ordinaryClass("net/minecraft/SystemReport");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(targetJar))) {
            jar.putNextEntry(new ZipEntry("net/minecraft/BlockModel.class"));
            jar.write(targetInterface);
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("net/minecraft/SystemReport.class"));
            jar.write(targetSystemReport);
            jar.closeEntry();
        }

        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediaryFile, MojangMappings.load(mojangFile));
        TargetApiIndex targetApi = TargetApiIndex.load(targetJar);
        MixinResourceRemapper.configure(mappings, CompatibilityScanner.AliasSet.empty(), targetApi);

        byte[] incompatibleClass = classMixinTargeting("example/mixins/BlockModelMixin",
                "net/minecraft/class_1");
        byte[] skippedBaseMixin = classMixinTargeting("example/mixins/SkippedBaseMixin",
                "net/minecraft/class_1");
        byte[] childOfSkippedMixin = classMixinTargetingWithSuper("example/mixins/ChildMixin",
                "example/mixins/SkippedBaseMixin", "net/minecraft/class_2");
        byte[] missingShadowClass = classMixinWithShadow("example/mixins/SystemReportMixin",
                "net/minecraft/class_2", "setDetail", "(Ljava/lang/String;Ljava/util/function/Supplier;)V");
        byte[] missingInjectionClass = classMixinWithMissingInjection(
                "example/mixins/SystemReportInjectionMixin", "net/minecraft/class_2", "destroy()V");
        ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader resourceLoader = new ClassLoader(originalLoader) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals("example/mixins/BlockModelMixin.class")) {
                    return new ByteArrayInputStream(incompatibleClass);
                }
                if (name.equals("example/mixins/SkippedBaseMixin.class")) {
                    return new ByteArrayInputStream(skippedBaseMixin);
                }
                if (name.equals("example/mixins/ChildMixin.class")) {
                    return new ByteArrayInputStream(childOfSkippedMixin);
                }
                if (name.equals("example/mixins/SystemReportMixin.class")) {
                    return new ByteArrayInputStream(missingShadowClass);
                }
                if (name.equals("example/mixins/SystemReportInjectionMixin.class")) {
                    return new ByteArrayInputStream(missingInjectionClass);
                }
                return super.getResourceAsStream(name);
            }
        };

        String config = """
                {
                  "package": "example.mixins",
                  "client": ["BlockModelMixin", "SkippedBaseMixin", "SystemReportMixin",
                             "SystemReportInjectionMixin", "ChildMixin", "UnchangedMixin"]
                }
                """;
        String transformed;
        try {
            Thread.currentThread().setContextClassLoader(resourceLoader);
            try (InputStream stream = MixinResourceRemapper.transform(
                    "fabric-model-loading-api-v1.mixins.json",
                    new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)))) {
                transformed = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalLoader);
        }

        assertFalse(transformed.contains("BlockModelMixin"), transformed);
        assertFalse(transformed.contains("SkippedBaseMixin"), transformed);
        assertFalse(transformed.contains("ChildMixin"), transformed);
        assertFalse(transformed.contains("SystemReportMixin"), transformed);
        assertFalse(transformed.contains("SystemReportInjectionMixin"), transformed);
        assertTrue(transformed.contains("UnchangedMixin"), transformed);
        assertTrue(transformed.contains("\"client\""), transformed);
        assertTrue(config.contains("BlockModelMixin"),
                "The original mixin-config text must remain unchanged");
    }

    private static byte[] interfaceClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] ordinaryClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classMixinWithMissingInjection(String internalName, String targetInternalName,
                                                         String selector) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor injectMethod = writer.visitMethod(Opcodes.ACC_PUBLIC, "injected", "()V", null, null);
        AnnotationVisitor inject = injectMethod.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();
        inject.visitEnd();
        injectMethod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classMixinWithShadow(String internalName, String targetInternalName,
                                               String shadowName, String shadowDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, internalName, null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor shadow = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                shadowName, shadowDescriptor, null, null);
        shadow.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        shadow.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classMixinTargetingWithSuper(String internalName, String superName,
                                                       String targetInternalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classMixinTargeting(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void leavesUnrelatedResourcesAlone() {
        InputStream original = new ByteArrayInputStream("unchanged".getBytes(StandardCharsets.UTF_8));
        MixinResourceRemapper.configure(null, CompatibilityScanner.AliasSet.empty());

        assertFalse(MixinResourceRemapper.isMixinResource("assets/example/lang/en_us.json"));
        assertSame(original, MixinResourceRemapper.transform("assets/example/lang/en_us.json", original));
    }
}
