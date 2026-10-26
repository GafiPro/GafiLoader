package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Installs the narrow Fabric Loader hook used to remap ClassTweaker files in memory.
 *
 * <p>The patch is pinned to the current Fabric Loader method and call descriptors. It fails
 * explicitly when the expected boundary changes instead of claiming a compatibility layer exists
 * when it does not.</p>
 */
final class ClassTweakerPatcher {
    static final String TARGET_CLASS = "net/fabricmc/loader/impl/FabricLoaderImpl";
    private static final String TARGET_METHOD = "loadClassTweakers";
    private static final String TARGET_METHOD_DESCRIPTOR = "()V";
    private static final String FILES_CLASS = "java/nio/file/Files";
    private static final String FILES_METHOD = "newBufferedReader";
    private static final String FILES_DESCRIPTOR =
            "(Ljava/nio/file/Path;)Ljava/io/BufferedReader;";
    private static final String ADAPTER_CLASS = "dev/gafipro/gafiloader/MixinResourceRemapper";
    private static final String ADAPTER_METHOD = "openClassTweakerReader";

    private ClassTweakerPatcher() {}

    /**
     * @return patched bytes when the expected Fabric Loader class is supplied
     */
    static byte[] transform(String className, byte[] original) {
        if (!TARGET_CLASS.equals(className) || original == null) {
            return null;
        }

        boolean[] foundMethod = {false};
        int[] patchedCalls = {0};
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_METHOD_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }

                foundMethod[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && FILES_CLASS.equals(owner)
                                && FILES_METHOD.equals(methodName)
                                && FILES_DESCRIPTOR.equals(methodDescriptor)) {
                            patchedCalls[0]++;
                            super.visitMethodInsn(opcode, ADAPTER_CLASS, ADAPTER_METHOD,
                                    FILES_DESCRIPTOR, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                        }
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        if (!foundMethod[0]) {
            throw new IllegalStateException("Fabric Loader ClassTweaker hook is incompatible: missing "
                    + TARGET_METHOD + TARGET_METHOD_DESCRIPTOR + " in " + TARGET_CLASS);
        }
        if (patchedCalls[0] == 0) {
            throw new IllegalStateException("Fabric Loader ClassTweaker hook is incompatible: "
                    + TARGET_METHOD + TARGET_METHOD_DESCRIPTOR + " no longer calls "
                    + FILES_CLASS + "." + FILES_METHOD + FILES_DESCRIPTOR);
        }

        System.out.println("[Gafi Loader] Installed ClassTweaker reader hook (" + patchedCalls[0] + " call site(s)).");
        return writer.toByteArray();
    }
}
