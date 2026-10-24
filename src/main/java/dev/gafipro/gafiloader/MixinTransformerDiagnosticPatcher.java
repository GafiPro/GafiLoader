package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary diagnostic hook for observing the actual target bytecode Mixin receives. */
final class MixinTransformerDiagnosticPatcher {
    static final String TARGET_CLASS = "org/spongepowered/asm/mixin/transformer/MixinTransformer";
    private static final String TARGET_METHOD = "transformClassBytes";
    private static final String TARGET_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;[B)[B";
    private static final Type ADAPTER = Type.getObjectType(
            "dev/gafipro/gafiloader/MixinResourceRemapper");
    private static final Method INSPECT = new Method("debugRuntimeTargetClassBytes",
            "(Ljava/lang/String;Ljava/lang/String;[B)V");

    private MixinTransformerDiagnosticPatcher() {}

    static byte[] transform(String className, byte[] classBytes) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }
        AtomicBoolean patched = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader,
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                    String descriptor, String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                patched.set(true);
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadArg(0);
                        loadArg(1);
                        loadArg(2);
                        invokeStatic(ADAPTER, INSPECT);
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!patched.get()) {
            throw new IllegalStateException("Could not patch MixinTransformer.transformClassBytes"
                    + TARGET_DESCRIPTOR);
        }
        System.out.println("[Gafi Loader] Installed runtime target-class diagnostic hook.");
        return writer.toByteArray();
    }
}
