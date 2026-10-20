package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remaps direct JVM references in a class from 1.21.11 Intermediary into runtime Mojang names.
 *
 * <p>Reviewed member/class aliases are applied after namespace normalization. The transformation
 * occurs as class bytes are defined; it never edits the source mod JAR.</p>
 *
 * <p>Mixin configuration/refmap JSON and bytecode requested for Mixin metadata inspection have
 * separate in-memory adapters in {@link MixinResourceRemapper}. Arbitrary reflection strings and
 * unrelated resources need separate adapters. Descriptor conversions
 * that change the underlying type/shape are deliberately rejected.</p>
 */
final class IntermediaryBytecodeRemapper {
    private static final int API = Opcodes.ASM9;

    private IntermediaryBytecodeRemapper() {}

    static byte[] remap(byte[] original, IntermediaryMappings mappings) throws IOException {
        return remap(original, mappings, CompatibilityScanner.AliasSet.empty(), null);
    }

    static byte[] remap(byte[] original, IntermediaryMappings mappings,
                        CompatibilityScanner.AliasSet aliases) throws IOException {
        return remap(original, mappings, aliases, null);
    }

    /**
     * @return remapped bytes when at least one known symbol changed; otherwise {@code null}
     */
    static byte[] remap(byte[] original, IntermediaryMappings mappings,
                        CompatibilityScanner.AliasSet aliases, TargetApiIndex targetApi) throws IOException {
        if (original == null || mappings == null || aliases == null) {
            throw new IllegalArgumentException("Class bytes, Intermediary mappings and alias set are required.");
        }

        ClassReader reader = new ClassReader(original);
        if (targetApi != null) {
            try {
                validateAliasDispatch(reader, mappings, aliases, targetApi);
            } catch (AliasValidationFailure failure) {
                throw new IOException(failure.getMessage(), failure);
            }
        }

        AtomicBoolean changed = new AtomicBoolean(false);
        Remapper remapper = new Remapper(API) {
            @Override
            public String map(String internalName) {
                String normalized = mappings.mapClassName(internalName);
                String mapped = aliases.mapClass(normalized);
                if (!mapped.equals(internalName)) {
                    changed.set(true);
                }
                return mapped;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                CompatibilityScanner.Reference normalized = mappings.normalize(
                        new CompatibilityScanner.Reference(
                                CompatibilityScanner.SymbolKind.METHOD, owner, name, descriptor));
                CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
                ensureDescriptorUnchangedByMemberAlias("method", owner, name, descriptor,
                        normalized.descriptor(), mapped.descriptor(), aliases);
                if (!mapped.name().equals(name)) {
                    changed.set(true);
                }
                return mapped.name();
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                CompatibilityScanner.Reference normalized = mappings.normalize(
                        new CompatibilityScanner.Reference(
                                CompatibilityScanner.SymbolKind.FIELD, owner, name, descriptor));
                CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
                ensureDescriptorUnchangedByMemberAlias("field", owner, name, descriptor,
                        normalized.descriptor(), mapped.descriptor(), aliases);
                if (!mapped.name().equals(name)) {
                    changed.set(true);
                }
                return mapped.name();
            }

            private void ensureDescriptorUnchangedByMemberAlias(String kind, String owner, String name,
                                                                 String originalDescriptor,
                                                                 String normalizedDescriptor,
                                                                 String mappedDescriptor,
                                                                 CompatibilityScanner.AliasSet aliasSet) {
                String expected = aliasSet.mapDescriptor(normalizedDescriptor);
                if (!Objects.equals(mappedDescriptor, expected)) {
                    throw new IllegalArgumentException("Unsupported descriptor-changing " + kind
                            + " alias for " + owner + "#" + name + originalDescriptor
                            + ": mapped descriptor " + mappedDescriptor
                            + " does not match type-only namespace mapping " + expected
                            + ". Add a dedicated bytecode adapter instead.");
                }
            }
        };

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassRemapper(writer, remapper) {
            @Override
            protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
                return new MethodRemapper(API, methodVisitor, remapper) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        CompatibilityScanner.Reference mapped = mapReference(
                                CompatibilityScanner.SymbolKind.METHOD, owner, name, descriptor,
                                mappings, aliases, changed);
                        // Write the already-normalized reference directly to the downstream visitor.
                        // Letting MethodRemapper remap it again would lose aliases that move a member
                        // to a different owner class.
                        mv.visitMethodInsn(opcode, mapped.owner(), mapped.name(),
                                mapped.descriptor(), isInterface);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        CompatibilityScanner.Reference mapped = mapReference(
                                CompatibilityScanner.SymbolKind.FIELD, owner, name, descriptor,
                                mappings, aliases, changed);
                        mv.visitFieldInsn(opcode, mapped.owner(), mapped.name(), mapped.descriptor());
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        mv.visitLdcInsn(mapAliasAwareConstant(value, mappings, aliases, remapper, changed));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        String mappedDescriptor = mapDescriptor(descriptor, mappings, aliases, changed);
                        Handle mappedBootstrap = (Handle) mapAliasAwareConstant(
                                bootstrapMethodHandle, mappings, aliases, remapper, changed);
                        Object[] mappedArguments = new Object[bootstrapMethodArguments.length];
                        for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                            mappedArguments[i] = mapAliasAwareConstant(bootstrapMethodArguments[i],
                                    mappings, aliases, remapper, changed);
                        }
                        mv.visitInvokeDynamicInsn(name, mappedDescriptor, mappedBootstrap, mappedArguments);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return changed.get() ? writer.toByteArray() : null;
    }

    private static CompatibilityScanner.Reference mapReference(
            CompatibilityScanner.SymbolKind kind, String owner, String name, String descriptor,
            IntermediaryMappings mappings, CompatibilityScanner.AliasSet aliases,
            AtomicBoolean changed) {
        CompatibilityScanner.Reference mapped = aliases.normalize(mappings.normalize(
                new CompatibilityScanner.Reference(kind, owner, name, descriptor)));
        CompatibilityScanner.Reference original = new CompatibilityScanner.Reference(kind, owner, name, descriptor);
        if (!mapped.equals(original)) {
            changed.set(true);
        }
        return mapped;
    }

    private static String mapDescriptor(String descriptor, IntermediaryMappings mappings,
                                        CompatibilityScanner.AliasSet aliases, AtomicBoolean changed) {
        String mapped = aliases.mapDescriptor(mappings.mapDescriptor(descriptor));
        if (!Objects.equals(mapped, descriptor)) {
            changed.set(true);
        }
        return mapped;
    }

    private static Object mapAliasAwareConstant(Object value, IntermediaryMappings mappings,
                                                CompatibilityScanner.AliasSet aliases,
                                                Remapper remapper, AtomicBoolean changed) {
        if (value instanceof Handle handle) {
            CompatibilityScanner.SymbolKind kind = handle.getTag() >= Opcodes.H_GETFIELD
                    && handle.getTag() <= Opcodes.H_PUTSTATIC
                    ? CompatibilityScanner.SymbolKind.FIELD : CompatibilityScanner.SymbolKind.METHOD;
            CompatibilityScanner.Reference mapped = mapReference(kind, handle.getOwner(), handle.getName(),
                    handle.getDesc(), mappings, aliases, changed);
            return new Handle(handle.getTag(), mapped.owner(), mapped.name(), mapped.descriptor(),
                    handle.isInterface());
        }
        if (value instanceof ConstantDynamic dynamic) {
            Handle bootstrap = (Handle) mapAliasAwareConstant(dynamic.getBootstrapMethod(),
                    mappings, aliases, remapper, changed);
            Object[] arguments = new Object[dynamic.getBootstrapMethodArgumentCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = mapAliasAwareConstant(dynamic.getBootstrapMethodArgument(i),
                        mappings, aliases, remapper, changed);
            }
            String descriptor = mapDescriptor(dynamic.getDescriptor(), mappings, aliases, changed);
            return new ConstantDynamic(dynamic.getName(), descriptor, bootstrap, arguments);
        }
        Object mapped = remapper.mapValue(value);
        if (!Objects.equals(mapped, value)) {
            changed.set(true);
        }
        return mapped;
    }

    /**
     * Verify every alias that affects an actual bytecode call-site against the target API. A
     * same-descriptor rename cannot safely change static/instance form because the JVM opcode
     * remains the old mod's opcode.
     */
    private static void validateAliasDispatch(ClassReader reader, IntermediaryMappings mappings,
                                              CompatibilityScanner.AliasSet aliases,
                                              TargetApiIndex targetApi) {
        reader.accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(API) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        validateMethodReference(opcode, owner, methodName, methodDescriptor, isInterface,
                                mappings, aliases, targetApi);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        validateFieldReference(opcode, owner, fieldName, fieldDescriptor,
                                mappings, aliases, targetApi);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        validateConstant(value, mappings, aliases, targetApi);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        validateHandle(bootstrapMethodHandle, mappings, aliases, targetApi);
                        for (Object argument : bootstrapMethodArguments) {
                            validateConstant(argument, mappings, aliases, targetApi);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void validateConstant(Object value, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases, TargetApiIndex targetApi) {
        if (value instanceof Handle handle) {
            validateHandle(handle, mappings, aliases, targetApi);
        } else if (value instanceof ConstantDynamic dynamic) {
            validateHandle(dynamic.getBootstrapMethod(), mappings, aliases, targetApi);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                validateConstant(dynamic.getBootstrapMethodArgument(i), mappings, aliases, targetApi);
            }
        }
    }

    private static void validateHandle(Handle handle, IntermediaryMappings mappings,
                                      CompatibilityScanner.AliasSet aliases, TargetApiIndex targetApi) {
        if (handle == null) {
            return;
        }
        int tag = handle.getTag();
        if (tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC) {
            int opcode = switch (tag) {
                case Opcodes.H_GETFIELD -> Opcodes.GETFIELD;
                case Opcodes.H_GETSTATIC -> Opcodes.GETSTATIC;
                case Opcodes.H_PUTFIELD -> Opcodes.PUTFIELD;
                case Opcodes.H_PUTSTATIC -> Opcodes.PUTSTATIC;
                default -> -1;
            };
            validateFieldReference(opcode, handle.getOwner(), handle.getName(), handle.getDesc(),
                    mappings, aliases, targetApi);
        } else {
            int opcode = switch (tag) {
                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                default -> Opcodes.INVOKEVIRTUAL;
            };
            validateMethodReference(opcode, handle.getOwner(), handle.getName(), handle.getDesc(),
                    handle.isInterface(), mappings, aliases, targetApi);
        }
    }

    private static void validateMethodReference(int opcode, String owner, String name, String descriptor,
                                                boolean interfaceReference,
                                                IntermediaryMappings mappings,
                                                CompatibilityScanner.AliasSet aliases,
                                                TargetApiIndex targetApi) {
        CompatibilityScanner.Reference normalized = mappings.normalize(new CompatibilityScanner.Reference(
                CompatibilityScanner.SymbolKind.METHOD, owner, name, descriptor));
        CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
        if (mapped.equals(normalized)) {
            return;
        }

        Integer access = targetApi.methodAccess(mapped.owner(), mapped.name(), mapped.descriptor());
        if (access == null) {
            throw new AliasValidationFailure("Alias target method is missing from the configured target game: "
                    + mapped.owner() + "#" + mapped.name() + mapped.descriptor());
        }

        boolean targetStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean callStatic = opcode == Opcodes.INVOKESTATIC;
        if (targetStatic != callStatic) {
            throw new AliasValidationFailure("Alias changes static/instance invocation shape at "
                    + owner + "#" + name + descriptor + ": target "
                    + mapped.owner() + "#" + mapped.name() + mapped.descriptor()
                    + (targetStatic ? " is static but call-site is not INVOKESTATIC."
                    : " is an instance method but call-site is INVOKESTATIC."));
        }
        boolean targetInterface = targetApi.isInterface(mapped.owner());
        if (interfaceReference != targetInterface) {
            throw new AliasValidationFailure("Alias changes class/interface method-reference shape at "
                    + owner + "#" + name + descriptor + ": target "
                    + mapped.owner() + "#" + mapped.name() + mapped.descriptor()
                    + (targetInterface ? " requires an interface method reference."
                    : " requires a class method reference."));
        }
        if (opcode == Opcodes.INVOKEINTERFACE && !targetInterface) {
            throw new AliasValidationFailure("Alias target owner is not an interface for INVOKEINTERFACE: "
                    + mapped.owner() + "#" + mapped.name() + mapped.descriptor());
        }
        if (opcode == Opcodes.INVOKEVIRTUAL && targetInterface) {
            throw new AliasValidationFailure("Alias target owner is an interface but call-site uses INVOKEVIRTUAL: "
                    + mapped.owner() + "#" + mapped.name() + mapped.descriptor());
        }
    }

    private static void validateFieldReference(int opcode, String owner, String name, String descriptor,
                                               IntermediaryMappings mappings,
                                               CompatibilityScanner.AliasSet aliases,
                                               TargetApiIndex targetApi) {
        CompatibilityScanner.Reference normalized = mappings.normalize(new CompatibilityScanner.Reference(
                CompatibilityScanner.SymbolKind.FIELD, owner, name, descriptor));
        CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
        if (mapped.equals(normalized)) {
            return;
        }

        Integer access = targetApi.fieldAccess(mapped.owner(), mapped.name(), mapped.descriptor());
        if (access == null) {
            throw new AliasValidationFailure("Alias target field is missing from the configured target game: "
                    + mapped.owner() + "#" + mapped.name() + ":" + mapped.descriptor());
        }

        boolean targetStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean opcodeStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        if (targetStatic != opcodeStatic) {
            throw new AliasValidationFailure("Alias changes static/instance field shape at "
                    + owner + "#" + name + ":" + descriptor + ": target "
                    + mapped.owner() + "#" + mapped.name() + ":" + mapped.descriptor()
                    + (targetStatic ? " is static but call-site is not GETSTATIC/PUTSTATIC."
                    : " is an instance field but call-site uses GETSTATIC/PUTSTATIC."));
        }
    }

    private static final class AliasValidationFailure extends RuntimeException {
        private AliasValidationFailure(String message) {
            super(message);
        }
    }
}
