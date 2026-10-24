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
 * Adds in-memory resource and class-byte hooks to Fabric Loader's Mixin service.
 *
 * <p>The patch is deliberately pinned to the class/method signatures used by Fabric Loader for
 * modern Knot launches. A changed runtime signature fails with a precise error rather than
 * claiming that Mixin compatibility remapping is active when it is not.</p>
 */
final class MixinServiceKnotPatcher {
    static final String TARGET_CLASS = "net/fabricmc/loader/impl/launch/knot/MixinServiceKnot";
    private static final String RESOURCE_METHOD = "getResourceAsStream";
    private static final String RESOURCE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final Type INPUT_STREAM = Type.getType(InputStream.class);
    private static final Type BYTE_ARRAY = Type.getType(byte[].class);
    private static final Type OBJECT = Type.getType(Object.class);
    private static final Type ADAPTER = Type.getObjectType("dev/gafipro/gafiloader/MixinResourceRemapper");
    private static final Method TRANSFORM = new Method("transform",
            "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/io/InputStream;");
    private static final Method TRANSFORM_CLASS_BYTES = new Method("transformClassBytes",
            "(Ljava/lang/String;[B)[B");
    private static final Method TRANSFORM_CLASS_NODE = new Method("transformClassNode",
            "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");

    private MixinServiceKnotPatcher() {}

    /**
     * @return transformed bytes, or {@code null} when the class is not the Mixin service
     */
    static byte[] transform(String className, byte[] classBytes) {
        if (!TARGET_CLASS.equals(className)) {
            return null;
        }
        AtomicBoolean patchedResourceReader = new AtomicBoolean(false);
        AtomicBoolean patchedClassBytecodeReader = new AtomicBoolean(false);
        AtomicBoolean patchedClassNodeReader = new AtomicBoolean(false);
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                org.objectweb.asm.MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                boolean resourceReader = RESOURCE_METHOD.equals(name)
                        && RESOURCE_DESCRIPTOR.equals(descriptor);
                boolean classBytecodeReader = name.equals("getClassBytes")
                        && (descriptor.equals("(Ljava/lang/String;Ljava/lang/String;)[B")
                        || descriptor.equals("(Ljava/lang/String;Z)[B"));
                boolean classNodeReader = name.equals("getClassNode")
                        && descriptor.equals("(Ljava/lang/String;ZI)Lorg/objectweb/asm/tree/ClassNode;");
                if (!resourceReader && !classBytecodeReader && !classNodeReader) {
                    return delegate;
                }
                if (resourceReader) {
                    patchedResourceReader.set(true);
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
                if (classBytecodeReader) {
                    patchedClassBytecodeReader.set(true);
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(int opcode) {
                            if (opcode != ARETURN) {
                                return;
                            }
                            int byteArrayLocal = newLocal(BYTE_ARRAY);
                            storeLocal(byteArrayLocal);
                            loadArg(0);
                            loadLocal(byteArrayLocal);
                            invokeStatic(ADAPTER, TRANSFORM_CLASS_BYTES);
                        }
                    };
                }
                patchedClassNodeReader.set(true);
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                    @Override
                    protected void onMethodExit(int opcode) {
                        if (opcode != ARETURN) {
                            return;
                        }
                        int nodeLocal = newLocal(OBJECT);
                        storeLocal(nodeLocal);
                        loadArg(0);
                        loadLocal(nodeLocal);
                        invokeStatic(ADAPTER, TRANSFORM_CLASS_NODE);
                        checkCast(Type.getObjectType("org/objectweb/asm/tree/ClassNode"));
                    }
                };
            }
        };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        if (!patchedResourceReader.get() || !patchedClassBytecodeReader.get() || !patchedClassNodeReader.get()) {
            throw new IllegalStateException("Gafi Loader could not install all Mixin compatibility hooks in "
                    + TARGET_CLASS + ". Expected " + RESOURCE_METHOD + RESOURCE_DESCRIPTOR
                    + ", a getClassBytes overload, and getClassNode(String, boolean, int)."
                    + " The installed Fabric Loader is not compatible with this resource hook.");
        }
        return writer.toByteArray();
    }
}
