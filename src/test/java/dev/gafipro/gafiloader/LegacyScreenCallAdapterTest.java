package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LegacyScreenCallAdapterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rewritesRemovedMinecraftSetScreenToGuiSetScreenOnlyWhenTargetSupportsIt() throws Exception {
        Path mojang = temporaryDirectory.resolve("client-mappings.txt");
        Path intermediary = temporaryDirectory.resolve("intermediary.tiny");
        Path targetJar = temporaryDirectory.resolve("minecraft-26.2.jar");

        Files.writeString(mojang, String.join("\n",
                "net.minecraft.client.Minecraft -> a:",
                "    1:1:void setScreen(net.minecraft.client.gui.screens.Screen):1:1 -> c",
                "net.minecraft.client.gui.screens.Screen -> b:",
                "net.minecraft.client.gui.Gui -> d:",
                ""));
        Files.writeString(intermediary, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tm\t(Lnet/minecraft/class_2;)V\tc\tmethod_1",
                "c\tb\tnet/minecraft/class_2",
                "c\td\tnet/minecraft/class_3",
                ""));

        writeJar(targetJar,
                new Entry("net/minecraft/client/Minecraft.class", minecraftClass()),
                new Entry("net/minecraft/client/gui/Gui.class", guiClass()),
                new Entry("net/minecraft/client/gui/screens/Screen.class",
                        emptyClass("net/minecraft/client/gui/screens/Screen")));

        IntermediaryMappings mappings = IntermediaryMappings.load(
                intermediary, MojangMappings.load(mojang));
        TargetApiIndex targetApi = TargetApiIndex.load(targetJar);
        byte[] transformed = IntermediaryBytecodeRemapper.remap(
                legacyCallClass(), mappings, CompatibilityScanner.AliasSet.empty(), targetApi);

        assertNotNull(transformed);
        assertEquals(List.of(
                "INSN " + Opcodes.SWAP,
                "FIELD net/minecraft/client/Minecraft#gui:Lnet/minecraft/client/gui/Gui;",
                "INSN " + Opcodes.SWAP,
                "METHOD net/minecraft/client/gui/Gui#setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"
        ), screenCallInstructions(transformed));
    }

    private static byte[] minecraftClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "gui", "Lnet/minecraft/client/gui/Gui;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] guiClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/client/gui/Gui",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "setScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;)V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyCallClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/LegacyScreenCall",
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "(Lnet/minecraft/class_1;Lnet/minecraft/class_2;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_1", "method_1",
                "(Lnet/minecraft/class_2;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> screenCallInstructions(byte[] bytes) {
        List<String> instructions = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals("run")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.SWAP) instructions.add("INSN " + opcode);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                        instructions.add("FIELD " + owner + "#" + field + ":" + desc);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        instructions.add("METHOD " + owner + "#" + method + desc);
                    }
                };
            }
        }, 0);
        return instructions;
    }

    private static void writeJar(Path path, Entry... entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (Entry entry : entries) {
                jar.putNextEntry(new JarEntry(entry.name()));
                jar.write(entry.bytes());
                jar.closeEntry();
            }
        }
    }

    private record Entry(String name, byte[] bytes) {}
}
