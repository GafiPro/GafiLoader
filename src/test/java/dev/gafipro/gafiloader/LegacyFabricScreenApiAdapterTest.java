package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyFabricScreenApiAdapterTest {
    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String AT_SHIFT_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/injection/At$Shift;";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GUI_MANAGER = "net/minecraft/client/gui/GuiManager";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String SCREEN_DESCRIPTOR = "L" + SCREEN + ";";
    private static final String CALLBACK_INFO_DESCRIPTOR =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String SCREEN_REMOVED_TARGET = SCREEN_DESCRIPTOR + "removed()V";
    private static final String SCREEN_TICK_TARGET = SCREEN_DESCRIPTOR + "tick()V";
    private static final String LOADING_SCREEN_TICK_TARGET =
            "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;tick()V";

    @Test
    void generatesTheTwoTarget26_2ScreenLifecycleAndSharedInterface() throws Exception {
        LegacyFabricScreenApiAdapter.Result result = LegacyFabricScreenApiAdapter.adapt(
                legacyMixin("net/minecraft/client/Minecraft",
                        "setScreen", "destroy", "tick", "doWorldLoad"));

        ClassReader guiMixin = new ClassReader(result.guiMixinBytes());
        ClassReader minecraftMixin = new ClassReader(result.minecraftMixinBytes());
        ClassReader extensions = new ClassReader(result.guiExtensionsBytes());

        assertEquals("net/fabricmc/fabric/mixin/screen/GuiMixin", guiMixin.getClassName());
        assertEquals("net/fabricmc/fabric/mixin/screen/MinecraftMixin", minecraftMixin.getClassName());
        assertEquals("net/fabricmc/fabric/impl/client/screen/GuiExtensions", extensions.getClassName());
        assertArrayEquals(new String[] {
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions"
        }, guiMixin.getInterfaces());

        assertEquals("net/minecraft/client/gui/GuiManager", mixinTarget(result.guiMixinBytes()));
        assertEquals("net/minecraft/client/Minecraft", mixinTarget(result.minecraftMixinBytes()));

        assertEquals(Set.of("setScreen", "tick"), injectionSelectors(result.guiMixinBytes()));
        assertEquals(Set.of("<init>", "exitWorldAndClose", "doWorldLoad"),
                injectionSelectors(result.minecraftMixinBytes()));

        assertTrue(hasMethodCall(result.guiMixinBytes(),
                "net/minecraft/client/Minecraft", "getRunningThread"));
        assertTrue(hasMethodCall(result.minecraftMixinBytes(),
                "net/minecraft/client/gui/GuiManager", "screen"));
        assertTrue(hasInjectionTarget(result.minecraftMixinBytes(),
                "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"));

        // Verify the generated handlers still dispatch the legacy ScreenEvents contract.
        // Selector assertions alone would not catch a handler that injects correctly but does nothing.
        assertEquals(List.of(
                "java/lang/Thread#currentThread()Ljava/lang/Thread;",
                "net/minecraft/client/Minecraft#getRunningThread()Ljava/lang/Thread;",
                "java/lang/Thread#getName()Ljava/lang/String;",
                "org/slf4j/Logger#error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"
        ), methodCalls(result.guiMixinBytes(), "checkThreadOnDev"));
        assertEquals(List.of(
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#remove("
                        + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;",
                "net/fabricmc/fabric/api/event/Event#invoker()Ljava/lang/Object;",
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$Remove#onRemove("
                        + SCREEN_DESCRIPTOR + ")V"
        ), methodCalls(result.guiMixinBytes(), "onScreenRemove"));
        assertEquals(List.of(
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#beforeTick("
                        + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;",
                "net/fabricmc/fabric/api/event/Event#invoker()Ljava/lang/Object;",
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$BeforeTick#beforeTick("
                        + SCREEN_DESCRIPTOR + ")V"
        ), methodCalls(result.guiMixinBytes(), "beforeScreenTick"));
        assertEquals(List.of(
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#afterTick("
                        + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;",
                "net/fabricmc/fabric/api/event/Event#invoker()Ljava/lang/Object;",
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$AfterTick#afterTick("
                        + SCREEN_DESCRIPTOR + ")V"
        ), methodCalls(result.guiMixinBytes(), "afterScreenTick"));
        assertEquals(List.of(
                "net/minecraft/client/gui/GuiManager#screen()" + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#remove("
                        + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;",
                "net/fabricmc/fabric/api/event/Event#invoker()Ljava/lang/Object;",
                "net/minecraft/client/gui/GuiManager#screen()" + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$Remove#onRemove("
                        + SCREEN_DESCRIPTOR + ")V"
        ), methodCalls(result.minecraftMixinBytes(), "onScreenRemoveBecauseStopping"));
        assertEquals(List.of(
                "net/minecraft/client/gui/GuiManager#screen()" + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions#setTickingScreen("
                        + SCREEN_DESCRIPTOR + ")V",
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions#getTickingScreen()"
                        + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#beforeTick("
                        + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;",
                "net/fabricmc/fabric/api/event/Event#invoker()Ljava/lang/Object;",
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions#getTickingScreen()"
                        + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$BeforeTick#beforeTick("
                        + SCREEN_DESCRIPTOR + ")V"
        ), methodCalls(result.minecraftMixinBytes(), "beforeLoadingScreenTick"));
        assertEquals(List.of(
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions#getTickingScreen()"
                        + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#afterTick("
                        + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;",
                "net/fabricmc/fabric/api/event/Event#invoker()Ljava/lang/Object;",
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions#getTickingScreen()"
                        + SCREEN_DESCRIPTOR,
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$AfterTick#afterTick("
                        + SCREEN_DESCRIPTOR + ")V",
                "net/fabricmc/fabric/impl/client/screen/GuiExtensions#setTickingScreen("
                        + SCREEN_DESCRIPTOR + ")V"
        ), methodCalls(result.minecraftMixinBytes(), "afterLoadingScreenTick"));

        // Exact 26.2 Fabric API hooks: normal screen events move to GuiManager,
        // while loading-screen and shutdown lifecycle hooks remain on Minecraft.
        assertInjection(result.guiMixinBytes(), "checkThreadOnDev",
                "setScreen", "HEAD", null, "NONE");
        assertInjection(result.guiMixinBytes(), "onScreenRemove",
                "setScreen", "INVOKE", SCREEN_REMOVED_TARGET, "AFTER");
        assertInjection(result.guiMixinBytes(), "beforeScreenTick",
                "tick", "INVOKE", SCREEN_TICK_TARGET, "NONE");
        assertInjection(result.guiMixinBytes(), "afterScreenTick",
                "tick", "INVOKE", SCREEN_TICK_TARGET, "AFTER");
        assertInjection(result.minecraftMixinBytes(), "onInit",
                "<init>", "RETURN", null, "NONE");
        assertInjection(result.minecraftMixinBytes(), "onScreenRemoveBecauseStopping",
                "exitWorldAndClose", "INVOKE", SCREEN_REMOVED_TARGET, "AFTER");
        assertInjection(result.minecraftMixinBytes(), "beforeLoadingScreenTick",
                "doWorldLoad", "INVOKE", LOADING_SCREEN_TICK_TARGET, "NONE");
        assertInjection(result.minecraftMixinBytes(), "afterLoadingScreenTick",
                "doWorldLoad", "INVOKE",
                "Lnet/minecraft/client/Minecraft;renderFrame(Z)V", "NONE");

        AtomicReference<Set<String>> interfaceMethods = new AtomicReference<>(new LinkedHashSet<>());
        extensions.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                interfaceMethods.get().add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        assertEquals(Set.of(
                "getTickingScreen()Lnet/minecraft/client/gui/screens/Screen;",
                "setTickingScreen(Lnet/minecraft/client/gui/screens/Screen;)V"
        ), interfaceMethods.get());
    }

    @Test
    void preservesShortCircuitSemanticsForDebugScreenFlag() throws Exception {
        LegacyFabricScreenApiAdapter.Result result = LegacyFabricScreenApiAdapter.adapt(
                legacyMixin(MINECRAFT, "setScreen", "destroy", "tick", "doWorldLoad"));

        assertEquals(List.of(
                "org/slf4j/LoggerFactory#getLogger(Ljava/lang/String;)Lorg/slf4j/Logger;",
                "net/fabricmc/loader/api/FabricLoader#getInstance()Lnet/fabricmc/loader/api/FabricLoader;",
                "net/fabricmc/loader/api/FabricLoader#isDevelopmentEnvironment()Z",
                "java/lang/Boolean#getBoolean(Ljava/lang/String;)Z"
        ), methodCalls(result.guiMixinBytes(), "<clinit>"));
        assertEquals(List.of(Opcodes.IFEQ, Opcodes.GOTO),
                jumpOpcodes(result.guiMixinBytes(), "<clinit>"),
                "Boolean.getBoolean must remain on the false branch of the development-environment check.");
    }

    @Test
    void addsGuiMixinBeforeMinecraftMixinAndIsIdempotent() throws Exception {
        byte[] original = """
                {
                  "required": true,
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["GameRendererMixin", "MinecraftMixin", "ScreenMixin"],
                  "mixins": ["KeyboardHandlerMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);

        byte[] updated = LegacyFabricScreenApiAdapter.addGuiMixinToConfig(original);
        JsonObject root = JsonParser.parseString(new String(updated, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray client = root.getAsJsonArray("client");
        int guiIndex = indexOf(client, "GuiMixin");
        int minecraftIndex = indexOf(client, "MinecraftMixin");

        assertTrue(guiIndex >= 0);
        assertTrue(minecraftIndex >= 0);
        assertTrue(guiIndex < minecraftIndex,
                "GuiMixin must be registered before MinecraftMixin, as in the 26.2 Fabric API config.");
        assertEquals("KeyboardHandlerMixin", root.getAsJsonArray("mixins").get(0).getAsString());
        assertSame(updated, LegacyFabricScreenApiAdapter.addGuiMixinToConfig(updated),
                "Reapplying the config migration must not serialize or duplicate an already adapted config.");
    }

    @Test
    void movesAnExistingGuiMixinBeforeMinecraftMixinAndPreservesOtherEntries() throws Exception {
        byte[] original = """
                {
                  "required": true,
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["GameRendererMixin", "MinecraftMixin", "ScreenMixin", "GuiMixin"],
                  "mixins": ["KeyboardHandlerMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);

        byte[] updated = LegacyFabricScreenApiAdapter.addGuiMixinToConfig(original);
        JsonArray client = JsonParser.parseString(new String(updated, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("client");

        assertEquals("GameRendererMixin", client.get(0).getAsString());
        assertEquals("GuiMixin", client.get(1).getAsString());
        assertEquals("MinecraftMixin", client.get(2).getAsString());
        assertEquals("ScreenMixin", client.get(3).getAsString());
        assertEquals(4, client.size());
        assertSame(updated, LegacyFabricScreenApiAdapter.addGuiMixinToConfig(updated));
    }

    @Test
    void rejectsDuplicateMixinEntriesAndUnexpectedPackage() {
        byte[] duplicateGui = """
                {
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["GuiMixin", "MinecraftMixin", "GuiMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] duplicateMinecraft = """
                {
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["MinecraftMixin", "MinecraftMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] unexpectedPackage = """
                {
                  "package": "net.fabricmc.fabric.mixin.other",
                  "client": ["MinecraftMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(duplicateGui));
        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(duplicateMinecraft));
        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(unexpectedPackage));
    }

    @Test
    void rejectsScreenMixinsRegisteredOutsideTheClientArray() {
        byte[] commonGuiMixin = """
                {
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["MinecraftMixin"],
                  "mixins": ["KeyboardHandlerMixin", "GuiMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] serverMinecraftMixin = """
                {
                  "package": "net.fabricmc.fabric.mixin.screen",
                  "client": ["MinecraftMixin"],
                  "server": ["net.fabricmc.fabric.mixin.screen.MinecraftMixin"]
                }
                """.getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(commonGuiMixin));
        assertThrows(IOException.class,
                () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(serverMinecraftMixin));
    }

    @Test
    void rejectsAChangedMixinTargetOrMissingLegacyInjectionInsteadOfPartiallyAdapting() {
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin("net/minecraft/client/gui/GuiManager",
                        "setScreen", "destroy", "tick", "doWorldLoad")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin("net/minecraft/client/Minecraft",
                        "setScreen", "tick", "doWorldLoad")));
    }

    @Test
    void rejectsMultipleTargetsChangedLegacyAtOptionsAndChangedHandlerDescriptors() {
        String[] selectors = {"setScreen", "destroy", "tick", "doWorldLoad"};
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin(new String[] {MINECRAFT, GUI_MANAGER}, selectors, null, false, null)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin(new String[] {MINECRAFT}, selectors,
                        "afterLoadingScreenTick", false, null)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin(new String[] {MINECRAFT}, selectors, null, true, null)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin(new String[] {MINECRAFT}, selectors, null, false, "beforeScreenTick")));
    }

    @Test
    void rejectsUnexpectedLegacyClassFieldAndHandlerAccessFlags() {
        String[] selectors = {"setScreen", "destroy", "tick", "doWorldLoad"};
        byte[] valid = legacyMixin(new String[] {MINECRAFT}, selectors, null, false, null);

        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withClassAccess(valid, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withFieldAccess(valid, "screen", Opcodes.ACC_PRIVATE)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withMethodAccess(valid, "checkThreadOnDev", Opcodes.ACC_PUBLIC)));
    }

    @Test
    void rejectsMissingOrUnexpectedLegacyConstructorAndClassInitializerShapes() {
        String[] selectors = {"setScreen", "destroy", "tick", "doWorldLoad"};
        byte[] valid = legacyMixin(new String[] {MINECRAFT}, selectors, null, false, null);

        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withoutMethod(valid, "<init>")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withoutMethod(valid, "<clinit>")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withMethodDescriptor(valid, "<init>", "(I)V")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withMethodAccess(valid, "<init>", Opcodes.ACC_PUBLIC)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withMethodDescriptor(valid, "<clinit>", "(I)V")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withMethodAccess(valid, "<clinit>", 0)));
    }

    @Test
    void rejectsChangedLegacyHandlerAndClassInitializerBytecode() {
        String[] selectors = {"setScreen", "destroy", "tick", "doWorldLoad"};
        byte[] valid = legacyMixin(new String[] {MINECRAFT}, selectors, null, false, null);

        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withChangedMethodInvocation(valid, "onScreenRemove",
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "remove", "beforeTick")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                withChangedClassInitializerJump(valid, Opcodes.IFNE, Opcodes.IFEQ)));
    }

    @Test
    void rejectsMixinParameterAnnotationsThatWouldBeDiscarded() {
        byte[] valid = legacyMixin(new String[] {MINECRAFT},
                new String[] {"setScreen", "destroy", "tick", "doWorldLoad"}, null, false, null);
        byte[] withCoerce = withParameterAnnotation(valid, "checkThreadOnDev", 0,
                "Lorg/spongepowered/asm/mixin/injection/Coerce;");

        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(withCoerce));
    }

    @Test
    void rejectsMalformedOrIncompleteMixinConfigs() {
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(
                "{not-json".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.addGuiMixinToConfig(
                "{\"client\":[\"ScreenMixin\"]}".getBytes(StandardCharsets.UTF_8)));
    }

    private static int indexOf(JsonArray array, String value) {
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (element.isJsonPrimitive() && value.equals(element.getAsString())) return i;
        }
        return -1;
    }

    private static String mixinTarget(byte[] bytes) {
        AtomicReference<String> target = new AtomicReference<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"value".equals(name)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (value instanceof Type type) target.set(type.getInternalName());
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return target.get();
    }

    private static Set<String> injectionSelectors(byte[] bytes) {
        Set<String> selectors = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!INJECT_ANNOTATION.equals(annotationDescriptor)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if (!"method".equals(property)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String selector) selectors.add(selector);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return selectors;
    }

    private static boolean hasInjectionTarget(byte[] bytes, String expectedTarget) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!INJECT_ANNOTATION.equals(annotationDescriptor)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String property, String nestedDescriptor) {
                                if (!"at".equals(property) || !AT_ANNOTATION.equals(nestedDescriptor)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String name, Object value) {
                                        if ("target".equals(name) && expectedTarget.equals(value)) found[0] = true;
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static List<Integer> jumpOpcodes(byte[] bytes, String expectedMethod) {
        List<Integer> opcodes = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!expectedMethod.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        opcodes.add(opcode);
                    }
                };
            }
        }, 0);
        return List.copyOf(opcodes);
    }

    private static List<String> methodCalls(byte[] bytes, String expectedMethod) {
        List<String> calls = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!expectedMethod.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        calls.add(owner + "#" + methodName + methodDescriptor);
                    }
                };
            }
        }, 0);
        return List.copyOf(calls);
    }

    private static boolean hasMethodCall(byte[] bytes, String expectedOwner, String expectedName) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (expectedOwner.equals(owner) && expectedName.equals(methodName)) found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }

    private static void assertInjection(byte[] bytes, String handlerName, String expectedSelector,
                                         String expectedAtValue, String expectedAtTarget,
                                         String expectedShift) {
        AtomicReference<String> selector = new AtomicReference<>();
        AtomicReference<String> atValue = new AtomicReference<>();
        AtomicReference<String> atTarget = new AtomicReference<>();
        AtomicReference<String> atShift = new AtomicReference<>("NONE");

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!handlerName.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!INJECT_ANNOTATION.equals(annotationDescriptor)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if (!"method".equals(property)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String text) selector.set(text);
                                    }
                                };
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String property, String nestedDescriptor) {
                                if (!"at".equals(property) || !AT_ANNOTATION.equals(nestedDescriptor)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String propertyName, Object value) {
                                        if ("value".equals(propertyName) && value instanceof String text) {
                                            atValue.set(text);
                                        } else if ("target".equals(propertyName) && value instanceof String text) {
                                            atTarget.set(text);
                                        }
                                    }

                                    @Override
                                    public void visitEnum(String propertyName, String enumDescriptor,
                                                          String enumValue) {
                                        if ("shift".equals(propertyName)
                                                && AT_SHIFT_ANNOTATION.equals(enumDescriptor)) {
                                            atShift.set(enumValue);
                                        }
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        assertEquals(expectedSelector, selector.get(), handlerName + " @Inject.method");
        assertEquals(expectedAtValue, atValue.get(), handlerName + " @At.value");
        assertEquals(expectedAtTarget, atTarget.get(), handlerName + " @At.target");
        assertEquals(expectedShift, atShift.get(), handlerName + " @At.shift");
    }

    static byte[] legacyMixin(String target, String... selectors) {
        return legacyMixin(new String[] {target}, selectors, null, false, null);
    }

    private static byte[] legacyMixin(String[] targets, String[] selectors, String changedAtHandler,
                                      boolean addUnexpectedInjectOption, String wrongDescriptorHandler) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                "net/fabricmc/fabric/mixin/screen/MinecraftMixin", null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_ANNOTATION, true);
        AnnotationVisitor targetValues = mixin.visitArray("value");
        for (String target : targets) targetValues.visit(null, Type.getObjectType(target));
        targetValues.visitEnd();
        mixin.visitEnd();

        addLegacyField(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "LOGGER", "Lorg/slf4j/Logger;", "Lorg/spongepowered/asm/mixin/Unique;");
        addLegacyField(writer, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "DEBUG_SCREEN", "Z", "Lorg/spongepowered/asm/mixin/Unique;");
        addLegacyField(writer, Opcodes.ACC_PUBLIC, "screen", SCREEN_DESCRIPTOR,
                "Lorg/spongepowered/asm/mixin/Shadow;");
        addLegacyField(writer, Opcodes.ACC_PRIVATE, "gameThread", "Ljava/lang/Thread;",
                "Lorg/spongepowered/asm/mixin/Shadow;");
        addLegacyField(writer, Opcodes.ACC_PRIVATE, "tickingScreen", SCREEN_DESCRIPTOR,
                "Lorg/spongepowered/asm/mixin/Unique;");

        addLegacyConstructor(writer);
        addLegacyClassInitializer(writer);

        List<LegacyHandlerFixture> handlers = List.of(
                new LegacyHandlerFixture("checkThreadOnDev",
                        "(" + SCREEN_DESCRIPTOR + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "setScreen", "HEAD", null, "NONE"),
                new LegacyHandlerFixture("onScreenRemove",
                        "(" + SCREEN_DESCRIPTOR + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "setScreen", "INVOKE", SCREEN_REMOVED_TARGET, "AFTER"),
                new LegacyHandlerFixture("onScreenRemoveBecauseStopping",
                        "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "destroy", "INVOKE", SCREEN_REMOVED_TARGET, "AFTER"),
                new LegacyHandlerFixture("beforeScreenTick",
                        "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "tick", "INVOKE", SCREEN_TICK_TARGET, "NONE"),
                new LegacyHandlerFixture("afterScreenTick",
                        "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "tick", "INVOKE", SCREEN_TICK_TARGET, "AFTER"),
                new LegacyHandlerFixture("beforeLoadingScreenTick",
                        "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "doWorldLoad", "INVOKE", LOADING_SCREEN_TICK_TARGET, "NONE"),
                new LegacyHandlerFixture("afterLoadingScreenTick",
                        "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                        "doWorldLoad", "INVOKE",
                        "Lnet/minecraft/client/Minecraft;runTick(Z)V", "NONE")
        );

        Set<String> includedSelectors = Set.of(selectors);
        for (LegacyHandlerFixture fixture : handlers) {
            if (!includedSelectors.contains(fixture.selector())) continue;
            String descriptor = fixture.name().equals(wrongDescriptorHandler) ? "()V" : fixture.descriptor();
            MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE,
                    fixture.name(), descriptor, null, null);
            AnnotationVisitor inject = handler.visitAnnotation(INJECT_ANNOTATION, true);
            AnnotationVisitor methods = inject.visitArray("method");
            methods.visit(null, fixture.selector());
            methods.visitEnd();
            if (addUnexpectedInjectOption && "checkThreadOnDev".equals(fixture.name())) {
                inject.visit("require", 0);
            }
            AnnotationVisitor points = inject.visitArray("at");
            AnnotationVisitor at = points.visitAnnotation(null, AT_ANNOTATION);
            at.visit("value", fixture.atValue());
            String atTarget = fixture.name().equals(changedAtHandler)
                    ? "Lnet/minecraft/client/Minecraft;renderFrame(Z)V" : fixture.atTarget();
            if (atTarget != null) at.visit("target", atTarget);
            if (!"NONE".equals(fixture.atShift())) {
                at.visitEnum("shift", AT_SHIFT_ANNOTATION, fixture.atShift());
            }
            at.visitEnd();
            points.visitEnd();
            inject.visitEnd();
            handler.visitCode();
            addLegacyHandlerBody(handler, fixture.name());
            handler.visitMaxs(0, 0);
            handler.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addLegacyConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(0, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static void addLegacyClassInitializer(ClassWriter writer) {
        String mixinClass = "net/fabricmc/fabric/mixin/screen/MinecraftMixin";
        MethodVisitor initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitLdcInsn("fabric-screen-api-v1");
        initializer.visitMethodInsn(Opcodes.INVOKESTATIC, "org/slf4j/LoggerFactory", "getLogger",
                "(Ljava/lang/String;)Lorg/slf4j/Logger;", false);
        initializer.visitFieldInsn(Opcodes.PUTSTATIC, mixinClass, "LOGGER", "Lorg/slf4j/Logger;");
        initializer.visitMethodInsn(Opcodes.INVOKESTATIC, "net/fabricmc/loader/api/FabricLoader",
                "getInstance", "()Lnet/fabricmc/loader/api/FabricLoader;", true);
        initializer.visitMethodInsn(Opcodes.INVOKEINTERFACE, "net/fabricmc/loader/api/FabricLoader",
                "isDevelopmentEnvironment", "()Z", true);
        Label developmentEnvironment = new Label();
        Label debugDisabled = new Label();
        Label join = new Label();
        initializer.visitJumpInsn(Opcodes.IFNE, developmentEnvironment);
        initializer.visitLdcInsn("fabric.debugScreen");
        initializer.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                "(Ljava/lang/String;)Z", false);
        initializer.visitJumpInsn(Opcodes.IFEQ, debugDisabled);
        initializer.visitLabel(developmentEnvironment);
        initializer.visitInsn(Opcodes.ICONST_1);
        initializer.visitJumpInsn(Opcodes.GOTO, join);
        initializer.visitLabel(debugDisabled);
        initializer.visitInsn(Opcodes.ICONST_0);
        initializer.visitLabel(join);
        initializer.visitFieldInsn(Opcodes.PUTSTATIC, mixinClass, "DEBUG_SCREEN", "Z");
        initializer.visitInsn(Opcodes.RETURN);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
    }

    private static void addLegacyHandlerBody(MethodVisitor handler, String name) {
        String mixinClass = "net/fabricmc/fabric/mixin/screen/MinecraftMixin";
        switch (name) {
            case "checkThreadOnDev" -> {
                Label done = new Label();
                handler.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                        "()Ljava/lang/Thread;", false);
                handler.visitVarInsn(Opcodes.ASTORE, 3);
                handler.visitFieldInsn(Opcodes.GETSTATIC, mixinClass, "DEBUG_SCREEN", "Z");
                handler.visitJumpInsn(Opcodes.IFEQ, done);
                handler.visitVarInsn(Opcodes.ALOAD, 3);
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "gameThread", "Ljava/lang/Thread;");
                handler.visitJumpInsn(Opcodes.IF_ACMPEQ, done);
                handler.visitFieldInsn(Opcodes.GETSTATIC, mixinClass, "LOGGER", "Lorg/slf4j/Logger;");
                handler.visitLdcInsn("Attempted to set screen to \"{}\" outside the render thread (\"{}\"). "
                        + "This will likely follow a crash! Make sure to call setScreen on the render thread.");
                handler.visitVarInsn(Opcodes.ALOAD, 1);
                handler.visitVarInsn(Opcodes.ALOAD, 3);
                handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName",
                        "()Ljava/lang/String;", false);
                handler.visitMethodInsn(Opcodes.INVOKEINTERFACE, "org/slf4j/Logger", "error",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true);
                handler.visitLabel(done);
            }
            case "onScreenRemove", "onScreenRemoveBecauseStopping" -> {
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "screen", SCREEN_DESCRIPTOR);
                handler.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "remove",
                        "(" + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;", false);
                handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "net/fabricmc/fabric/api/event/Event", "invoker", "()Ljava/lang/Object;", false);
                handler.visitTypeInsn(Opcodes.CHECKCAST,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$Remove");
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "screen", SCREEN_DESCRIPTOR);
                handler.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$Remove", "onRemove",
                        "(" + SCREEN_DESCRIPTOR + ")V", true);
            }
            case "beforeScreenTick", "beforeLoadingScreenTick" -> {
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "screen", SCREEN_DESCRIPTOR);
                handler.visitFieldInsn(Opcodes.PUTFIELD, mixinClass, "tickingScreen", SCREEN_DESCRIPTOR);
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "tickingScreen", SCREEN_DESCRIPTOR);
                handler.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "beforeTick",
                        "(" + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;", false);
                handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "net/fabricmc/fabric/api/event/Event", "invoker", "()Ljava/lang/Object;", false);
                handler.visitTypeInsn(Opcodes.CHECKCAST,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$BeforeTick");
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "tickingScreen", SCREEN_DESCRIPTOR);
                handler.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$BeforeTick", "beforeTick",
                        "(" + SCREEN_DESCRIPTOR + ")V", true);
            }
            case "afterScreenTick", "afterLoadingScreenTick" -> {
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "tickingScreen", SCREEN_DESCRIPTOR);
                handler.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "afterTick",
                        "(" + SCREEN_DESCRIPTOR + ")Lnet/fabricmc/fabric/api/event/Event;", false);
                handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "net/fabricmc/fabric/api/event/Event", "invoker", "()Ljava/lang/Object;", false);
                handler.visitTypeInsn(Opcodes.CHECKCAST,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$AfterTick");
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitFieldInsn(Opcodes.GETFIELD, mixinClass, "tickingScreen", SCREEN_DESCRIPTOR);
                handler.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                        "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents$AfterTick", "afterTick",
                        "(" + SCREEN_DESCRIPTOR + ")V", true);
                handler.visitVarInsn(Opcodes.ALOAD, 0);
                handler.visitInsn(Opcodes.ACONST_NULL);
                handler.visitFieldInsn(Opcodes.PUTFIELD, mixinClass, "tickingScreen", SCREEN_DESCRIPTOR);
            }
            default -> throw new IllegalArgumentException("Unexpected legacy screen handler: " + name);
        }
        handler.visitInsn(Opcodes.RETURN);
    }

    private static byte[] withParameterAnnotation(byte[] original, String methodName,
                                                   int parameter, String annotationDescriptor) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!methodName.equals(name)) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitEnd() {
                        AnnotationVisitor annotation = super.visitParameterAnnotation(
                                parameter, annotationDescriptor, true);
                        if (annotation != null) annotation.visitEnd();
                        super.visitEnd();
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withChangedMethodInvocation(byte[] original, String targetMethod,
                                                       String owner, String oldName, String newName) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!targetMethod.equals(name)) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String actualOwner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        if (owner.equals(actualOwner) && oldName.equals(methodName)) methodName = newName;
                        super.visitMethodInsn(opcode, actualOwner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withChangedClassInitializerJump(byte[] original, int fromOpcode, int toOpcode) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<clinit>".equals(name)) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean changed;

                    @Override
                    public void visitJumpInsn(int opcode, Label label) {
                        if (!changed && opcode == fromOpcode) {
                            opcode = toOpcode;
                            changed = true;
                        }
                        super.visitJumpInsn(opcode, label);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withMethodDescriptor(byte[] original, String methodName,
                                               String replacementDescriptor) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return super.visitMethod(access, name,
                        methodName.equals(name) ? replacementDescriptor : descriptor,
                        signature, exceptions);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withoutMethod(byte[] original, String methodName) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return methodName.equals(name) ? null
                        : super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withClassAccess(byte[] original, int replacementAccess) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                super.visit(version, replacementAccess, name, signature, superName, interfaces);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withFieldAccess(byte[] original, String fieldName, int replacementAccess) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                              String signature, Object value) {
                return super.visitField(fieldName.equals(name) ? replacementAccess : access,
                        name, descriptor, signature, value);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] withMethodAccess(byte[] original, String methodName, int replacementAccess) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return super.visitMethod(methodName.equals(name) ? replacementAccess : access,
                        name, descriptor, signature, exceptions);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void addLegacyField(ClassWriter writer, int access, String name,
                                       String descriptor, String annotationDescriptor) {
        org.objectweb.asm.FieldVisitor field = writer.visitField(access, name, descriptor, null, null);
        field.visitAnnotation(annotationDescriptor, true).visitEnd();
        field.visitEnd();
    }

    private record LegacyHandlerFixture(String name, String descriptor, String selector,
                                        String atValue, String atTarget, String atShift) {}

}
