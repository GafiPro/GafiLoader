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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    void rejectsAChangedMixinTargetOrMissingLegacyInjectionInsteadOfPartiallyAdapting() {
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin("net/minecraft/client/gui/GuiManager",
                        "setScreen", "destroy", "tick", "doWorldLoad")));
        assertThrows(IOException.class, () -> LegacyFabricScreenApiAdapter.adapt(
                legacyMixin("net/minecraft/client/Minecraft",
                        "setScreen", "tick", "doWorldLoad")));
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

    private static byte[] legacyMixin(String target, String... selectors) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "net/fabricmc/fabric/mixin/screen/MinecraftMixin", null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_ANNOTATION, true);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(target));
        targets.visitEnd();
        mixin.visitEnd();

        int index = 0;
        for (String selector : selectors) {
            MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE,
                    "legacyHandler" + index++, "()V", null, null);
            AnnotationVisitor inject = handler.visitAnnotation(INJECT_ANNOTATION, true);
            AnnotationVisitor methods = inject.visitArray("method");
            methods.visit(null, selector);
            methods.visitEnd();
            AnnotationVisitor at = inject.visitAnnotation("at", AT_ANNOTATION);
            at.visit("value", "HEAD");
            at.visitEnd();
            inject.visitEnd();
            handler.visitCode();
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 1);
            handler.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }
}
