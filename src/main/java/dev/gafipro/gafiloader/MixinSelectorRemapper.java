package dev.gafipro.gafiloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remaps selector strings stored in Mixin annotations before Mixin parses the old bytecode.
 * ASM's normal ClassRemapper remaps descriptors and Type annotation values, but does not rewrite
 * arbitrary strings such as @Inject(method = "method_123(Lnet/minecraft/class_1;)V").
 *
 * <p>This adapter only touches recognized selector-bearing annotation attributes. It neither
 * rewrites arbitrary string constants nor modifies the source class/JAR.</p>
 */
final class MixinSelectorRemapper {
    private static final int API = Opcodes.ASM9;
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String SPONGE_INJECTION_PREFIX =
            "Lorg/spongepowered/asm/mixin/injection/";
    private static final String MIXINEXTRAS_INJECTOR_PREFIX =
            "Lcom/llamalad7/mixinextras/injector/";

    private MixinSelectorRemapper() {}

    /** @return transformed bytes, or null when no selector/class string was changed */
    static byte[] remap(byte[] original, IntermediaryMappings mappings,
                        CompatibilityScanner.AliasSet aliases) {
        if (original == null || mappings == null || aliases == null) {
            return null;
        }

        List<String> targets = readMixinTargets(original);
        if (targets.isEmpty()) {
            return null;
        }

        AtomicBoolean changed = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return wrap(super.visitAnnotation(descriptor, visible), descriptor, targets,
                        mappings, aliases, changed);
            }

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                              String signature, Object value) {
                org.objectweb.asm.FieldVisitor field =
                        super.visitField(access, name, descriptor, signature, value);
                return new org.objectweb.asm.FieldVisitor(API, field) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return wrap(super.visitAnnotation(annotationDescriptor, visible), annotationDescriptor,
                                targets, mappings, aliases, changed);
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor, boolean visible) {
                        return wrap(super.visitTypeAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed);
                    }
                };
            }

            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor method =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                return new org.objectweb.asm.MethodVisitor(API, method) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        return wrap(super.visitAnnotation(annotationDescriptor, visible), annotationDescriptor,
                                targets, mappings, aliases, changed);
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor, boolean visible) {
                        return wrap(super.visitTypeAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor,
                                                                       boolean visible) {
                        return wrap(super.visitParameterAnnotation(parameter, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitInsnAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                        return wrap(super.visitInsnAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitTryCatchAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                        return wrap(super.visitTryCatchAnnotation(typeRef, typePath, annotationDescriptor, visible),
                                annotationDescriptor, targets, mappings, aliases, changed);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitLocalVariableAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, org.objectweb.asm.Label[] start,
                            org.objectweb.asm.Label[] end, int[] index, String annotationDescriptor,
                            boolean visible) {
                        return wrap(super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index,
                                annotationDescriptor, visible), annotationDescriptor, targets,
                                mappings, aliases, changed);
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return changed.get() ? writer.toByteArray() : null;
    }

    private static List<String> readMixinTargets(byte[] original) {
        List<String> targets = new ArrayList<>();
        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(API) {
                    @Override
                    public void visit(String name, Object value) {
                        if (name.equals("targets") && value instanceof String target) {
                            addTarget(targets, target);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!name.equals("value") && !name.equals("targets")) {
                            return null;
                        }
                        return new AnnotationVisitor(API) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (name.equals("value") && value instanceof Type type
                                        && type.getSort() == Type.OBJECT) {
                                    addTarget(targets, type.getInternalName());
                                } else if (name.equals("targets") && value instanceof String target) {
                                    addTarget(targets, target);
                                }
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return List.copyOf(targets);
    }

    private static void addTarget(List<String> targets, String target) {
        if (target == null || target.isBlank()) {
            return;
        }
        String normalized = target.replace('.', '/');
        if (normalized.startsWith("L") && normalized.endsWith(";")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (!targets.contains(normalized)) {
            targets.add(normalized);
        }
    }

    private static AnnotationVisitor wrap(AnnotationVisitor delegate, String annotationDescriptor,
                                          List<String> targets, IntermediaryMappings mappings,
                                          CompatibilityScanner.AliasSet aliases,
                                          AtomicBoolean changed) {
        if (delegate == null) {
            return null;
        }
        return new AnnotationVisitor(API, delegate) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String stringValue) {
                    String mapped = mapStringValue(annotationDescriptor, name, stringValue,
                            targets, mappings, aliases);
                    if (!mapped.equals(stringValue)) {
                        changed.set(true);
                    }
                    super.visit(name, mapped);
                } else {
                    super.visit(name, value);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                AnnotationVisitor array = super.visitArray(name);
                if (array == null) {
                    return null;
                }
                return new AnnotationVisitor(API, array) {
                    @Override
                    public void visit(String ignored, Object value) {
                        if (value instanceof String stringValue) {
                            String mapped = mapStringValue(annotationDescriptor, name, stringValue,
                                    targets, mappings, aliases);
                            if (!mapped.equals(stringValue)) {
                                changed.set(true);
                            }
                            super.visit(ignored, mapped);
                        } else {
                            super.visit(ignored, value);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String nestedName, String nestedDescriptor) {
                        return wrap(super.visitAnnotation(nestedName, nestedDescriptor), nestedDescriptor,
                                targets, mappings, aliases, changed);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String nestedName) {
                        return wrap(super.visitArray(nestedName), annotationDescriptor,
                                targets, mappings, aliases, changed);
                    }
                };
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
                return wrap(super.visitAnnotation(name, nestedDescriptor), nestedDescriptor,
                        targets, mappings, aliases, changed);
            }
        };
    }

    private static String mapStringValue(String annotationDescriptor, String elementName, String value,
                                         List<String> targets, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases) {
        if (value.isEmpty()) {
            return value;
        }

        if (MIXIN.equals(annotationDescriptor) && elementName.equals("targets")) {
            String internal = value.replace('.', '/');
            String mapped = aliases.mapClass(mappings.mapClassName(internal));
            return value.indexOf('/') >= 0 ? mapped : mapped.replace('/', '.');
        }

        boolean methodSelector = elementName.equals("method")
                && (annotationDescriptor.startsWith(SPONGE_INJECTION_PREFIX)
                    || annotationDescriptor.startsWith(MIXINEXTRAS_INJECTOR_PREFIX));
        boolean atTarget = AT.equals(annotationDescriptor) && elementName.equals("target");
        if (!methodSelector && !atTarget) {
            return value;
        }
        return remapSelector(value, targets, mappings, aliases);
    }

    /**
     * Returns a reason when a fully specified Minecraft member selector cannot resolve in the
     * current target game. Unsupported/wildcard selectors are left to Mixin's own parser.
     */
    static String incompatibilityReason(String selector, List<String> targets,
                                        IntermediaryMappings mappings,
                                        CompatibilityScanner.AliasSet aliases,
                                        TargetApiIndex targetApi) {
        if (selector == null || selector.isBlank() || targetApi == null) {
            return null;
        }
        boolean optional = selector.startsWith("?");
        String unwrapped = optional ? selector.substring(1) : selector;
        String mapped = remapSelector(unwrapped, targets, mappings, aliases);
        if (mapped == null || mapped.equals(unwrapped)) {
            // A selector that only needs descriptor normalization may still change even if the
            // member name stays the same; remapSelector handles that. If no full signature exists,
            // we deliberately do not guess.
        }

        if (mapped.startsWith("L")) {
            int ownerEnd = mapped.indexOf(';');
            if (ownerEnd <= 1 || ownerEnd + 1 >= mapped.length()) {
                return null;
            }
            String owner = mapped.substring(1, ownerEnd);
            if (!owner.startsWith("net/minecraft/")) {
                return null;
            }
            String member = mapped.substring(ownerEnd + 1);
            ParsedSelector parsed = parseMember(member);
            if (parsed == null) {
                return null;
            }
            if (!targetApi.hasClass(owner)) {
                return optional ? null : "selector owner class " + owner + " is absent from Minecraft 26.2";
            }
            boolean exists = parsed.method()
                    ? targetApi.methodAccess(owner, parsed.name(), parsed.descriptor()) != null
                    : targetApi.fieldAccess(owner, parsed.name(), parsed.descriptor()) != null;
            if (!exists && !optional) {
                return "selector " + mapped + " does not exist in Minecraft 26.2";
            }
            return null;
        }

        int descriptorStart = mapped.indexOf('(');
        if (descriptorStart <= 0) {
            return null;
        }
        String name = mapped.substring(0, descriptorStart);
        String descriptor = mapped.substring(descriptorStart);
        if (name.contains("*") || name.contains("/") || name.contains(";")) {
            return null;
        }
        for (String rawTarget : targets) {
            String owner = aliases.mapClass(mappings.mapClassName(rawTarget));
            if (!owner.startsWith("net/minecraft/")) {
                continue;
            }
            if (!targetApi.hasClass(owner)) {
                if (!optional) {
                    return "target class " + owner + " is absent from Minecraft 26.2";
                }
                continue;
            }
            if (targetApi.methodAccess(owner, name, descriptor) == null && !optional) {
                return "selector " + name + descriptor + " does not resolve on " + owner
                        + " in Minecraft 26.2";
            }
        }
        return null;
    }

    private static ParsedSelector parseMember(String selector) {
        int methodStart = selector.indexOf('(');
        int fieldSeparator = selector.indexOf(':');
        if (methodStart > 0 && (fieldSeparator < 0 || methodStart < fieldSeparator)) {
            return new ParsedSelector(true, selector.substring(0, methodStart),
                    selector.substring(methodStart));
        }
        if (fieldSeparator > 0) {
            return new ParsedSelector(false, selector.substring(0, fieldSeparator),
                    selector.substring(fieldSeparator + 1));
        }
        return null;
    }

    private record ParsedSelector(boolean method, String name, String descriptor) {}

    private static String remapSelector(String selector, List<String> targets,
                                        IntermediaryMappings mappings,
                                        CompatibilityScanner.AliasSet aliases) {
        String optionalPrefix = selector.startsWith("?") ? "?" : "";
        if (!optionalPrefix.isEmpty()) {
            selector = selector.substring(1);
        }
        // Explicit-owner member selectors use Mixin's Lowner;name(desc) or Lowner;field:desc syntax.
        if (selector.startsWith("L")) {
            int ownerEnd = selector.indexOf(';');
            if (ownerEnd > 1 && ownerEnd + 1 < selector.length()) {
                String owner = selector.substring(1, ownerEnd);
                String memberSelector = selector.substring(ownerEnd + 1);
                String mapped = remapOwnedMember(owner, memberSelector, mappings, aliases);
                if (mapped != null) {
                    return optionalPrefix + "L" + mapped;
                }
            }
        }

        // Ownerless injection selectors are relative to @Mixin's target(s).
        int descriptorStart = selector.indexOf('(');
        if (descriptorStart <= 0) {
            return optionalPrefix + selector;
        }
        String name = selector.substring(0, descriptorStart);
        String descriptor = selector.substring(descriptorStart);
        if (name.contains("*") || name.contains("/") || name.contains(";")) {
            return optionalPrefix + selector;
        }

        String result = null;
        for (String owner : targets) {
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.METHOD,
                            owner, name, descriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            String candidate = mapped.name() + mapped.descriptor();
            if (result != null && !result.equals(candidate)) {
                return optionalPrefix + selector;
            }
            result = candidate;
        }
        return optionalPrefix + (result == null ? selector : result);
    }

    private static String remapOwnedMember(String owner, String selector,
                                           IntermediaryMappings mappings,
                                           CompatibilityScanner.AliasSet aliases) {
        int methodStart = selector.indexOf('(');
        int fieldSeparator = selector.indexOf(':');
        if (methodStart > 0 && (fieldSeparator < 0 || methodStart < fieldSeparator)) {
            String name = selector.substring(0, methodStart);
            String descriptor = selector.substring(methodStart);
            if (name.contains("*") || name.contains("/")) {
                return null;
            }
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.METHOD,
                            owner, name, descriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            return mapped.owner() + ";" + mapped.name() + mapped.descriptor();
        }
        if (fieldSeparator > 0) {
            String name = selector.substring(0, fieldSeparator);
            String descriptor = selector.substring(fieldSeparator + 1);
            if (name.contains("*") || name.contains("/")) {
                return null;
            }
            CompatibilityScanner.Reference normalized = mappings.normalize(
                    new CompatibilityScanner.Reference(CompatibilityScanner.SymbolKind.FIELD,
                            owner, name, descriptor));
            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
            return mapped.owner() + ";" + mapped.name() + ":" + mapped.descriptor();
        }
        return null;
    }
}
