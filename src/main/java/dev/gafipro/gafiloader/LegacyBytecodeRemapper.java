package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Converts an individual legacy 1.21.11 class file from Intermediary names to
 * the Mojang names used by Minecraft 26.2. ASM's ClassRemapper covers descriptors,
 * signatures, annotations, frames, handles, and nested constant-dynamic values.
 */
final class LegacyBytecodeRemapper {
    private final LegacyClassTweakerMappings mappings;

    LegacyBytecodeRemapper(LegacyClassTweakerMappings mappings) {
        this.mappings = mappings;
    }

    Result remap(byte[] originalBytes) throws IOException {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            Remapper namespaceMappings = new Remapper() {
                @Override
                public String map(String internalName) {
                    try {
                        return mappings.mapClass(internalName);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    try {
                        return mappings.mapBytecodeMemberName(owner, name, descriptor, false);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    try {
                        return mappings.mapBytecodeMemberName(owner, name, descriptor, true);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
            };

            reader.accept(new ClassRemapper(Opcodes.ASM9, writer, namespaceMappings), 0);
            byte[] mapped = writer.toByteArray();
            return new Result(mapped, !Arrays.equals(originalBytes, mapped));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IOException("Could not remap a legacy 1.21.11 class file.", exception);
        }
    }

    record Result(byte[] bytes, boolean changed) {}
}
