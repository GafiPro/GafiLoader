package dev.gafipro.gafiloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinSelectorRemapperTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetGlobalMappings() {
        MixinResourceRemapper.configure(null, CompatibilityScanner.AliasSet.empty());
    }

    @Test
    void remapsImplicitAndExplicitMixinInjectionSelectorsInMemory() throws Exception {
        Path mojangFile = temporaryDirectory.resolve("client.txt");
        Path intermediaryFile = temporaryDirectory.resolve("intermediary.tiny");
        Files.writeString(mojangFile, String.join("\n",
                "net.minecraft.Foo -> a:",
                "    net.minecraft.Bar connection -> c",
                "    1:1:void oldMethod(net.minecraft.Bar):10:10 -> b",
                "net.minecraft.Bar -> d:",
                ""));
        Files.writeString(intermediaryFile, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tLd;\tc\tfield_3",
                "\tm\t(Ld;)V\tb\tmethod_2",
                "c\td\tnet/minecraft/class_2",
                ""));

        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediaryFile, MojangMappings.load(mojangFile));
        MixinResourceRemapper.configure(mappings, CompatibilityScanner.AliasSet.empty());

        byte[] original = mixinClassWithInjectionSelectors();
        byte[] originalSnapshot = original.clone();
        byte[] transformed = MixinResourceRemapper.transformClassBytes("example.FooMixin", original);

        assertNotEquals(null, transformed, "Expected annotation selectors to trigger remapping");
        assertArrayEquals(originalSnapshot, original, "Original Mixin class bytes must not be mutated");

        SelectorValues values = readSelectors(transformed);
        assertEquals("oldMethod(Lnet/minecraft/Bar;)V", values.implicitMethod());
        assertEquals("Lnet/minecraft/Foo;oldMethod(Lnet/minecraft/Bar;)V", values.explicitAtTarget());
        assertEquals("connection", values.accessorFieldName());
        assertTrue(values.mixinTarget().contains("net/minecraft/Foo"),
                "The standard ASM remapper should also normalize the class-valued @Mixin target");
    }

    @Test
    void recognizesHyphenatedAndUnderscoredRefmapResourceNames() {
        assertTrue(MixinResourceRemapper.isMixinResource("fabric-api-refmap.json"));
        assertTrue(MixinResourceRemapper.isMixinResource("fabric_api.refmap.json"));
        assertTrue(MixinResourceRemapper.isMixinResource("some.mixins.json"));
    }

    private static byte[] mixinClassWithInjectionSelectors() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/FooMixin", null,
                "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
        AnnotationVisitor mixinTargets = mixin.visitArray("value");
        mixinTargets.visit(null, Type.getObjectType("net/minecraft/class_1"));
        mixinTargets.visitEnd();
        mixin.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "inject", "()V", null, null);
        AnnotationVisitor inject = method.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methodSelectors = inject.visitArray("method");
        methodSelectors.visit(null, "method_2(Lnet/minecraft/class_2;)V");
        methodSelectors.visitEnd();

        AnnotationVisitor at = inject.visitAnnotation(
                "at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", "Lnet/minecraft/class_1;method_2(Lnet/minecraft/class_2;)V");
        at.visitEnd();
        inject.visitEnd();

        MethodVisitor accessorMethod = writer.visitMethod(Opcodes.ACC_PUBLIC, "getConnection",
                "()Lnet/minecraft/class_2;", null, null);
        AnnotationVisitor accessor = accessorMethod.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        accessor.visit("value", "field_3");
        accessor.visitEnd();
        accessorMethod.visitEnd();

        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static SelectorValues readSelectors(byte[] bytes) {
        String[] mixinTarget = {""};
        String[] implicitMethod = {""};
        String[] explicitAtTarget = {""};
        String[] accessorFieldName = {""};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (descriptor.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String ignored, Object value) {
                                    if (value instanceof Type type) {
                                        mixinTarget[0] = type.getInternalName();
                                    }
                                }
                            };
                        }
                    };
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String name, Object value) {
                                    if (name.equals("value") && value instanceof String stringValue) {
                                        accessorFieldName[0] = stringValue;
                                    }
                                }
                            };
                        }
                        if (!annotationDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String elementName) {
                                if (!elementName.equals("method")) {
                                    return null;
                                }
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String stringValue) {
                                            implicitMethod[0] = stringValue;
                                        }
                                    }
                                };
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String elementName,
                                                                      String nestedDescriptor) {
                                if (!elementName.equals("at")
                                        || !nestedDescriptor.equals("Lorg/spongepowered/asm/mixin/injection/At;")) {
                                    return null;
                                }
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String atElement, Object value) {
                                        if (atElement.equals("target") && value instanceof String stringValue) {
                                            explicitAtTarget[0] = stringValue;
                                        }
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, 0);
        return new SelectorValues(mixinTarget[0], implicitMethod[0], explicitAtTarget[0],
                accessorFieldName[0]);
    }

    private record SelectorValues(String mixinTarget, String implicitMethod, String explicitAtTarget,
                                  String accessorFieldName) {}
}
