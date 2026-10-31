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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFabricModelLoadingApiAdapterTest {
    private static final String OLD_BLOCK_MODEL =
            "net/minecraft/client/renderer/block/model/BlockModel";
    private static final String NEW_CUBOID_MODEL =
            "net/minecraft/client/resources/model/cuboid/CuboidModel";
    private static final String MIXIN_DESCRIPTOR = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String MODIFY_ARG_DESCRIPTOR =
            "Lcom/llamalad7/mixinextras/injector/ModifyArg;";
    private static final String REDIRECT_DESCRIPTOR =
            "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String AT_DESCRIPTOR =
            "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String LOCAL_DESCRIPTOR =
            "Lcom/llamalad7/mixinextras/sugar/Local;";

    @TempDir
    Path temporaryDirectory;

    @Test
    void portsTheLegacyBlockModelMixinAndAccessorToCuboidModel() throws Exception {
        byte[] mixin = classWithMixinTarget(
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelMixin", OLD_BLOCK_MODEL);
        byte[] accessor = classWithMixinTarget(
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelAccessor", OLD_BLOCK_MODEL);

        LegacyFabricModelLoadingApiAdapter.Result adaptedMixin =
                LegacyFabricModelLoadingApiAdapter.adapt(
                        "net/fabricmc/fabric/mixin/client/model/loading/BlockModelMixin.class", mixin);
        LegacyFabricModelLoadingApiAdapter.Result adaptedAccessor =
                LegacyFabricModelLoadingApiAdapter.adapt(
                        "net/fabricmc/fabric/mixin/client/model/loading/BlockModelAccessor.class", accessor);

        assertTrue(adaptedMixin.changed());
        assertTrue(adaptedAccessor.changed());
        assertEquals(NEW_CUBOID_MODEL, mixinTarget(adaptedMixin.bytes()));
        assertEquals(NEW_CUBOID_MODEL, mixinTarget(adaptedAccessor.bytes()));
    }

    @Test
    void updatesTheModelManagerMixinTargetsOrdinalAndLocalCaptureToThe262Implementation() throws Exception {
        byte[] original = modelManagerMixinClass();
        LegacyFabricModelLoadingApiAdapter.Result adapted =
                LegacyFabricModelLoadingApiAdapter.adapt(
                        "net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin.class", original);

        assertTrue(adapted.changed());
        assertEquals("()L" + NEW_CUBOID_MODEL + ";", modelManagerReturnType(adapted.bytes()));

        AtomicReference<String> redirectMethod = new AtomicReference<>();
        AtomicReference<String> redirectTarget = new AtomicReference<>();
        AtomicReference<Integer> collectOrdinal = new AtomicReference<>();
        AtomicReference<String> localName = new AtomicReference<>();

        new ClassReader(adapted.bytes()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if ("cancelVanillaDeserialize".equals(name)
                                && REDIRECT_DESCRIPTOR.equals(annotationDescriptor)) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String valueName, Object value) {
                                    if ("method".equals(valueName)) redirectMethod.set((String) value);
                                }

                                @Override
                                public AnnotationVisitor visitAnnotation(String valueName, String valueDescriptor) {
                                    if (!"at".equals(valueName) || !AT_DESCRIPTOR.equals(valueDescriptor)) {
                                        return null;
                                    }
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String atName, Object value) {
                                            if ("target".equals(atName)) redirectTarget.set((String) value);
                                        }
                                    };
                                }
                            };
                        }
                        if ("hookModelCollect".equals(name)
                                && MODIFY_ARG_DESCRIPTOR.equals(annotationDescriptor)) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public AnnotationVisitor visitAnnotation(String valueName, String valueDescriptor) {
                                    if (!"at".equals(valueName) || !AT_DESCRIPTOR.equals(valueDescriptor)) {
                                        return null;
                                    }
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String atName, Object value) {
                                            if ("ordinal".equals(atName)) collectOrdinal.set((Integer) value);
                                        }
                                    };
                                }
                            };
                        }
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor,
                                                                      boolean visible) {
                        if ("resolveExtraModels".equals(name) && parameter == 4
                                && LOCAL_DESCRIPTOR.equals(annotationDescriptor)) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String valueName, Object value) {
                                    if ("name".equals(valueName)) localName.set((String) value);
                                }
                            };
                        }
                        return null;
                    }
                };
            }
        }, 0);

        assertEquals("lambda$loadBlockModels$2(Ljava/util/Map$Entry;)Lcom/mojang/datafixers/util/Pair;",
                redirectMethod.get());
        assertEquals("L" + NEW_CUBOID_MODEL + ";fromStream(Ljava/io/Reader;)L" + NEW_CUBOID_MODEL + ";",
                redirectTarget.get());
        assertEquals(0, collectOrdinal.get());
        assertEquals("result", localName.get());
    }

    @Test
    void updatesTheNestedLegacyFabricApiArchiveAndPreservesAllOriginalJars() throws Exception {
        Path gameDirectory = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path modsDirectory = Files.createDirectories(gameDirectory.resolve("mods"));

        byte[] modelMixin = classWithMixinTarget(
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelMixin", OLD_BLOCK_MODEL);
        byte[] accessor = classWithMixinTarget(
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelAccessor", OLD_BLOCK_MODEL);
        byte[] managerMixin = modelManagerMixinClass();
        byte[] deserializer = classWithModelClassLiteral(
                "net/fabricmc/fabric/impl/client/model/loading/UnbakedModelJsonDeserializer", OLD_BLOCK_MODEL);

        byte[] modelLoadingModule = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-model-loading-api-v1","version":"0.141.6+1.21.11"}
                        """.getBytes(StandardCharsets.UTF_8),
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelMixin.class", modelMixin,
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelAccessor.class", accessor,
                "net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin.class", managerMixin,
                "net/fabricmc/fabric/impl/client/model/loading/UnbakedModelJsonDeserializer.class", deserializer));
        byte[] originalFabricApi = jar(Map.of(
                "fabric.mod.json", """
                        {"schemaVersion":1,"id":"fabric-api","version":"0.141.6+1.21.11"}
                        """.getBytes(StandardCharsets.UTF_8),
                "META-INF/jars/fabric-model-loading-api-v1.jar", modelLoadingModule));
        Path source = modsDirectory.resolve("fabric-api.jar");
        Files.write(source, originalFabricApi);

        LegacyModCompatibilityTransformer.Result result =
                new LegacyModCompatibilityTransformer(createBytecodeMappings()).prepare(modsDirectory, gameDirectory);

        assertTrue(result.transformed());
        assertEquals(4, result.adaptedModelLoadingClasses());
        assertEquals(1, result.modifiedJars());
        assertArrayEquals(originalFabricApi, Files.readAllBytes(source),
                "GafiLoader must not change the source Fabric API JAR.");

        byte[] stagedOuter = Files.readAllBytes(result.modsDirectory().resolve("fabric-api.jar"));
        byte[] stagedModule = readEntry(stagedOuter, "META-INF/jars/fabric-model-loading-api-v1.jar");
        byte[] stagedMixin = readEntry(stagedModule,
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelMixin.class");
        byte[] stagedAccessor = readEntry(stagedModule,
                "net/fabricmc/fabric/mixin/client/model/loading/BlockModelAccessor.class");
        byte[] stagedDeserializer = readEntry(stagedModule,
                "net/fabricmc/fabric/impl/client/model/loading/UnbakedModelJsonDeserializer.class");

        assertEquals(NEW_CUBOID_MODEL, mixinTarget(stagedMixin));
        assertEquals(NEW_CUBOID_MODEL, mixinTarget(stagedAccessor));
        assertEquals(NEW_CUBOID_MODEL, firstLdcClass(stagedDeserializer));
    }

    @Test
    void leavesClassesOutsideTheModelLoadingAdapterAllowlistUntouched() throws Exception {
        byte[] original = classWithMixinTarget("example/UnrelatedMixin", OLD_BLOCK_MODEL);

        LegacyFabricModelLoadingApiAdapter.Result result =
                LegacyFabricModelLoadingApiAdapter.adapt("example/UnrelatedMixin.class", original);

        assertFalse(result.changed());
        assertArrayEquals(original, result.bytes());
    }

    private LegacyClassTweakerMappings createBytecodeMappings() throws IOException {
        Path intermediaryJar = temporaryDirectory.resolve("intermediary-fixture.jar");
        byte[] tiny = ("tiny\t2\t0\tofficial\tintermediary\n"
                + "c\ta\tnet/minecraft/class_7924\n"
                + "\tf\tLa;\tb\tfield_1234\n"
                + "\tm\t(La;)La;\tc\tmethod_2345\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(intermediaryJar, jar(Map.of("mappings/mappings.tiny", tiny)));

        Path mojangMappings = temporaryDirectory.resolve("client-mappings-fixture.txt");
        Files.writeString(mojangMappings, """
                net.minecraft.core.Registry -> a:
                    net.minecraft.core.Registry entry -> b
                    net.minecraft.core.Registry lookup(net.minecraft.core.Registry) -> c
                """, StandardCharsets.UTF_8);
        return LegacyClassTweakerMappings.load(intermediaryJar, mojangMappings);
    }

    private static byte[] classWithMixinTarget(String internalName, String target) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_DESCRIPTOR, true);
        mixin.visit("value", Type.getObjectType(target));
        mixin.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithModelClassLiteral(String internalName, String target) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "fallbackType", "()Ljava/lang/Class;", null, null);
        method.visitCode();
        method.visitLdcInsn(Type.getObjectType(target));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] modelManagerMixinClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/ModelManagerMixinFixture", null,
                "java/lang/Object", null);

        MethodVisitor redirect = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "cancelVanillaDeserialize", "()L" + OLD_BLOCK_MODEL + ";", null, null);
        AnnotationVisitor redirectAnnotation = redirect.visitAnnotation(REDIRECT_DESCRIPTOR, true);
        redirectAnnotation.visit("method",
                "method_65750(Ljava/util/Map$Entry;)Lcom/mojang/datafixers/util/Pair;");
        AnnotationVisitor redirectAt = redirectAnnotation.visitAnnotation("at", AT_DESCRIPTOR);
        redirectAt.visit("value", "INVOKE");
        redirectAt.visit("target", "L" + OLD_BLOCK_MODEL + ";fromStream(Ljava/io/Reader;)L"
                + OLD_BLOCK_MODEL + ";");
        redirectAt.visitEnd();
        redirectAnnotation.visitEnd();
        redirect.visitCode();
        redirect.visitInsn(Opcodes.ACONST_NULL);
        redirect.visitInsn(Opcodes.ARETURN);
        redirect.visitMaxs(1, 0);
        redirect.visitEnd();

        MethodVisitor collect = writer.visitMethod(Opcodes.ACC_PRIVATE,
                "hookModelCollect", "()V", null, null);
        AnnotationVisitor modifyArg = collect.visitAnnotation(MODIFY_ARG_DESCRIPTOR, true);
        AnnotationVisitor modifyAt = modifyArg.visitAnnotation("at", AT_DESCRIPTOR);
        modifyAt.visit("value", "INVOKE");
        modifyAt.visit("target",
                "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;");
        modifyAt.visit("ordinal", 1);
        modifyAt.visitEnd();
        modifyArg.visitEnd();
        collect.visitCode();
        collect.visitInsn(Opcodes.RETURN);
        collect.visitMaxs(0, 1);
        collect.visitEnd();

        MethodVisitor resolve = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "resolveExtraModels",
                "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                null, null);
        resolve.visitParameterAnnotation(4, LOCAL_DESCRIPTOR, true).visitEnd();
        resolve.visitCode();
        resolve.visitInsn(Opcodes.RETURN);
        resolve.visitMaxs(0, 5);
        resolve.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String mixinTarget(byte[] classBytes) {
        AtomicReference<String> target = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_DESCRIPTOR.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof Type type) {
                            target.set(type.getInternalName());
                        }
                    }
                };
            }
        }, 0);
        return target.get();
    }

    private static String modelManagerReturnType(byte[] classBytes) {
        AtomicReference<String> descriptor = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String methodDescriptor,
                                             String signature, String[] exceptions) {
                if ("cancelVanillaDeserialize".equals(name)) descriptor.set(methodDescriptor);
                return null;
            }
        }, 0);
        return descriptor.get();
    }

    private static String firstLdcClass(byte[] classBytes) {
        AtomicReference<String> target = new AtomicReference<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (target.get() == null && value instanceof Type type) {
                            target.set(type.getInternalName());
                        }
                    }
                };
            }
        }, 0);
        return target.get();
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

    private static byte[] readEntry(byte[] archive, String requestedEntry) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(requestedEntry)) {
                    return input.readAllBytes();
                }
            }
        }
        throw new IOException("Missing test archive entry: " + requestedEntry);
    }
}
