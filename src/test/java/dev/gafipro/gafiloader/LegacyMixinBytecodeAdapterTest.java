package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMixinBytecodeAdapterTest {
    private static final String TARGET_OWNER = "net/minecraft/class_7924";
    private static final String TARGET_NAMED = "net/minecraft/core/Registry";
    private static final String MIXIN_CLASS = "net/fabricmc/fabric/mixin/registry/LegacyRegistryMixin";

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String MODIFY_RETURN_VALUE =
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    @TempDir
    Path temporaryDirectory;

    @Test
    void remapsMixinShadowDeclarationsAndInjectionSelectorsAgainstTheMixinTarget() throws Exception {
        LegacyClassTweakerMappings mappings = createMappings();
        byte[] original = legacyMixinClass();

        LegacyMixinBytecodeAdapter.Result result =
                LegacyMixinBytecodeAdapter.remap(original, mappings);

        assertTrue(result.changed());
        AtomicReference<String> mixinTarget = new AtomicReference<>();
        AtomicReference<String> shadowFieldName = new AtomicReference<>();
        AtomicReference<String> shadowFieldDescriptor = new AtomicReference<>();
        AtomicReference<String> shadowMethodName = new AtomicReference<>();
        AtomicReference<String> shadowMethodDescriptor = new AtomicReference<>();
        AtomicReference<String> injectionMethodSelector = new AtomicReference<>();
        AtomicReference<String> atTargetSelector = new AtomicReference<>();

        new ClassReader(result.bytes()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof Type type) {
                            mixinTarget.set(type.getInternalName());
                        }
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (name.equals("entry")) {
                    shadowFieldName.set(name);
                    shadowFieldDescriptor.set(descriptor);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("lookup".equals(name)) {
                    shadowMethodName.set(name);
                    shadowMethodDescriptor.set(descriptor);
                }
                if (!"onLookup".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!MODIFY_RETURN_VALUE.equals(annotationDescriptor)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String property, Object value) {
                                if ("method".equals(property)) injectionMethodSelector.set((String) value);
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String property, String nestedDescriptor) {
                                if (!"at".equals(property) || !AT.equals(nestedDescriptor)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String atProperty, Object value) {
                                        if ("target".equals(atProperty)) atTargetSelector.set((String) value);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, 0);

        String mappedMethodDescriptor = "(L" + TARGET_NAMED + ";)L" + TARGET_NAMED + ";";
        assertEquals(TARGET_NAMED, mixinTarget.get());
        assertEquals("entry", shadowFieldName.get());
        assertEquals("L" + TARGET_NAMED + ";", shadowFieldDescriptor.get());
        assertEquals("lookup", shadowMethodName.get());
        assertEquals(mappedMethodDescriptor, shadowMethodDescriptor.get());
        assertEquals("lookup" + mappedMethodDescriptor, injectionMethodSelector.get());
        assertEquals("L" + TARGET_NAMED + ";lookup" + mappedMethodDescriptor, atTargetSelector.get());
    }

    @Test
    void remapsImplicitAccessorInvokerAndCustomShadowPrefixWhileHonouringRemapFalse() throws Exception {
        LegacyClassTweakerMappings mappings = createMappings();
        LegacyMixinBytecodeAdapter.Result result = LegacyMixinBytecodeAdapter.remap(
                mixinWithImplicitAccessorsAndCustomShadowPrefix(), mappings);

        AtomicReference<String> shadowMethod = new AtomicReference<>();
        AtomicReference<String> accessorMethod = new AtomicReference<>();
        AtomicReference<String> invokerMethod = new AtomicReference<>();
        AtomicReference<String> nonRemappedAccessorValue = new AtomicReference<>();

        new ClassReader(result.bytes()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("bridge$lookup".equals(name)) shadowMethod.set(name);
                if ("getEntry".equals(name)) accessorMethod.set(name);
                if ("callLookup".equals(name)) invokerMethod.set(name);
                if (!"getUnmappedField".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!"Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(annotationDescriptor)) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String property, Object value) {
                                if ("value".equals(property)) nonRemappedAccessorValue.set((String) value);
                            }
                        };
                    }
                };
            }
        }, 0);

        assertEquals("bridge$lookup", shadowMethod.get(),
                "A custom @Shadow prefix must be retained while its target method is remapped.");
        assertEquals("getEntry", accessorMethod.get(),
                "An omitted @Accessor value must be inferred and remapped from the handler name.");
        assertEquals("callLookup", invokerMethod.get(),
                "An omitted @Invoker value must be inferred and remapped from the handler name.");
        assertEquals("field_1234", nonRemappedAccessorValue.get(),
                "@Accessor(remap=false) must not have its explicit target rewritten.");
    }

    private static byte[] mixinWithImplicitAccessorsAndCustomShadowPrefix() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "net/fabricmc/fabric/mixin/registry/LegacyAccessorMixin", null,
                "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, true);
        mixin.visit("value", Type.getObjectType(TARGET_OWNER));
        mixin.visitEnd();

        MethodVisitor shadow = writer.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT,
                "bridge$method_2345", "(L" + TARGET_OWNER + ";)L" + TARGET_OWNER + ";", null, null);
        AnnotationVisitor shadowAnnotation = shadow.visitAnnotation(SHADOW, true);
        shadowAnnotation.visit("prefix", "bridge$");
        shadowAnnotation.visitEnd();
        shadow.visitEnd();

        MethodVisitor accessor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getField_1234", "()L" + TARGET_OWNER + ";", null, null);
        accessor.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true).visitEnd();
        accessor.visitEnd();

        MethodVisitor invoker = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "callMethod_2345", "(L" + TARGET_OWNER + ";)L" + TARGET_OWNER + ";", null, null);
        invoker.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Invoker;", true).visitEnd();
        invoker.visitEnd();

        MethodVisitor unmapped = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getUnmappedField", "()L" + TARGET_OWNER + ";", null, null);
        AnnotationVisitor accessorAnnotation =
                unmapped.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        accessorAnnotation.visit("value", "field_1234");
        accessorAnnotation.visit("remap", false);
        accessorAnnotation.visitEnd();
        unmapped.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private LegacyClassTweakerMappings createMappings() throws IOException {
        Path intermediaryJar = temporaryDirectory.resolve("intermediary-mixin-fixture.jar");
        byte[] tiny = ("tiny\t2\t0\tofficial\tintermediary\n"
                + "c\ta\tnet/minecraft/class_7924\n"
                + "\tf\tLa;\tb\tfield_1234\n"
                + "\tm\t(La;)La;\tc\tmethod_2345\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(intermediaryJar, jar(Map.of("mappings/mappings.tiny", tiny)));

        Path mojangMappings = temporaryDirectory.resolve("client-mappings-mixin-fixture.txt");
        Files.writeString(mojangMappings, """
                net.minecraft.core.Registry -> a:
                    net.minecraft.core.Registry entry -> b
                    net.minecraft.core.Registry lookup(net.minecraft.core.Registry) -> c
                """, StandardCharsets.UTF_8);
        return LegacyClassTweakerMappings.load(intermediaryJar, mojangMappings);
    }

    private static byte[] legacyMixinClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                MIXIN_CLASS, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, true);
        mixin.visit("value", Type.getObjectType(TARGET_OWNER));
        mixin.visitEnd();

        FieldVisitor shadowField = writer.visitField(Opcodes.ACC_PRIVATE, "field_1234",
                "L" + TARGET_OWNER + ";", null, null);
        shadowField.visitAnnotation(SHADOW, true).visitEnd();
        shadowField.visitEnd();

        MethodVisitor shadowMethod = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "method_2345", "(L" + TARGET_OWNER + ";)L" + TARGET_OWNER + ";", null, null);
        shadowMethod.visitAnnotation(SHADOW, true).visitEnd();
        shadowMethod.visitEnd();

        MethodVisitor inject = writer.visitMethod(Opcodes.ACC_PRIVATE,
                "onLookup", "()V", null, null);
        AnnotationVisitor modify = inject.visitAnnotation(MODIFY_RETURN_VALUE, true);
        modify.visit("method", "method_2345(L" + TARGET_OWNER + ";)L" + TARGET_OWNER + ";");
        AnnotationVisitor at = modify.visitAnnotation("at", AT);
        at.visit("value", "INVOKE");
        at.visit("target", "L" + TARGET_OWNER + ";method_2345(L" + TARGET_OWNER + ";)L"
                + TARGET_OWNER + ";");
        at.visitEnd();
        modify.visitEnd();
        inject.visitCode();
        inject.visitInsn(Opcodes.RETURN);
        inject.visitMaxs(0, 1);
        inject.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (var entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
