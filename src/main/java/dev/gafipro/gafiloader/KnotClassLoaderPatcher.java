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
 * Hooks KnotClassLoader's resource path. Fabric can load mixin configuration JSON directly through
 * this classloader, bypassing MixinServiceKnot.getResourceAsStream, so both resource boundaries are
 * covered. All remapping remains in memory; the original mod JARs are never rewritten.
 */
final class KnotClassLoaderPatcher {
    static final String TARGET_CLASS = "net/fabricmc/loader/impl/launch/knot/KnotClassLoader";
    private static final String RESOURCE_METHOD = "getResourceAsStream";
    private static final String RESOURCE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final Type INPUT_STREAM = Type.getType(InputStream.class);
    private static final Type ADAPTER = Type.getObjectType("dev/gafipro/gafiloader/MixinResourceRemapper");
    private static final Method TRANSFORM = new Method("transform",
            "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/io/InputStream;");

    private KnotClassLoaderPatcher() {}

    static byte[] transform(String className, byte[] classBytes) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }
        AtomicBoolean patched = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
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
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!patched.get()) {
            throw new IllegalStateException("Gafi Loader could not install its resource hook in "
                    + TARGET_CLASS + ". Expected " + RESOURCE_METHOD + RESOURCE_DESCRIPTOR
                    + "; this Fabric Loader build is not compatible with the resource hook.");
        }
        return writer.toByteArray();
    }
}
