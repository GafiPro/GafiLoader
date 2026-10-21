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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyApiBridgePatcherTest {
    @TempDir
    Path temporaryDirectory;

    private static final String DISPLAY_SLOT = "net/minecraft/world/scores/DisplaySlot";
    private static final String CHAT_FORMATTING = "net/minecraft/ChatFormatting";
    private static final String READ_ONLY_SCORE_INFO = "net/minecraft/world/scores/ReadOnlyScoreInfo";
    private static final String NUMBER_FORMAT = "net/minecraft/network/chat/numbers/NumberFormat";
    private static final String MUTABLE_COMPONENT = "net/minecraft/network/chat/MutableComponent";
    private static final String COMMON_COMPONENTS = "net/minecraft/network/chat/CommonComponents";
    private static final String SAFE_DESC =
            "(Lnet/minecraft/world/scores/ReadOnlyScoreInfo;"
                    + "Lnet/minecraft/network/chat/numbers/NumberFormat;)"
                    + "Lnet/minecraft/network/chat/MutableComponent;";

    @Test
    void supportsStructuralBridgesOnTheActualOfficial26_2TargetWhenFixtureIsAvailable() throws Exception {
        String configured = System.getProperty("gafi.loader.real.target.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null && !configured.isBlank()
                && Files.isRegularFile(Path.of(configured)),
                "Official Minecraft 26.2 fixture is only available in integration CI");

        Path targetJar = Path.of(configured);
        TargetApiIndex api = TargetApiIndex.load(targetJar);
        byte[] displayBytes = readClass(targetJar, DISPLAY_SLOT);
        byte[] scoreBytes = readClass(targetJar, READ_ONLY_SCORE_INFO);

        assertNotNull(LegacyApiBridgePatcher.transformClassForRuntime(DISPLAY_SLOT, displayBytes, api),
                "Official target should support the verified DisplaySlot bridge");
        assertNotNull(LegacyApiBridgePatcher.transformClassForRuntime(READ_ONLY_SCORE_INFO, scoreBytes, api),
                "Official target should support the verified ReadOnlyScoreInfo bridge");
    }

    @Test
    void insertsDisplaySlotCompatibilityBridgeOnlyWhenEveryTargetColorExists() throws Exception {
        TargetApiIndex api = targetApi(true, true);
        byte[] patched = LegacyApiBridgePatcher.transformClassForRuntime(
                DISPLAY_SLOT, displaySlotClass(), api);

        assertNotNull(patched);
        List<String> instructions = methodInstructions(patched, "teamColorToSlot", "(L"
                + CHAT_FORMATTING + ";)L" + DISPLAY_SLOT + ";");
        assertTrue(instructions.contains("CALL " + CHAT_FORMATTING + "#isColor()Z"));
        assertTrue(instructions.contains("CALL java/lang/Enum#name()Ljava/lang/String;"));
        assertTrue(instructions.contains("CALL java/lang/Enum#valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;"));
    }

    @Test
    void insertsSafeFormatValueBridgeThatPreservesNullFallbackAndDelegatesForNonNullScores() throws Exception {
        TargetApiIndex api = targetApi(true, true);
        byte[] patched = LegacyApiBridgePatcher.transformClassForRuntime(
                READ_ONLY_SCORE_INFO, readOnlyScoreInfoClass(), api);

        assertNotNull(patched);
        List<String> instructions = methodInstructions(patched, "safeFormatValue", SAFE_DESC);
        assertTrue(instructions.contains("FIELD " + COMMON_COMPONENTS + "#EMPTY:L"
                + MUTABLE_COMPONENT + ";"));
        assertTrue(instructions.contains("CALL " + READ_ONLY_SCORE_INFO + "#formatValue"
                + "(L" + NUMBER_FORMAT + ";)L" + MUTABLE_COMPONENT + ";"));
        assertTrue(instructions.contains("JUMP " + Opcodes.IFNONNULL));
    }

    @Test
    void doesNotPatchWhenTargetApiCannotSupportTheBridge() throws Exception {
        TargetApiIndex api = targetApi(false, false);
        assertNull(LegacyApiBridgePatcher.transformClassForRuntime(
                DISPLAY_SLOT, displaySlotClass(), api));
        assertNull(LegacyApiBridgePatcher.transformClassForRuntime(
                READ_ONLY_SCORE_INFO, readOnlyScoreInfoClass(), api));
    }

    private static byte[] readClass(Path jarPath, String className) throws IOException {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarPath.toFile())) {
            java.util.jar.JarEntry entry = jar.getJarEntry(className + ".class");
            if (entry == null) {
                throw new IOException("Expected class not found in target JAR: " + className);
            }
            try (java.io.InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private TargetApiIndex targetApi(boolean includeColors, boolean includeFormatBridge) throws IOException {
        Path jar = temporaryDirectory.resolve("target-" + includeColors + "-" + includeFormatBridge + ".jar");
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(DISPLAY_SLOT + ".class", displaySlotClass(includeColors)));
        entries.add(new Entry(CHAT_FORMATTING + ".class", chatFormattingClass()));
        entries.add(new Entry(READ_ONLY_SCORE_INFO + ".class", readOnlyScoreInfoClass()));
        entries.add(new Entry(NUMBER_FORMAT + ".class", emptyClass(NUMBER_FORMAT, Opcodes.ACC_PUBLIC)));
        entries.add(new Entry(MUTABLE_COMPONENT + ".class", emptyClass(MUTABLE_COMPONENT, Opcodes.ACC_PUBLIC)));
        entries.add(new Entry(COMMON_COMPONENTS + ".class", commonComponentsClass()));
        if (!includeFormatBridge) {
            entries.removeIf(entry -> entry.name().equals(COMMON_COMPONENTS + ".class"));
        }
        writeJar(jar, entries.toArray(Entry[]::new));
        return TargetApiIndex.load(jar);
    }

    private static byte[] displaySlotClass() {
        return displaySlotClass(true);
    }

    private static byte[] displaySlotClass(boolean includeColors) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM,
                DISPLAY_SLOT, null, "java/lang/Enum", null);
        String[] colors = {"BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA", "DARK_RED",
                "DARK_PURPLE", "GOLD", "GRAY", "DARK_GRAY", "BLUE", "GREEN", "AQUA",
                "RED", "LIGHT_PURPLE", "YELLOW", "WHITE"};
        for (String color : colors) {
            if (!includeColors && color.equals("WHITE")) continue;
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                    "TEAM_" + color, "L" + DISPLAY_SLOT + ";", null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] chatFormattingClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM,
                CHAT_FORMATTING, null, "java/lang/Enum", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "isColor", "()Z", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] readOnlyScoreInfoClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                READ_ONLY_SCORE_INFO, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "formatValue",
                "(L" + NUMBER_FORMAT + ";)L" + MUTABLE_COMPONENT + ";", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] commonComponentsClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, COMMON_COMPONENTS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "EMPTY", "L" + MUTABLE_COMPONENT + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name, int access) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> methodInstructions(byte[] bytes, String expectedName, String expectedDescriptor) {
        List<String> instructions = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(expectedName) || !descriptor.equals(expectedDescriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        instructions.add("FIELD " + owner + "#" + name + ":" + desc);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String desc, boolean isInterface) {
                        instructions.add("CALL " + owner + "#" + name + desc);
                    }
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        instructions.add("JUMP " + opcode);
                    }
                };
            }
        }, 0);
        return instructions;
    }

    private static byte[] emptyClass(String name, int classAccess, String ignored) {
        return emptyClass(name, classAccess);
    }

    private static void writeJar(Path jarPath, Entry... entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(jarPath);
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
