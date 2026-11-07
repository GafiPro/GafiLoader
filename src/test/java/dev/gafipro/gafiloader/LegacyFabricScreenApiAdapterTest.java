package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyFabricScreenApiAdapterTest {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String FINAL = "Lorg/spongepowered/asm/mixin/Final;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String MINECRAFT = "net/minecraft/client/MinecraftClient";
    private static final String GUI_MANAGER = "net/minecraft/client/gui/GuiManager";
    private static final String SCREEN = "net/minecraft/client/gui/screen/Screen";
    private static final String SCREEN_DESCRIPTOR = "L" + SCREEN + ";";

    @Test
    void splitsLegacyScreenMixinAndPreservesScreenEventHandlers() throws Exception {
        LegacyFabricScreenApiAdapter.Result result =
                LegacyFabricScreenApiAdapter.adapt(legacyMinecraftMixin());

        assertTrue(result.adapted());
        assertEquals(Set.of(LegacyFabricScreenApiAdapter.GENERATED_MIXIN_ENTRY),
                result.generatedClasses().keySet());

        ClassShape minecraftMixin = inspect(result.bytes());
        assertEquals(MINECRAFT, minecraftMixin.mixinTarget());
        assertTrue(minecraftMixin.fields().containsKey("gui"));
        assertEquals("L" + GUI_MANAGER + ";", minecraftMixin.fields().get("gui"));
        assertFalse(minecraftMixin.fields().containsKey("screen"),
                "Minecraft 26.2 no longer owns the current-screen field.");
        assertFalse(minecraftMixin.fields().containsKey("gameThread"));
        assertTrue(minecraftMixin.fields().containsKey("tickingScreen"));
        assertFalse(minecraftMixin.methods().contains("checkThreadOnDev"));
        assertFalse(minecraftMixin.methods().contains("onScreenRemove"));
        assertFalse(minecraftMixin.methods().contains("onScreenRemoveBecauseStopping"));
        assertFalse(minecraftMixin.methods().contains("beforeScreenTick"));
        assertFalse(minecraftMixin.methods().contains("afterScreenTick"));
        assertTrue(minecraftMixin.methods().contains("beforeLoadingScreenTick"));
        assertTrue(minecraftMixin.methods().contains("afterLoadingScreenTick"));

        List<String> loadingScreenInstructions =
                inspectInstructions(result.bytes(), "beforeLoadingScreenTick");
        assertTrue(loadingScreenInstructions.contains(
                "FIELD:" + LegacyFabricScreenApiAdapter.LEGACY_MIXIN + "#gui:L" + GUI_MANAGER + ";"));
        assertTrue(loadingScreenInstructions.contains(
                "METHOD:" + GUI_MANAGER + "#screen()"+ SCREEN_DESCRIPTOR));
        assertFalse(loadingScreenInstructions.stream().anyMatch(value ->
                value.contains("#screen:" + SCREEN_DESCRIPTOR)),
                "The Minecraft mixin must not read the removed Minecraft.screen field.");

        // The real 1.21.11 mixin snapshots the screen before the loading-screen
        // callback and clears that snapshot after the callback. The adaptation
        // must change only where the current screen is read, not lose this state.
        List<FieldAccess> beforeLoadingAccesses =
                inspectFieldAccesses(result.bytes(), "beforeLoadingScreenTick");
        assertTrue(beforeLoadingAccesses.contains(new FieldAccess(Opcodes.PUTFIELD,
                LegacyFabricScreenApiAdapter.LEGACY_MIXIN, "tickingScreen", SCREEN_DESCRIPTOR)));
        assertTrue(beforeLoadingAccesses.contains(new FieldAccess(Opcodes.GETFIELD,
                LegacyFabricScreenApiAdapter.LEGACY_MIXIN, "tickingScreen", SCREEN_DESCRIPTOR)));
        List<FieldAccess> afterLoadingAccesses =
                inspectFieldAccesses(result.bytes(), "afterLoadingScreenTick");
        assertTrue(afterLoadingAccesses.contains(new FieldAccess(Opcodes.GETFIELD,
                LegacyFabricScreenApiAdapter.LEGACY_MIXIN, "tickingScreen", SCREEN_DESCRIPTOR)));
        assertTrue(afterLoadingAccesses.contains(new FieldAccess(Opcodes.PUTFIELD,
                LegacyFabricScreenApiAdapter.LEGACY_MIXIN, "tickingScreen", SCREEN_DESCRIPTOR)));

        byte[] generatedBytes = result.generatedClasses()
                .get(LegacyFabricScreenApiAdapter.GENERATED_MIXIN_ENTRY);
        ClassShape guiMixin = inspect(generatedBytes);
        assertEquals(LegacyFabricScreenApiAdapter.GENERATED_MIXIN, guiMixin.className());
        assertEquals(GUI_MANAGER, guiMixin.mixinTarget());
        assertEquals(SCREEN_DESCRIPTOR, guiMixin.fields().get("screen"));
        assertTrue(guiMixin.fields().containsKey("tickingScreen"));
        assertTrue(guiMixin.methods().containsAll(
                Set.of("onScreenRemove", "beforeScreenTick", "afterScreenTick")));
        assertFalse(guiMixin.methods().contains("onScreenRemoveBecauseStopping"));
        assertFalse(guiMixin.methods().contains("checkThreadOnDev"));

        assertEquals(Set.of("setScreen"), guiMixin.injectTargets().get("onScreenRemove"));
        assertEquals(Set.of("tick"), guiMixin.injectTargets().get("beforeScreenTick"));
        assertEquals(Set.of("tick"), guiMixin.injectTargets().get("afterScreenTick"));
        assertTrue(inspectInstructions(generatedBytes, "onScreenRemove").stream().anyMatch(value ->
                value.contains("FIELD:" + LegacyFabricScreenApiAdapter.GENERATED_MIXIN + "#screen:")));
        assertTrue(inspectInstructions(generatedBytes, "afterScreenTick").stream().anyMatch(value ->
                value.contains("FIELD:" + LegacyFabricScreenApiAdapter.GENERATED_MIXIN + "#tickingScreen:")));
    }

    @Test
    void rejectsUnexpectedScreenShadowOrDescriptor() {
        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.adapt(legacyMinecraftMixin(false)),
                "A field named screen without @Shadow must not be treated as the target's screen field.");
        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.adapt(
                        legacyMinecraftMixin("Lnet/minecraft/client/gui/screens/Screen;", true)),
                "The legacy plural Screen package must be remapped before this adapter runs.");
    }

    @Test
    void registersGeneratedMixinInClientArrayExactlyOnce() throws Exception {
        byte[] original = """
                {
                  "required": true,
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["GameRendererMixin", "MinecraftMixin", "ScreenMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);

        byte[] patched = LegacyFabricScreenApiAdapter.addGeneratedMixinToConfig(original);
        byte[] patchedAgain = LegacyFabricScreenApiAdapter.addGeneratedMixinToConfig(patched);
        assertEquals(new String(patched, StandardCharsets.UTF_8),
                new String(patchedAgain, StandardCharsets.UTF_8));

        com.google.gson.JsonArray client = com.google.gson.JsonParser.parseString(
                new String(patched, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("client");
        assertEquals(4, client.size());
        assertEquals("GafiLoaderGuiManagerMixin", client.get(3).getAsString());
    }

    private static byte[] legacyMinecraftMixin() {
        return legacyMinecraftMixin(SCREEN_DESCRIPTOR, true);
    }

    private static byte[] legacyMinecraftMixin(boolean shadowScreen) {
        return legacyMinecraftMixin(SCREEN_DESCRIPTOR, shadowScreen);
    }

    private static byte[] legacyMinecraftMixin(String screenDescriptor, boolean shadowScreen) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                LegacyFabricScreenApiAdapter.LEGACY_MIXIN, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, true);
        // Mixin.value is Class<?>[]; model the real class-file annotation array encoding.
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(MINECRAFT));
        targets.visitEnd();
        mixin.visitEnd();

        FieldVisitor screen = writer.visitField(Opcodes.ACC_PUBLIC, "screen",
                screenDescriptor, null, null);
        if (shadowScreen) screen.visitAnnotation(SHADOW, true).visitEnd();
        screen.visitEnd();

        FieldVisitor gameThread = writer.visitField(Opcodes.ACC_PRIVATE, "gameThread",
                "Ljava/lang/Thread;", null, null);
        gameThread.visitAnnotation(SHADOW, true).visitEnd();
        gameThread.visitEnd();

        FieldVisitor tickingScreen = writer.visitField(Opcodes.ACC_PRIVATE, "tickingScreen",
                SCREEN_DESCRIPTOR, null, null);
        tickingScreen.visitAnnotation(UNIQUE, true).visitEnd();
        tickingScreen.visitEnd();

        addHandler(writer, "checkThreadOnDev", "(" + screenDescriptor + CALLBACK_INFO + ")V",
                null, false, false);
        addHandler(writer, "onScreenRemove", "(" + screenDescriptor + CALLBACK_INFO + ")V",
                "setScreen", true, false);
        addHandler(writer, "onScreenRemoveBecauseStopping", "(" + CALLBACK_INFO + ")V",
                "destroy", true, false);
        addHandler(writer, "beforeScreenTick", "(" + CALLBACK_INFO + ")V",
                "tick", true, false);
        addHandler(writer, "afterScreenTick", "(" + CALLBACK_INFO + ")V",
                "tick", true, true);
        addLoadingScreenHandler(writer, "beforeLoadingScreenTick", "doWorldLoad", true);
        addLoadingScreenHandler(writer, "afterLoadingScreenTick", "doWorldLoad", false);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addLoadingScreenHandler(ClassWriter writer, String name,
                                                 String target, boolean beforeTick) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name,
                "(" + CALLBACK_INFO + ")V", null, null);
        AnnotationVisitor annotation = method.visitAnnotation(INJECT, true);
        AnnotationVisitor targets = annotation.visitArray("method");
        targets.visit(null, target);
        targets.visitEnd();
        annotation.visitEnd();

        method.visitCode();
        if (beforeTick) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, LegacyFabricScreenApiAdapter.LEGACY_MIXIN,
                    "screen", SCREEN_DESCRIPTOR);
            method.visitFieldInsn(Opcodes.PUTFIELD, LegacyFabricScreenApiAdapter.LEGACY_MIXIN,
                    "tickingScreen", SCREEN_DESCRIPTOR);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, LegacyFabricScreenApiAdapter.LEGACY_MIXIN,
                    "tickingScreen", SCREEN_DESCRIPTOR);
            method.visitInsn(Opcodes.POP);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, LegacyFabricScreenApiAdapter.LEGACY_MIXIN,
                    "tickingScreen", SCREEN_DESCRIPTOR);
            method.visitInsn(Opcodes.POP);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitFieldInsn(Opcodes.PUTFIELD, LegacyFabricScreenApiAdapter.LEGACY_MIXIN,
                    "tickingScreen", SCREEN_DESCRIPTOR);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
    }

    private static void addHandler(ClassWriter writer, String name, String descriptor,
                                   String target, boolean inject, boolean readTickingScreen) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE, name, descriptor, null, null);
        if (inject) {
            AnnotationVisitor annotation = method.visitAnnotation(INJECT, true);
            AnnotationVisitor targets = annotation.visitArray("method");
            targets.visit(null, target);
            targets.visitEnd();
            annotation.visitEnd();
        }
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, LegacyFabricScreenApiAdapter.LEGACY_MIXIN,
                readTickingScreen ? "tickingScreen" : "screen",
                SCREEN_DESCRIPTOR);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static ClassShape inspect(byte[] bytes) {
        String[] className = {null};
        String[] mixinTarget = {null};
        Map<String, String> fields = new LinkedHashMap<>();
        Set<String> methods = new LinkedHashSet<>();
        Map<String, Set<String>> injectTargets = new LinkedHashMap<>();

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                className[0] = name;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String property) {
                        if (!"value".equals(property)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                                    mixinTarget[0] = type.getInternalName();
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                fields.put(name, descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.add(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!INJECT.equals(annotationDescriptor)) return null;
                        Set<String> targets = new LinkedHashSet<>();
                        injectTargets.put(name, targets);
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if (!"method".equals(property)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String target) targets.add(target);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return new ClassShape(className[0], mixinTarget[0], fields, methods, injectTargets);
    }

    private static List<FieldAccess> inspectFieldAccesses(byte[] bytes, String requestedMethod) {
        List<FieldAccess> accesses = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!requestedMethod.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        accesses.add(new FieldAccess(opcode, owner, fieldName, fieldDescriptor));
                    }
                };
            }
        }, 0);
        return accesses;
    }

    private static List<String> inspectInstructions(byte[] bytes, String requestedMethod) {
        List<String> instructions = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!requestedMethod.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        instructions.add("FIELD:" + owner + "#" + name + ":" + descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        instructions.add("METHOD:" + owner + "#" + name + descriptor);
                    }
                };
            }
        }, 0);
        return instructions;
    }

    private record FieldAccess(int opcode, String owner, String name, String descriptor) {}

    private record ClassShape(String className, String mixinTarget, Map<String, String> fields,
                              Set<String> methods, Map<String, Set<String>> injectTargets) {}
}
