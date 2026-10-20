package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Validates the hook against a released Fabric Loader binary when CI supplies the fixture JAR.
 * Standard local test runs skip this test unless -PgafiFabricLoaderJar points at that JAR.
 */
class FabricLoaderBinaryCompatibilityTest {
    private static final String PROPERTY = "gafi.loader.fabric.loader.jar";

    @Test
    void hooksReleasedMixinServiceBinaryBeforeRuntime() throws IOException {
        String configuredJar = System.getProperty(PROPERTY);
        assumeTrue(configuredJar != null && !configuredJar.isBlank(),
                "Set -PgafiFabricLoaderJar to run the released Fabric Loader binary check");

        Path loaderJar = Path.of(configuredJar);
        assertTrue(Files.isRegularFile(loaderJar), "Fabric Loader fixture JAR not found: " + loaderJar);

        byte[] original;
        String classEntryName = MixinServiceKnotPatcher.TARGET_CLASS + ".class";
        try (JarFile jar = new JarFile(loaderJar.toFile())) {
            JarEntry entry = jar.getJarEntry(classEntryName);
            assertNotNull(entry, "Released Fabric Loader JAR is missing " + classEntryName);
            try (InputStream input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }

        byte[] snapshot = original.clone();
        byte[] transformed = MixinServiceKnotPatcher.transform(
                MixinServiceKnotPatcher.TARGET_CLASS, original);

        assertNotNull(transformed);
        assertFalse(Arrays.equals(snapshot, transformed),
                "The released Mixin service class should receive both compatibility hooks");
        assertTrue(Arrays.equals(snapshot, original),
                "The released class bytes read from Fabric Loader must remain unchanged");

        AtomicBoolean resourceHook = new AtomicBoolean();
        AtomicBoolean classByteHook = new AtomicBoolean();
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (opcode != Opcodes.INVOKESTATIC
                                || !owner.equals("dev/gafipro/gafiloader/MixinResourceRemapper")) {
                            return;
                        }
                        if (methodName.equals("transform")
                                && methodDescriptor.equals(
                                        "(Ljava/lang/String;Ljava/io/InputStream;)Ljava/io/InputStream;")) {
                            resourceHook.set(true);
                        }
                        if (methodName.equals("transformClassBytes")
                                && methodDescriptor.equals("(Ljava/lang/String;[B)[B")) {
                            classByteHook.set(true);
                        }
                    }
                };
            }
        }, 0);

        assertTrue(resourceHook.get(), "Expected the Mixin resource hook in released Fabric Loader");
        assertTrue(classByteHook.get(), "Expected the pre-Mixin class-byte hook in released Fabric Loader");
    }
}
