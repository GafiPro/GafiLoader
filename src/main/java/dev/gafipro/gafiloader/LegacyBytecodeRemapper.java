package dev.gafipro.gafiloader;

import org.objectweb.asm.commons.ClassRemapper;

import java.io.IOException;
import java.util.Arrays;

/**
 * Converts legacy 1.21.11 class files from Intermediary names into the Mojang
 * named namespace used by Minecraft 26.2, including Mixin selectors and shadows.
 */
final class LegacyBytecodeRemapper {
    private final LegacyClassTweakerMappings mappings;

    LegacyBytecodeRemapper(LegacyClassTweakerMappings mappings) {
        this.mappings = mappings;
    }

    static void verifyRuntimeSupport() throws IOException {
        try {
            Class.forName(ClassRemapper.class.getName(), false,
                    LegacyBytecodeRemapper.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IOException("ASM Commons is not available on the Fabric launch classpath; "
                    + "the legacy bytecode namespace conversion cannot run safely.", exception);
        }
    }

    Result remap(byte[] originalBytes) throws IOException {
        LegacyMixinBytecodeAdapter.Result mapped =
                LegacyMixinBytecodeAdapter.remap(originalBytes, mappings);
        return new Result(mapped.bytes(), !Arrays.equals(originalBytes, mapped.bytes()));
    }

    record Result(byte[] bytes, boolean changed) {}
}
