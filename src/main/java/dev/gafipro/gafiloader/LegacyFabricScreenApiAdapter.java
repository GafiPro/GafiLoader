package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Ports the 1.21.11 fabric-screen-api-v1 MinecraftMixin to the split screen
 * ownership used by Minecraft 26.2.
 *
 * <p>The 26.2 screen manager owns the current screen and its tick loop. The
 * Minecraft target still owns the loading-screen and shutdown lifecycle.
 * This adapter therefore emits the same two-target structure used by the
 * 26.2 Fabric API branch, without adding newer screen-extraction events that
 * do not exist in the 1.21.11 API.</p>
 */
final class LegacyFabricScreenApiAdapter {
    static final String MODULE_ID = "fabric-screen-api-v1";
    static final String LEGACY_MIXIN_ENTRY =
            "net/fabricmc/fabric/mixin/screen/MinecraftMixin.class";
    static final String GUI_MIXIN_ENTRY =
            "net/fabricmc/fabric/mixin/screen/GuiMixin.class";
    static final String GUI_EXTENSIONS_ENTRY =
            "net/fabricmc/fabric/impl/client/screen/GuiExtensions.class";
    static final String MIXIN_CONFIG_ENTRY = "fabric-screen-api-v1.mixins.json";

    private static final String LEGACY_MIXIN_CLASS =
            "net/fabricmc/fabric/mixin/screen/MinecraftMixin";
    private static final String GUI_MIXIN_CLASS =
            "net/fabricmc/fabric/mixin/screen/GuiMixin";
    private static final String GUI_EXTENSIONS =
            "net/fabricmc/fabric/impl/client/screen/GuiExtensions";

    // Minecraft 26.2 uses Mojang's named namespace. The Yarn name "Gui" used by
    // Fabric's source branch is therefore GuiManager in the runtime bytecode.
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GUI_MANAGER = "net/minecraft/client/gui/GuiManager";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String LEVEL_LOADING_SCREEN =
            "net/minecraft/client/gui/screens/LevelLoadingScreen";

    private static final String MIXIN_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String FINAL_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Final;";
    private static final String UNIQUE_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String AT_SHIFT_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/injection/At$Shift;";

    private static final String CALLBACK_INFO =
            "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String SCREEN_EVENTS =
            "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents";
    private static final String EVENT = "net/fabricmc/fabric/api/event/Event";
    private static final String LOGGER = "org/slf4j/Logger";
    private static final String LOGGER_FACTORY = "org/slf4j/LoggerFactory";
    private static final String FABRIC_LOADER = "net/fabricmc/loader/api/FabricLoader";

    private static final String SCREEN_DESCRIPTOR = "L" + SCREEN + ";";
    private static final String MINECRAFT_DESCRIPTOR = "L" + MINECRAFT + ";";
    private static final String GUI_MANAGER_DESCRIPTOR = "L" + GUI_MANAGER + ";";
    private static final String GUI_EXTENSIONS_DESCRIPTOR = "L" + GUI_EXTENSIONS + ";";
    private static final String CALLBACK_INFO_DESCRIPTOR = "L" + CALLBACK_INFO + ";";
    private static final String LOGGER_DESCRIPTOR = "L" + LOGGER + ";";
    private static final String EVENT_DESCRIPTOR = "L" + EVENT + ";";
    private static final String JSON_CHARSET = StandardCharsets.UTF_8.name();

    private static final Gson JSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private LegacyFabricScreenApiAdapter() {}

    static Result adapt(byte[] remappedLegacyMixin) throws IOException {
        verifyLegacyMixin(remappedLegacyMixin);
        return new Result(buildMinecraftMixin(), buildGuiMixin(), buildGuiExtensions());
    }

    /**
     * Adds GuiMixin before MinecraftMixin, matching the 26.2 Fabric API ordering.
     * The operation is idempotent so a repeated preparation cannot duplicate it.
     */
    static byte[] addGuiMixinToConfig(byte[] originalBytes) throws IOException {
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(originalBytes, JSON_CHARSET));
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse the legacy fabric-screen-api-v1 Mixin config.", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("The legacy fabric-screen-api-v1 Mixin config must be a JSON object.");
        }

        JsonObject root = parsed.getAsJsonObject();
        String expectedPackage = LEGACY_MIXIN_CLASS.substring(0, LEGACY_MIXIN_CLASS.lastIndexOf('/'))
                .replace('/', '.');
        JsonElement packageElement = root.get("package");
        if (packageElement == null || !packageElement.isJsonPrimitive()
                || !packageElement.getAsJsonPrimitive().isString()
                || !expectedPackage.equals(packageElement.getAsString())) {
            throw new IOException("The legacy fabric-screen-api-v1 Mixin config must declare package \""
                    + expectedPackage + "\".");
        }

        JsonElement clientElement = root.get("client");
        if (clientElement == null || !clientElement.isJsonArray()) {
            throw new IOException("The legacy fabric-screen-api-v1 Mixin config is missing its client array.");
        }

        JsonArray client = clientElement.getAsJsonArray();
        int minecraftIndex = -1;
        int guiIndex = -1;
        int minecraftCount = 0;
        int guiCount = 0;
        for (int i = 0; i < client.size(); i++) {
            JsonElement element = client.get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
            String name = element.getAsString();
            if ("MinecraftMixin".equals(name)) {
                minecraftIndex = i;
                minecraftCount++;
            } else if ("GuiMixin".equals(name)) {
                guiIndex = i;
                guiCount++;
            }
        }
        if (minecraftCount != 1) {
            throw new IOException("The legacy fabric-screen-api-v1 Mixin config must list MinecraftMixin exactly once; "
                    + "found " + minecraftCount + ".");
        }
        if (guiCount > 1) {
            throw new IOException("The legacy fabric-screen-api-v1 Mixin config contains duplicate GuiMixin entries; "
                    + "refusing an ambiguous screen migration.");
        }
        if (guiIndex >= 0 && guiIndex < minecraftIndex) return originalBytes;

        JsonArray updated = new JsonArray();
        for (int i = 0; i < client.size(); i++) {
            if (i == guiIndex) continue;
            if (i == minecraftIndex) updated.add("GuiMixin");
            updated.add(client.get(i).deepCopy());
        }

        root.add("client", updated);
        return JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static void verifyLegacyMixin(byte[] classBytes) throws IOException {
        final ClassReader reader;
        try {
            reader = new ClassReader(classBytes);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Could not read the legacy fabric-screen-api-v1 MinecraftMixin class.", exception);
        }
        if (!LEGACY_MIXIN_CLASS.equals(reader.getClassName())) {
            throw new IOException("Expected legacy screen Mixin " + LEGACY_MIXIN_CLASS
                    + " but found " + reader.getClassName() + ".");
        }

        Set<String> targets = new LinkedHashSet<>();
        Set<String> injectionSelectors = new LinkedHashSet<>();
        reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof Type type
                                && type.getSort() == Type.OBJECT) {
                            targets.add(type.getInternalName());
                        }
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"value".equals(name)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                                    targets.add(type.getInternalName());
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!INJECT_ANNOTATION.equals(annotationDescriptor)) return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String property, Object value) {
                                if ("method".equals(property) && value instanceof String selector) {
                                    addSelectors(injectionSelectors, selector);
                                }
                            }

                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if (!"method".equals(property)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String ignored, Object value) {
                                        if (value instanceof String selector) {
                                            addSelectors(injectionSelectors, selector);
                                        }
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (!targets.contains(MINECRAFT)) {
            throw new IOException("The legacy screen Mixin no longer targets " + MINECRAFT
                    + "; refusing to apply the 1.21.11-to-26.2 screen adapter.");
        }
        for (String selector : Set.of("setScreen", "destroy", "tick", "doWorldLoad")) {
            if (!injectionSelectors.contains(selector)) {
                throw new IOException("The legacy screen Mixin is missing its expected @Inject target '"
                        + selector + "'; refusing a partial screen migration.");
            }
        }
    }

    private static void addSelectors(Set<String> selectors, String value) {
        for (String item : value.split(",")) {
            String selector = item.trim();
            int descriptorStart = selector.indexOf('(');
            if (descriptorStart >= 0) selector = selector.substring(0, descriptorStart);
            int ownerEnd = selector.lastIndexOf(';');
            if (ownerEnd >= 0) selector = selector.substring(ownerEnd + 1);
            if (!selector.isBlank()) selectors.add(selector);
        }
    }

    private static byte[] buildGuiMixin() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                GUI_MIXIN_CLASS, null, "java/lang/Object", new String[] { GUI_EXTENSIONS });
        addMixinTarget(writer, GUI_MANAGER);

        FieldVisitor logger = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "LOGGER", LOGGER_DESCRIPTOR, null, null);
        addFieldMarker(logger, UNIQUE_ANNOTATION);
        logger.visitEnd();

        FieldVisitor debugScreen = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "DEBUG_SCREEN", "Z", null, null);
        addFieldMarker(debugScreen, UNIQUE_ANNOTATION);
        debugScreen.visitEnd();

        FieldVisitor screen = writer.visitField(Opcodes.ACC_PRIVATE, "screen", SCREEN_DESCRIPTOR, null, null);
        addFieldMarker(screen, SHADOW_ANNOTATION);
        screen.visitEnd();

        FieldVisitor minecraft = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "minecraft", MINECRAFT_DESCRIPTOR, null, null);
        addFieldMarker(minecraft, SHADOW_ANNOTATION);
        addFieldMarker(minecraft, FINAL_ANNOTATION);
        minecraft.visitEnd();

        FieldVisitor tickingScreen = writer.visitField(Opcodes.ACC_PRIVATE,
                "tickingScreen", SCREEN_DESCRIPTOR, null, null);
        addFieldMarker(tickingScreen, UNIQUE_ANNOTATION);
        tickingScreen.visitEnd();

        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn("fabric-screen-api-v1");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, LOGGER_FACTORY, "getLogger",
                "(Ljava/lang/String;)" + LOGGER_DESCRIPTOR, false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, GUI_MIXIN_CLASS, "LOGGER", LOGGER_DESCRIPTOR);
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance",
                "()L" + FABRIC_LOADER + ";", true);
        clinit.visitMethodInsn(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "isDevelopmentEnvironment", "()Z", true);
        clinit.visitLdcInsn("fabric.debugScreen");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                "(Ljava/lang/String;)Z", false);
        clinit.visitInsn(Opcodes.IOR);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, GUI_MIXIN_CLASS, "DEBUG_SCREEN", "Z");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor checkThread = writer.visitMethod(Opcodes.ACC_PRIVATE, "checkThreadOnDev",
                "(" + SCREEN_DESCRIPTOR + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(checkThread, "setScreen", "HEAD", null, null);
        checkThread.visitCode();
        checkThread.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false);
        checkThread.visitVarInsn(Opcodes.ASTORE, 3);
        Label done = new Label();
        checkThread.visitFieldInsn(Opcodes.GETSTATIC, GUI_MIXIN_CLASS, "DEBUG_SCREEN", "Z");
        checkThread.visitJumpInsn(Opcodes.IFEQ, done);
        checkThread.visitVarInsn(Opcodes.ALOAD, 3);
        checkThread.visitVarInsn(Opcodes.ALOAD, 0);
        checkThread.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "minecraft", MINECRAFT_DESCRIPTOR);
        checkThread.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MINECRAFT, "getRunningThread",
                "()Ljava/lang/Thread;", false);
        checkThread.visitJumpInsn(Opcodes.IF_ACMPEQ, done);
        checkThread.visitFieldInsn(Opcodes.GETSTATIC, GUI_MIXIN_CLASS, "LOGGER", LOGGER_DESCRIPTOR);
        checkThread.visitLdcInsn("Attempted to set screen to \"{}\" outside the render thread (\"{}\"). "
                + "This will likely follow a crash! Make sure to call setScreen on the render thread.");
        checkThread.visitVarInsn(Opcodes.ALOAD, 1);
        checkThread.visitVarInsn(Opcodes.ALOAD, 3);
        checkThread.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName",
                "()Ljava/lang/String;", false);
        checkThread.visitMethodInsn(Opcodes.INVOKEINTERFACE, LOGGER, "error",
                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true);
        checkThread.visitLabel(done);
        checkThread.visitInsn(Opcodes.RETURN);
        checkThread.visitMaxs(0, 0);
        checkThread.visitEnd();

        MethodVisitor onScreenRemove = writer.visitMethod(Opcodes.ACC_PRIVATE, "onScreenRemove",
                "(" + SCREEN_DESCRIPTOR + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(onScreenRemove, "setScreen", "INVOKE",
                "L" + SCREEN + ";removed()V", "AFTER");
        onScreenRemove.visitCode();
        onScreenRemove.visitVarInsn(Opcodes.ALOAD, 0);
        onScreenRemove.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "screen", SCREEN_DESCRIPTOR);
        onScreenRemove.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "remove",
                "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false);
        onScreenRemove.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "invoker",
                "()Ljava/lang/Object;", false);
        onScreenRemove.visitTypeInsn(Opcodes.CHECKCAST, SCREEN_EVENTS + "$Remove");
        onScreenRemove.visitVarInsn(Opcodes.ALOAD, 0);
        onScreenRemove.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "screen", SCREEN_DESCRIPTOR);
        onScreenRemove.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$Remove", "onRemove",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        onScreenRemove.visitInsn(Opcodes.RETURN);
        onScreenRemove.visitMaxs(0, 0);
        onScreenRemove.visitEnd();

        MethodVisitor beforeScreenTick = writer.visitMethod(Opcodes.ACC_PRIVATE, "beforeScreenTick",
                "(" + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(beforeScreenTick, "tick", "INVOKE", "L" + SCREEN + ";tick()V", null);
        beforeScreenTick.visitCode();
        beforeScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeScreenTick.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "screen", SCREEN_DESCRIPTOR);
        beforeScreenTick.visitFieldInsn(Opcodes.PUTFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        beforeScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeScreenTick.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        beforeScreenTick.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "beforeTick",
                "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false);
        beforeScreenTick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "invoker",
                "()Ljava/lang/Object;", false);
        beforeScreenTick.visitTypeInsn(Opcodes.CHECKCAST, SCREEN_EVENTS + "$BeforeTick");
        beforeScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeScreenTick.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        beforeScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$BeforeTick", "beforeTick",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        beforeScreenTick.visitInsn(Opcodes.RETURN);
        beforeScreenTick.visitMaxs(0, 0);
        beforeScreenTick.visitEnd();

        MethodVisitor afterScreenTick = writer.visitMethod(Opcodes.ACC_PRIVATE, "afterScreenTick",
                "(" + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(afterScreenTick, "tick", "INVOKE", "L" + SCREEN + ";tick()V", "AFTER");
        afterScreenTick.visitCode();
        afterScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        afterScreenTick.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        afterScreenTick.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "afterTick",
                "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false);
        afterScreenTick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "invoker",
                "()Ljava/lang/Object;", false);
        afterScreenTick.visitTypeInsn(Opcodes.CHECKCAST, SCREEN_EVENTS + "$AfterTick");
        afterScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        afterScreenTick.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        afterScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$AfterTick", "afterTick",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        afterScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        afterScreenTick.visitInsn(Opcodes.ACONST_NULL);
        afterScreenTick.visitFieldInsn(Opcodes.PUTFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        afterScreenTick.visitInsn(Opcodes.RETURN);
        afterScreenTick.visitMaxs(0, 0);
        afterScreenTick.visitEnd();

        MethodVisitor getTickingScreen = writer.visitMethod(Opcodes.ACC_PUBLIC, "getTickingScreen",
                "()" + SCREEN_DESCRIPTOR, null, null);
        getTickingScreen.visitCode();
        getTickingScreen.visitVarInsn(Opcodes.ALOAD, 0);
        getTickingScreen.visitFieldInsn(Opcodes.GETFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        getTickingScreen.visitInsn(Opcodes.ARETURN);
        getTickingScreen.visitMaxs(0, 0);
        getTickingScreen.visitEnd();

        MethodVisitor setTickingScreen = writer.visitMethod(Opcodes.ACC_PUBLIC, "setTickingScreen",
                "(" + SCREEN_DESCRIPTOR + ")V", null, null);
        setTickingScreen.visitCode();
        setTickingScreen.visitVarInsn(Opcodes.ALOAD, 0);
        setTickingScreen.visitVarInsn(Opcodes.ALOAD, 1);
        setTickingScreen.visitFieldInsn(Opcodes.PUTFIELD, GUI_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR);
        setTickingScreen.visitInsn(Opcodes.RETURN);
        setTickingScreen.visitMaxs(0, 0);
        setTickingScreen.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildMinecraftMixin() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                LEGACY_MIXIN_CLASS, null, "java/lang/Object", null);
        addMixinTarget(writer, MINECRAFT);

        FieldVisitor gui = writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "gui", GUI_MANAGER_DESCRIPTOR, null, null);
        addFieldMarker(gui, SHADOW_ANNOTATION);
        addFieldMarker(gui, FINAL_ANNOTATION);
        gui.visitEnd();

        FieldVisitor guiExtensions = writer.visitField(Opcodes.ACC_PRIVATE,
                "guiExtensions", GUI_EXTENSIONS_DESCRIPTOR, null, null);
        addFieldMarker(guiExtensions, UNIQUE_ANNOTATION);
        guiExtensions.visitEnd();

        MethodVisitor onInit = writer.visitMethod(Opcodes.ACC_PRIVATE, "onInit",
                "(" + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(onInit, "<init>", "RETURN", null, null);
        onInit.visitCode();
        onInit.visitVarInsn(Opcodes.ALOAD, 0);
        onInit.visitVarInsn(Opcodes.ALOAD, 0);
        onInit.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "gui", GUI_MANAGER_DESCRIPTOR);
        onInit.visitTypeInsn(Opcodes.CHECKCAST, GUI_EXTENSIONS);
        onInit.visitFieldInsn(Opcodes.PUTFIELD, LEGACY_MIXIN_CLASS, "guiExtensions", GUI_EXTENSIONS_DESCRIPTOR);
        onInit.visitInsn(Opcodes.RETURN);
        onInit.visitMaxs(0, 0);
        onInit.visitEnd();

        MethodVisitor onScreenRemoveBecauseStopping = writer.visitMethod(Opcodes.ACC_PRIVATE,
                "onScreenRemoveBecauseStopping", "(" + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(onScreenRemoveBecauseStopping, "exitWorldAndClose", "INVOKE",
                "L" + SCREEN + ";removed()V", "AFTER");
        onScreenRemoveBecauseStopping.visitCode();
        onScreenRemoveBecauseStopping.visitVarInsn(Opcodes.ALOAD, 0);
        onScreenRemoveBecauseStopping.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "gui",
                GUI_MANAGER_DESCRIPTOR);
        onScreenRemoveBecauseStopping.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUI_MANAGER, "screen",
                "()" + SCREEN_DESCRIPTOR, false);
        onScreenRemoveBecauseStopping.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "remove",
                "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false);
        onScreenRemoveBecauseStopping.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "invoker",
                "()Ljava/lang/Object;", false);
        onScreenRemoveBecauseStopping.visitTypeInsn(Opcodes.CHECKCAST, SCREEN_EVENTS + "$Remove");
        onScreenRemoveBecauseStopping.visitVarInsn(Opcodes.ALOAD, 0);
        onScreenRemoveBecauseStopping.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "gui",
                GUI_MANAGER_DESCRIPTOR);
        onScreenRemoveBecauseStopping.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUI_MANAGER, "screen",
                "()" + SCREEN_DESCRIPTOR, false);
        onScreenRemoveBecauseStopping.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$Remove",
                "onRemove", "(" + SCREEN_DESCRIPTOR + ")V", true);
        onScreenRemoveBecauseStopping.visitInsn(Opcodes.RETURN);
        onScreenRemoveBecauseStopping.visitMaxs(0, 0);
        onScreenRemoveBecauseStopping.visitEnd();

        MethodVisitor beforeLoadingScreenTick = writer.visitMethod(Opcodes.ACC_PRIVATE,
                "beforeLoadingScreenTick", "(" + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(beforeLoadingScreenTick, "doWorldLoad", "INVOKE",
                "L" + LEVEL_LOADING_SCREEN + ";tick()V", null);
        beforeLoadingScreenTick.visitCode();
        beforeLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "gui",
                GUI_MANAGER_DESCRIPTOR);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUI_MANAGER, "screen",
                "()" + SCREEN_DESCRIPTOR, false);
        beforeLoadingScreenTick.visitVarInsn(Opcodes.ASTORE, 2);
        beforeLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "guiExtensions",
                GUI_EXTENSIONS_DESCRIPTOR);
        beforeLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 2);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, GUI_EXTENSIONS, "setTickingScreen",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        beforeLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "guiExtensions",
                GUI_EXTENSIONS_DESCRIPTOR);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, GUI_EXTENSIONS, "getTickingScreen",
                "()" + SCREEN_DESCRIPTOR, true);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "beforeTick",
                "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "invoker",
                "()Ljava/lang/Object;", false);
        beforeLoadingScreenTick.visitTypeInsn(Opcodes.CHECKCAST, SCREEN_EVENTS + "$BeforeTick");
        beforeLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        beforeLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "guiExtensions",
                GUI_EXTENSIONS_DESCRIPTOR);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, GUI_EXTENSIONS, "getTickingScreen",
                "()" + SCREEN_DESCRIPTOR, true);
        beforeLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$BeforeTick", "beforeTick",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        beforeLoadingScreenTick.visitInsn(Opcodes.RETURN);
        beforeLoadingScreenTick.visitMaxs(0, 0);
        beforeLoadingScreenTick.visitEnd();

        MethodVisitor afterLoadingScreenTick = writer.visitMethod(Opcodes.ACC_PRIVATE,
                "afterLoadingScreenTick", "(" + CALLBACK_INFO_DESCRIPTOR + ")V", null, null);
        addInject(afterLoadingScreenTick, "doWorldLoad", "INVOKE",
                "L" + MINECRAFT + ";renderFrame(Z)V", null);
        afterLoadingScreenTick.visitCode();
        afterLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        afterLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "guiExtensions",
                GUI_EXTENSIONS_DESCRIPTOR);
        afterLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, GUI_EXTENSIONS, "getTickingScreen",
                "()" + SCREEN_DESCRIPTOR, true);
        afterLoadingScreenTick.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "afterTick",
                "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false);
        afterLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT, "invoker",
                "()Ljava/lang/Object;", false);
        afterLoadingScreenTick.visitTypeInsn(Opcodes.CHECKCAST, SCREEN_EVENTS + "$AfterTick");
        afterLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        afterLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "guiExtensions",
                GUI_EXTENSIONS_DESCRIPTOR);
        afterLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, GUI_EXTENSIONS, "getTickingScreen",
                "()" + SCREEN_DESCRIPTOR, true);
        afterLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$AfterTick", "afterTick",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        afterLoadingScreenTick.visitVarInsn(Opcodes.ALOAD, 0);
        afterLoadingScreenTick.visitFieldInsn(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "guiExtensions",
                GUI_EXTENSIONS_DESCRIPTOR);
        afterLoadingScreenTick.visitInsn(Opcodes.ACONST_NULL);
        afterLoadingScreenTick.visitMethodInsn(Opcodes.INVOKEINTERFACE, GUI_EXTENSIONS, "setTickingScreen",
                "(" + SCREEN_DESCRIPTOR + ")V", true);
        afterLoadingScreenTick.visitInsn(Opcodes.RETURN);
        afterLoadingScreenTick.visitMaxs(0, 0);
        afterLoadingScreenTick.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildGuiExtensions() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                GUI_EXTENSIONS, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getTickingScreen",
                "()" + SCREEN_DESCRIPTOR, null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "setTickingScreen",
                "(" + SCREEN_DESCRIPTOR + ")V", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addMixinTarget(ClassWriter writer, String target) {
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN_ANNOTATION, true);
        AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(target));
        values.visitEnd();
        mixin.visitEnd();
    }

    private static void addFieldMarker(FieldVisitor field, String annotationDescriptor) {
        field.visitAnnotation(annotationDescriptor, true).visitEnd();
    }

    private static void addInject(MethodVisitor method, String selector, String atValue,
                                  String target, String shift) {
        AnnotationVisitor inject = method.visitAnnotation(INJECT_ANNOTATION, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, selector);
        methods.visitEnd();

        AnnotationVisitor at = inject.visitAnnotation("at", AT_ANNOTATION);
        at.visit("value", atValue);
        if (target != null) at.visit("target", target);
        if (shift != null) at.visitEnum("shift", AT_SHIFT_ANNOTATION, shift);
        at.visitEnd();
        inject.visitEnd();
    }

    record Result(byte[] minecraftMixinBytes, byte[] guiMixinBytes, byte[] guiExtensionsBytes) {}
}
