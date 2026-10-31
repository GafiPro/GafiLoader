package dev.gafipro.gafiloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Remaps legacy bytecode and the Mixin metadata embedded in class files.
 *
 * <p>ASM's ordinary ClassRemapper cannot translate Mixin selector strings or
 * @Shadow declarations: those names are interpreted against the class declared
 * by @Mixin, not against the mixin class which owns the bytecode method. This
 * adapter keeps that target context while moving the class file to 26.2 names.</p>
 */
final class LegacyMixinBytecodeAdapter {
    private static final String MIXIN_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String AT_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String ACCESSOR_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String DEFAULT_SHADOW_PREFIX = "shadow$";

    private LegacyMixinBytecodeAdapter() {}

    static Result remap(byte[] originalBytes, LegacyClassTweakerMappings mappings) throws IOException {
        ClassReader reader;
        try {
            reader = new ClassReader(originalBytes);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Could not read a legacy class file before namespace conversion.", exception);
        }

        MixinMetadata metadata = inspectMixinMetadata(reader);
        String currentClass = reader.getClassName();
        try {
            ClassWriter writer = new ClassWriter(reader, 0);
            Remapper namespaceMappings = new Remapper() {
                @Override
                public String map(String internalName) {
                    try {
                        return mappings.mapClass(internalName);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    try {
                        if (owner.equals(currentClass) && metadata.shadowFields().contains(memberKey(name, descriptor))) {
                            return mapMixinDeclarationName(metadata, name, descriptor, false, mappings);
                        }
                        return mappings.mapBytecodeMemberName(owner, name, descriptor, false);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    try {
                        if (owner.equals(currentClass) && metadata.shadowMethods().contains(memberKey(name, descriptor))) {
                            return mapMixinDeclarationName(metadata, name, descriptor, true, mappings);
                        }
                        return mappings.mapBytecodeMemberName(owner, name, descriptor, true);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
            };

            ClassVisitor remappedClass = new ClassRemapper(writer, namespaceMappings);
            ClassVisitor mixinMetadataAdapter = new ClassVisitor(Opcodes.ASM9, remappedClass) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    AnnotationVisitor delegate = super.visitAnnotation(descriptor, visible);
                    return wrapAnnotation(delegate, descriptor, metadata, mappings);
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    FieldVisitor delegate = super.visitField(access, name, descriptor, signature, value);
                    if (delegate == null) return null;
                    return new FieldVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                            return wrapAnnotation(super.visitAnnotation(annotationDescriptor, visible),
                                    annotationDescriptor, metadata, mappings);
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (delegate == null) return null;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                            return wrapAnnotation(super.visitAnnotation(annotationDescriptor, visible),
                                    annotationDescriptor, metadata, mappings);
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter,
                                String annotationDescriptor, boolean visible) {
                            return wrapAnnotation(super.visitParameterAnnotation(parameter,
                                    annotationDescriptor, visible), annotationDescriptor, metadata, mappings);
                        }

                        @Override
                        public AnnotationVisitor visitInsnAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                            return wrapAnnotation(super.visitInsnAnnotation(typeRef, typePath,
                                    annotationDescriptor, visible), annotationDescriptor, metadata, mappings);
                        }

                        @Override
                        public AnnotationVisitor visitTryCatchAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                            return wrapAnnotation(super.visitTryCatchAnnotation(typeRef, typePath,
                                    annotationDescriptor, visible), annotationDescriptor, metadata, mappings);
                        }

                        @Override
                        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, org.objectweb.asm.Label[] start,
                                org.objectweb.asm.Label[] end, int[] index,
                                String annotationDescriptor, boolean visible) {
                            return wrapAnnotation(super.visitLocalVariableAnnotation(typeRef, typePath,
                                    start, end, index, annotationDescriptor, visible),
                                    annotationDescriptor, metadata, mappings);
                        }
                    };
                }

                @Override
                public void visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                String descriptor, boolean visible) {
                    super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
                }
            };

            reader.accept(mixinMetadataAdapter, 0);
            byte[] mapped = writer.toByteArray();
            return new Result(mapped, !Arrays.equals(originalBytes, mapped));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IOException("Could not remap legacy class bytecode and Mixin metadata for "
                    + currentClass + ".", exception);
        }
    }

    private static MixinMetadata inspectMixinMetadata(ClassReader reader) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> shadowMethods = new LinkedHashSet<>();
        Set<String> shadowFields = new LinkedHashSet<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof Type type
                                && type.getSort() == Type.OBJECT) {
                            targets.add(type.getInternalName());
                        } else if ("targets".equals(name) && value instanceof String target) {
                            targets.add(toInternalName(target));
                        }
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"value".equals(name) && !"targets".equals(name)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if ("value".equals(name) && value instanceof Type type
                                        && type.getSort() == Type.OBJECT) {
                                    targets.add(type.getInternalName());
                                } else if ("targets".equals(name) && value instanceof String target) {
                                    targets.add(toInternalName(target));
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (SHADOW_ANNOTATION.equals(annotationDescriptor)) {
                            shadowFields.add(memberKey(name, descriptor));
                        }
                        return null;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (SHADOW_ANNOTATION.equals(annotationDescriptor)
                                || OVERWRITE_ANNOTATION.equals(annotationDescriptor)) {
                            shadowMethods.add(memberKey(name, descriptor));
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return new MixinMetadata(reader.getClassName(), List.copyOf(targets),
                Set.copyOf(shadowMethods), Set.copyOf(shadowFields));
    }

    private static String mapMixinDeclarationName(MixinMetadata metadata, String originalName,
                                                   String descriptor, boolean method,
                                                   LegacyClassTweakerMappings mappings) throws IOException {
        String prefix = "";
        String lookupName = originalName;
        if (method && lookupName.startsWith(DEFAULT_SHADOW_PREFIX)
                && lookupName.substring(DEFAULT_SHADOW_PREFIX.length()).startsWith("method_")) {
            prefix = DEFAULT_SHADOW_PREFIX;
            lookupName = lookupName.substring(prefix.length());
        }

        if (!(method ? lookupName.startsWith("method_") : lookupName.startsWith("field_"))) {
            return originalName;
        }
        if (metadata.targetOwners().isEmpty()) {
            throw new IOException("Mixin member " + originalName + descriptor + " in "
                    + metadata.className() + " is marked @Shadow/@Overwrite but no @Mixin target was found.");
        }

        Set<String> mappedNames = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (String targetOwner : metadata.targetOwners()) {
            try {
                mappedNames.add(mappings.mapMixinMemberName(targetOwner, lookupName, descriptor, method));
            } catch (IOException exception) {
                failures.add(exception.getMessage());
            }
        }
        if (mappedNames.size() == 1) return prefix + mappedNames.iterator().next();
        if (mappedNames.size() > 1) {
            throw new IOException("Conflicting legacy @Shadow/@Overwrite mappings for "
                    + metadata.className() + "#" + originalName + descriptor + ": " + mappedNames);
        }
        throw new IOException("Could not map legacy @Shadow/@Overwrite member "
                + metadata.className() + "#" + originalName + descriptor + " against targets "
                + metadata.targetOwners() + ". " + String.join(" | ", failures));
    }

    private static AnnotationVisitor wrapAnnotation(AnnotationVisitor delegate, String descriptor,
            MixinMetadata metadata, LegacyClassTweakerMappings mappings) {
        if (delegate == null) return null;
        return new AnnotationVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visit(String name, Object value) {
                Object mapped = value;
                if (value instanceof String text) {
                    try {
                        mapped = mapAnnotationString(descriptor, name, text, metadata, mappings);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
                super.visit(name, mapped);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
                return wrapAnnotation(super.visitAnnotation(name, nestedDescriptor),
                        nestedDescriptor, metadata, mappings);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor nested = super.visitArray(name);
                if (nested == null) return null;
                return new AnnotationVisitor(Opcodes.ASM9, nested) {
                    @Override
                    public void visit(String ignored, Object value) {
                        Object mapped = value;
                        if (value instanceof String text) {
                            try {
                                mapped = mapAnnotationString(descriptor, name, text, metadata, mappings);
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        }
                        super.visit(ignored, mapped);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String ignored, String nestedDescriptor) {
                        return wrapAnnotation(super.visitAnnotation(ignored, nestedDescriptor),
                                nestedDescriptor, metadata, mappings);
                    }
                };
            }
        };
    }

    private static String mapAnnotationString(String annotationDescriptor, String propertyName,
            String value, MixinMetadata metadata, LegacyClassTweakerMappings mappings) throws IOException {
        if (MIXIN_ANNOTATION.equals(annotationDescriptor) && "targets".equals(propertyName)) {
            return mapClassSelector(value, mappings);
        }
        if (AT_ANNOTATION.equals(annotationDescriptor) && "target".equals(propertyName)) {
            return mapMemberSelector(value, metadata.targetOwners(), mappings, true);
        }
        if (isInjectionAnnotation(annotationDescriptor) && "method".equals(propertyName)) {
            return mapMethodSelectorList(value, metadata.targetOwners(), mappings);
        }
        if ((ACCESSOR_ANNOTATION.equals(annotationDescriptor)
                || INVOKER_ANNOTATION.equals(annotationDescriptor))
                && "value".equals(propertyName) && !value.isBlank()) {
            boolean method = INVOKER_ANNOTATION.equals(annotationDescriptor);
            return mapSelectorNameOnly(value, metadata.targetOwners(), mappings, method);
        }
        return value;
    }

    private static boolean isInjectionAnnotation(String descriptor) {
        return descriptor.startsWith("Lorg/spongepowered/asm/mixin/injection/")
                && !AT_ANNOTATION.equals(descriptor)
                && !descriptor.endsWith("/Slice;")
                || descriptor.startsWith("Lcom/llamalad7/mixinextras/injector/");
    }

    private static String mapMethodSelectorList(String value, List<String> targetOwners,
                                                LegacyClassTweakerMappings mappings) throws IOException {
        String[] selectors = value.split(",");
        if (selectors.length == 1) {
            return mapMemberSelector(value, targetOwners, mappings, false);
        }
        List<String> mapped = new ArrayList<>(selectors.length);
        for (String selector : selectors) {
            mapped.add(mapMemberSelector(selector.trim(), targetOwners, mappings, false));
        }
        return String.join(",", mapped);
    }

    private static String mapSelectorNameOnly(String value, List<String> targetOwners,
                                              LegacyClassTweakerMappings mappings,
                                              boolean method) throws IOException {
        String owner = targetOwners.isEmpty() ? null : targetOwners.getFirst();
        if (owner == null) return value;
        return mappings.mapMixinSelectorName(owner, value, null, method);
    }

    /**
     * Remaps Mixin member selectors such as
     * Lnet/minecraft/class_2378;method_60915(Lnet/minecraft/class_5321;)Ljava/lang/String;.
     * The owner may be omitted in @Inject(method=...), in which case the @Mixin
     * target is the lookup owner.
     */
    private static String mapMemberSelector(String selector, List<String> targetOwners,
                                            LegacyClassTweakerMappings mappings,
                                            boolean allowClassOnly) throws IOException {
        if (selector == null || selector.isBlank()) return selector;
        String original = selector.trim();
        String explicitOwner = null;
        String member = original;
        boolean hasExplicitOwner = original.startsWith("L") && original.indexOf(';') > 1;
        if (hasExplicitOwner) {
            int separator = original.indexOf(';');
            explicitOwner = original.substring(1, separator);
            member = original.substring(separator + 1);
        }

        int methodDescriptorStart = member.indexOf('(');
        int fieldDescriptorStart = member.indexOf(':');
        boolean method = methodDescriptorStart >= 0;
        boolean field = !method && fieldDescriptorStart >= 0;
        if (!method && !field) {
            if (hasExplicitOwner && allowClassOnly && member.isEmpty()) {
                return "L" + mappings.mapClass(explicitOwner) + ";";
            }
            if (looksLikeMemberId(member)) {
                return mapSelectorNameOnly(member, selectorOwner(explicitOwner, targetOwners),
                        mappings, member.startsWith("method_"));
            }
            if (hasExplicitOwner && allowClassOnly) {
                return "L" + mappings.mapClass(explicitOwner) + ";" + member;
            }
            return original;
        }

        String memberName = member.substring(0, method ? methodDescriptorStart : fieldDescriptorStart);
        String descriptor = member.substring(method ? methodDescriptorStart + 0 : fieldDescriptorStart + 1);
        String owner = selectorOwner(explicitOwner, targetOwners);
        if (owner == null) return original;

        String mappedName = mappings.mapMixinSelectorName(owner, memberName, descriptor, method);
        String mappedDescriptor = mappings.mapDescriptor(descriptor);
        String mappedMember = mappedName + (method ? mappedDescriptor : ":" + mappedDescriptor);
        if (!hasExplicitOwner) return mappedMember;

        String mappedOwner = mappings.mapClass(explicitOwner);
        return "L" + mappedOwner + ";" + mappedMember;
    }

    private static List<String> selectorOwner(String explicitOwner, List<String> targetOwners) {
        if (explicitOwner != null) return List.of(explicitOwner);
        return targetOwners;
    }

    private static String mapSelectorNameOnly(String name, List<String> owners,
                                              LegacyClassTweakerMappings mappings,
                                              boolean method) throws IOException {
        if (owners == null || owners.isEmpty()) return name;
        Set<String> names = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        for (String owner : owners) {
            try {
                names.add(mappings.mapMixinSelectorName(owner, name, null, method));
            } catch (IOException exception) {
                failures.add(exception.getMessage());
            }
        }
        if (names.size() == 1) return names.iterator().next();
        if (names.size() > 1) {
            throw new IOException("Conflicting target mappings for descriptor-less selector "
                    + name + " across " + owners + ": " + names);
        }
        if (!failures.isEmpty()) {
            throw new IOException("Could not resolve descriptor-less selector " + name
                    + " against " + owners + ". " + String.join(" | ", failures));
        }
        return name;
    }

    private static String mapClassSelector(String value, LegacyClassTweakerMappings mappings) throws IOException {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return value;
        String internalName = toInternalName(trimmed);
        return mappings.mapClass(internalName).replace('/', '.');
    }

    private static boolean looksLikeMemberId(String value) {
        return value.startsWith("method_") || value.startsWith("field_");
    }

    private static String toInternalName(String name) {
        String value = name.trim();
        if (value.startsWith("L") && value.endsWith(";") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace('.', '/');
    }

    private static String memberKey(String name, String descriptor) {
        return name + "\u0000" + descriptor;
    }

    record Result(byte[] bytes, boolean changed) {}

    private record MixinMetadata(String className, List<String> targetOwners,
                                 Set<String> shadowMethods, Set<String> shadowFields) {}
}
