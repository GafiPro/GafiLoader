package dev.gafipro.gafiloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.util.Set;

/**
 * Ports the 1.21.11 Fabric model-loading module's BlockModel integration to
 * the corresponding 26.2 CuboidModel integration. The rules mirror the
 * upstream 26.2 Fabric API implementation and are scoped to this module only.
 */
final class LegacyFabricModelLoadingApiAdapter {
    static final String MODULE_ID = "fabric-model-loading-api-v1";

    private static final String OLD_BLOCK_MODEL =
            "net/minecraft/client/renderer/block/model/BlockModel";
    private static final String NEW_CUBOID_MODEL =
            "net/minecraft/client/resources/model/cuboid/CuboidModel";
    private static final String OLD_BLOCK_MODEL_DESCRIPTOR = "L" + OLD_BLOCK_MODEL + ";";
    private static final String NEW_CUBOID_MODEL_DESCRIPTOR = "L" + NEW_CUBOID_MODEL + ";";

    private static final String MODEL_MANAGER_MIXIN =
            "net/fabricmc/fabric/mixin/client/model/loading/ModelManagerMixin.class";
    private static final String MIXIN_LOCAL_DESCRIPTOR =
            "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String MIXIN_AT_DESCRIPTOR =
            "Lorg/spongepowered/asm/mixin/injection/At;";

    private static final Set<String> ADAPTED_CLASSES = Set.of(
            "net/fabricmc/fabric/mixin/client/model/loading/BlockModelMixin.class",
            "net/fabricmc/fabric/mixin/client/model/loading/BlockModelAccessor.class",
            MODEL_MANAGER_MIXIN,
            "net/fabricmc/fabric/impl/client/model/loading/UnbakedModelJsonDeserializer.class"
    );

    private LegacyFabricModelLoadingApiAdapter() {}

    static Result adapt(String archiveEntryName, byte[] originalBytes) throws IOException {
        String entryName = archiveEntryName == null ? "" : archiveEntryName.replace('\\', '/');
        if (!ADAPTED_CLASSES.contains(entryName)) {
            return new Result(originalBytes, false);
        }

        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            Remapper modelTypeRemapper = new Remapper() {
                @Override
                public String map(String internalName) {
                    return OLD_BLOCK_MODEL.equals(internalName) ? NEW_CUBOID_MODEL : internalName;
                }
            };
            ClassVisitor remappedClass = new ClassRemapper(writer, modelTypeRemapper);
            boolean isModelManagerMixin = MODEL_MANAGER_MIXIN.equals(entryName);

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, remappedClass) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!isModelManagerMixin || delegate == null) {
                        return delegate;
                    }

                    boolean hookModelCollect = "hookModelCollect".equals(name);
                    boolean resolveExtraModels = "resolveExtraModels".equals(name);
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                            AnnotationVisitor annotation = super.visitAnnotation(annotationDescriptor, visible);
                            return annotation == null ? null
                                    : rewriteAnnotationValues(annotation, hookModelCollect, false);
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter,
                                String annotationDescriptor, boolean visible) {
                            AnnotationVisitor annotation =
                                    super.visitParameterAnnotation(parameter, annotationDescriptor, visible);
                            if (annotation == null) {
                                return null;
                            }
                            if (resolveExtraModels && parameter == 4
                                    && MIXIN_LOCAL_DESCRIPTOR.equals(annotationDescriptor)) {
                                return ensureLocalVariableName(annotation, "result");
                            }
                            return annotation;
                        }
                    };
                }
            };

            reader.accept(visitor, 0);
            byte[] adapted = writer.toByteArray();
            return new Result(adapted, !java.util.Arrays.equals(originalBytes, adapted));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IOException("Could not adapt the 1.21.11 Fabric model-loading mixin "
                    + entryName + " for Minecraft 26.2.", exception);
        }
    }

    private static AnnotationVisitor rewriteAnnotationValues(AnnotationVisitor downstream,
            boolean updateCollectOrdinal, boolean isAtAnnotation) {
        return new AnnotationVisitor(Opcodes.ASM9, downstream) {
            @Override
            public void visit(String name, Object value) {
                Object mapped = value;
                if (value instanceof String text) {
                    text = text.replace(OLD_BLOCK_MODEL_DESCRIPTOR, NEW_CUBOID_MODEL_DESCRIPTOR);
                    text = text.replace("method_65750(",
                            "lambda$loadBlockModels$2(");
                    mapped = text;
                } else if (isAtAnnotation && updateCollectOrdinal && "ordinal".equals(name)
                        && value instanceof Integer ordinal && ordinal == 1) {
                    // The 26.2 ModelManager has one matching thenApplyAsync call at this site,
                    // whereas the 1.21.11 implementation selected the second occurrence.
                    mapped = 0;
                }
                super.visit(name, mapped);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                AnnotationVisitor nested = super.visitAnnotation(name, descriptor);
                return nested == null ? null : rewriteAnnotationValues(nested,
                        updateCollectOrdinal, MIXIN_AT_DESCRIPTOR.equals(descriptor));
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor nested = super.visitArray(name);
                return nested == null ? null : rewriteAnnotationValues(nested, updateCollectOrdinal, false);
            }
        };
    }

    private static AnnotationVisitor ensureLocalVariableName(AnnotationVisitor downstream, String localName) {
        return new AnnotationVisitor(Opcodes.ASM9, downstream) {
            private boolean hasName;

            @Override
            public void visit(String name, Object value) {
                if ("name".equals(name)) {
                    hasName = true;
                }
                super.visit(name, value);
            }

            @Override
            public void visitEnd() {
                if (!hasName) {
                    super.visit("name", localName);
                }
                super.visitEnd();
            }
        };
    }

    record Result(byte[] bytes, boolean changed) {}
}
