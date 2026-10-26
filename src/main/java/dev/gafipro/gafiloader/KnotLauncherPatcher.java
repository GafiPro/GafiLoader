package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hooks Fabric Launcher's resource API, the path used by MixinServiceKnot to load configs/refmaps.
 * All compatibility changes are performed on streams in memory.
 */
final class KnotLauncherPatcher {
    static final String TARGET_CLASS = "net/fabricmc/loader/impl/launch/knot/Knot";
    private static final String RESOURCE_METHOD = "getResourceAsStream";
    private static final String RESOURCE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final Type INPUT_STREAM = Type.getType(InputStream.class);
    private static final Type ADAPTER = Type.getObjectType("dev/gafipro/gafiloader/MixinResourceRemapper");
    private static final Method TRANSFORM = new Method("transform",
            "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/io/InputStream;");

    private KnotLauncherPatcher() {}

    static byte[] transform(String className, byte[] classBytes) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }
        AtomicBoolean patched = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!RESOURCE_METHOD.equals(name) || !RESOURCE_DESCRIPTOR.equals(descriptor)) {
                    return delegate;
                }
                patched.set(true);
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                    @Override
                    protected void onMethodExit(int opcode) {
                        if (opcode != ARETURN) {
                            return;
                        }
                        int streamLocal = newLocal(INPUT_STREAM);
                        storeLocal(streamLocal);
                        loadArg(0);
                        loadLocal(streamLocal);
                        invokeStatic(ADAPTER, TRANSFORM);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        if (!patched.get()) {
            throw new IllegalStateException("Gafi Loader could not install resource hook in "
                    + TARGET_CLASS + ". Expected " + RESOURCE_METHOD + RESOURCE_DESCRIPTOR + ".");
        }
        return writer.toByteArray();
    }
}
