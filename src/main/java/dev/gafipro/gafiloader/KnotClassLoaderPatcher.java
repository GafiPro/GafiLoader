package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

import java.io.InputStream;
import java.net.URL;
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
    private static final String URL_METHOD = "getResource";
    private static final String URL_DESCRIPTOR = "(Ljava/lang/String;)Ljava/net/URL;";
    private static final Type INPUT_STREAM = Type.getType(InputStream.class);
    private static final Type URL_TYPE = Type.getType(URL.class);
    private static final Type ADAPTER = Type.getObjectType("dev/gafipro/gafiloader/MixinResourceRemapper");
    private static final Method TRANSFORM = new Method("transform",
            "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/io/InputStream;");
    private static final Method TRANSFORM_URL = new Method("transformUrl",
            "(Ljava/lang/String;Ljava/net/URL;)Ljava/net/URL;");

    private KnotClassLoaderPatcher() {}

    static byte[] transform(String className, byte[] classBytes) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }
        AtomicBoolean patched = new AtomicBoolean(false);
        AtomicBoolean patchedUrl = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                if (URL_METHOD.equals(name) && URL_DESCRIPTOR.equals(descriptor)) {
                    patchedUrl.set(true);
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(int opcode) {
                            if (opcode != ARETURN) {
                                return;
                            }
                            int urlLocal = newLocal(URL_TYPE);
                            storeLocal(urlLocal);
                            loadArg(0);
                            loadLocal(urlLocal);
                            invokeStatic(ADAPTER, TRANSFORM_URL);
                        }
                    };
                }
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
        if (!patched.get() || !patchedUrl.get()) {
            throw new IllegalStateException("Gafi Loader could not install all resource hooks in "
                    + TARGET_CLASS + ". Expected " + RESOURCE_METHOD + RESOURCE_DESCRIPTOR
                    + " and " + URL_METHOD + URL_DESCRIPTOR
                    + "; this Fabric Loader build is not compatible with the resource hook.");
        }
        return writer.toByteArray();
    }
}
