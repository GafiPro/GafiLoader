package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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

    private static final Map<String, ExpectedHandler> EXPECTED_LEGACY_HANDLERS = Map.of(
            "checkThreadOnDev", new ExpectedHandler(
                    "(" + SCREEN_DESCRIPTOR + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "setScreen", "HEAD", null, "NONE"),
            "onScreenRemove", new ExpectedHandler(
                    "(" + SCREEN_DESCRIPTOR + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "setScreen", "INVOKE", SCREEN_DESCRIPTOR + "removed()V", "AFTER"),
            "onScreenRemoveBecauseStopping", new ExpectedHandler(
                    "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "destroy", "INVOKE", SCREEN_DESCRIPTOR + "removed()V", "AFTER"),
            "beforeScreenTick", new ExpectedHandler(
                    "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "tick", "INVOKE", SCREEN_DESCRIPTOR + "tick()V", "NONE"),
            "afterScreenTick", new ExpectedHandler(
                    "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "tick", "INVOKE", SCREEN_DESCRIPTOR + "tick()V", "AFTER"),
            "beforeLoadingScreenTick", new ExpectedHandler(
                    "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "doWorldLoad", "INVOKE", "L" + LEVEL_LOADING_SCREEN + ";tick()V", "NONE"),
            "afterLoadingScreenTick", new ExpectedHandler(
                    "(" + CALLBACK_INFO_DESCRIPTOR + ")V",
                    "doWorldLoad", "INVOKE", "L" + MINECRAFT + ";runTick(Z)V", "NONE")
    );

    private static final Map<String, FieldShape> EXPECTED_LEGACY_FIELDS = Map.of(
            "LOGGER", new FieldShape(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    LOGGER_DESCRIPTOR, Set.of(UNIQUE_ANNOTATION)),
            "DEBUG_SCREEN", new FieldShape(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "Z", Set.of(UNIQUE_ANNOTATION)),
            "screen", new FieldShape(Opcodes.ACC_PUBLIC,
                    SCREEN_DESCRIPTOR, Set.of(SHADOW_ANNOTATION)),
            "gameThread", new FieldShape(Opcodes.ACC_PRIVATE,
                    "Ljava/lang/Thread;", Set.of(SHADOW_ANNOTATION)),
            "tickingScreen", new FieldShape(Opcodes.ACC_PRIVATE,
                    SCREEN_DESCRIPTOR, Set.of(UNIQUE_ANNOTATION))
    );

    /*
     * Strict instruction fingerprints for the audited Fabric API 1.21.11 source.
     * The adapter discards these methods and replaces them with generated 26.2
     * Mixins, so matching only their names/selectors is not sufficient: a changed
     * upstream body must stop preparation until its behavior has been reviewed.
     */
    private static final Map<String, List<String>> EXPECTED_LEGACY_METHOD_BODIES = Map.ofEntries(
            Map.entry("<init>", List.of(
                    varToken(Opcodes.ALOAD, 0),
                    methodToken(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false),
                    insnToken(Opcodes.RETURN)
            )),
            Map.entry("checkThreadOnDev", List.of(
                    methodToken(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                            "()Ljava/lang/Thread;", false),
                    varToken(Opcodes.ASTORE, 3),
                    fieldToken(Opcodes.GETSTATIC, LEGACY_MIXIN_CLASS, "DEBUG_SCREEN", "Z"),
                    jumpToken(Opcodes.IFEQ, 0),
                    varToken(Opcodes.ALOAD, 3),
                    varToken(Opcodes.ALOAD, 0),
                    fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "gameThread", "Ljava/lang/Thread;"),
                    jumpToken(Opcodes.IF_ACMPEQ, 0),
                    fieldToken(Opcodes.GETSTATIC, LEGACY_MIXIN_CLASS, "LOGGER", LOGGER_DESCRIPTOR),
                    constantToken("Attempted to set screen to \"{}\" outside the render thread (\"{}\"). "
                            + "This will likely follow a crash! Make sure to call setScreen on the render thread."),
                    varToken(Opcodes.ALOAD, 1),
                    varToken(Opcodes.ALOAD, 3),
                    methodToken(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getName",
                            "()Ljava/lang/String;", false),
                    methodToken(Opcodes.INVOKEINTERFACE, LOGGER, "error",
                            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", true),
                    labelToken(0),
                    insnToken(Opcodes.RETURN)
            )),
            Map.entry("onScreenRemove", screenRemoveBody()),
            Map.entry("onScreenRemoveBecauseStopping", screenRemoveBody()),
            Map.entry("beforeScreenTick", beforeScreenTickBody()),
            Map.entry("beforeLoadingScreenTick", beforeScreenTickBody()),
            Map.entry("afterScreenTick", afterScreenTickBody()),
            Map.entry("afterLoadingScreenTick", afterScreenTickBody()),
            Map.entry("<clinit>", List.of(
                    constantToken("fabric-screen-api-v1"),
                    methodToken(Opcodes.INVOKESTATIC, LOGGER_FACTORY, "getLogger",
                            "(Ljava/lang/String;)" + LOGGER_DESCRIPTOR, false),
                    fieldToken(Opcodes.PUTSTATIC, LEGACY_MIXIN_CLASS, "LOGGER", LOGGER_DESCRIPTOR),
                    methodToken(Opcodes.INVOKESTATIC, FABRIC_LOADER, "getInstance",
                            "()L" + FABRIC_LOADER + ";", true),
                    methodToken(Opcodes.INVOKEINTERFACE, FABRIC_LOADER, "isDevelopmentEnvironment",
                            "()Z", true),
                    jumpToken(Opcodes.IFNE, 0),
                    constantToken("fabric.debugScreen"),
                    methodToken(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                            "(Ljava/lang/String;)Z", false),
                    jumpToken(Opcodes.IFEQ, 1),
                    labelToken(0),
                    insnToken(Opcodes.ICONST_1),
                    jumpToken(Opcodes.GOTO, 2),
                    labelToken(1),
                    insnToken(Opcodes.ICONST_0),
                    labelToken(2),
                    fieldToken(Opcodes.PUTSTATIC, LEGACY_MIXIN_CLASS, "DEBUG_SCREEN", "Z"),
                    insnToken(Opcodes.RETURN)
            ))
    );

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
        rejectScreenMixinsOutsideClient(root, expectedPackage);
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
            String mixinName = screenMixinName(element.getAsString(), expectedPackage);
            if ("MinecraftMixin".equals(mixinName)) {
                minecraftIndex = i;
                minecraftCount++;
            } else if ("GuiMixin".equals(mixinName)) {
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

        JsonElement guiEntry = guiIndex >= 0
                ? client.get(guiIndex).deepCopy() : new JsonPrimitive("GuiMixin");
        JsonArray updated = new JsonArray();
        for (int i = 0; i < client.size(); i++) {
            if (i == guiIndex) continue;
            if (i == minecraftIndex) updated.add(guiEntry.deepCopy());
            updated.add(client.get(i).deepCopy());
        }

        root.add("client", updated);
        return JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static void rejectScreenMixinsOutsideClient(JsonObject root, String expectedPackage)
            throws IOException {
        for (String section : List.of("mixins", "server")) {
            JsonElement sectionElement = root.get(section);
            if (sectionElement == null || !sectionElement.isJsonArray()) continue;

            for (JsonElement entry : sectionElement.getAsJsonArray()) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) continue;
                String mixinName = screenMixinName(entry.getAsString(), expectedPackage);
                if (mixinName != null) {
                    throw new IOException("The legacy fabric-screen-api-v1 Mixin config lists "
                            + mixinName + " in its \"" + section + "\" section; screen lifecycle Mixins "
                            + "must be registered only in the client array.");
                }
            }
        }
    }

    private static String screenMixinName(String configuredName, String expectedPackage) {
        String simpleName = configuredName;
        String qualifiedPrefix = expectedPackage + ".";
        if (simpleName.startsWith(qualifiedPrefix)) {
            simpleName = simpleName.substring(qualifiedPrefix.length());
        }
        if ("MinecraftMixin".equals(simpleName) || "GuiMixin".equals(simpleName)) {
            return simpleName;
        }
        return null;
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
        Set<String> unexpectedMixinProperties = new LinkedHashSet<>();
        int[] mixinAnnotationCount = {0};
        int[] mixinTargetCount = {0};
        int[] classAccess = {-1};
        String[] superclass = {null};
        Set<String> interfaces = new LinkedHashSet<>();
        Map<String, FieldShape> fields = new LinkedHashMap<>();
        Set<String> duplicateFields = new LinkedHashSet<>();
        Map<String, String> methodDescriptors = new LinkedHashMap<>();
        Map<String, Integer> methodAccesses = new LinkedHashMap<>();
        Set<String> duplicateMethods = new LinkedHashSet<>();
        Map<String, InjectionMetadata> injections = new LinkedHashMap<>();
        Set<String> duplicateInjectMethods = new LinkedHashSet<>();
        Map<String, List<String>> methodBodies = new LinkedHashMap<>();
        Set<String> unexpectedInjectorAnnotations = new LinkedHashSet<>();
        Set<String> unexpectedMixinMethodAnnotations = new LinkedHashSet<>();
        Set<String> unexpectedMixinAnnotations = new LinkedHashSet<>();

        reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] implementedInterfaces) {
                classAccess[0] = access;
                superclass[0] = superName;
                if (implementedInterfaces != null) {
                    for (String implementedInterface : implementedInterfaces) {
                        interfaces.add(implementedInterface);
                    }
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION.equals(descriptor)) {
                    if (isMixinFrameworkAnnotation(descriptor)) {
                        unexpectedMixinAnnotations.add("class:" + descriptor);
                    }
                    return null;
                }
                mixinAnnotationCount[0]++;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    private void recordTarget(Object value) {
                        if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                            targets.add(type.getInternalName());
                            mixinTargetCount[0]++;
                        } else {
                            unexpectedMixinProperties.add("value");
                        }
                    }

                    @Override
                    public void visit(String property, Object value) {
                        if ("value".equals(property)) recordTarget(value);
                        else unexpectedMixinProperties.add(property);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String property) {
                        if (!"value".equals(property)) {
                            unexpectedMixinProperties.add(property);
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                recordTarget(value);
                            }
                        };
                    }

                    @Override
                    public void visitEnum(String property, String enumDescriptor, String value) {
                        unexpectedMixinProperties.add(property);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String property, String nestedDescriptor) {
                        unexpectedMixinProperties.add(property);
                        return null;
                    }
                };
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                         String descriptor, boolean visible) {
                if (isMixinFrameworkAnnotation(descriptor)) {
                    unexpectedMixinAnnotations.add("class-type:" + descriptor);
                }
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                Set<String> annotations = new LinkedHashSet<>();
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        annotations.add(annotationDescriptor);
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                        if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                            unexpectedMixinAnnotations.add("field-type:" + name + ":" + annotationDescriptor);
                        }
                        return null;
                    }

                    @Override
                    public void visitEnd() {
                        FieldShape previous = fields.putIfAbsent(
                                name, new FieldShape(access, descriptor, Set.copyOf(annotations)));
                        if (previous != null) duplicateFields.add(name);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (methodDescriptors.putIfAbsent(name, descriptor) != null) {
                    duplicateMethods.add(name);
                }
                methodAccesses.putIfAbsent(name, access);
                List<String> body = new ArrayList<>();
                Map<Label, Integer> labelIds = new IdentityHashMap<>();
                int[] nextLabelId = {0};
                java.util.function.ToIntFunction<Label> labelId = label ->
                        labelIds.computeIfAbsent(label, ignored -> nextLabelId[0]++);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int opcode) {
                        body.add(insnToken(opcode));
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        body.add("N:" + opcode + ":" + operand);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int variable) {
                        body.add(varToken(opcode, variable));
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        body.add(typeToken(opcode, type));
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        body.add(fieldToken(opcode, owner, fieldName, fieldDescriptor));
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        body.add(methodToken(opcode, owner, methodName, methodDescriptor, isInterface));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String invokedName, String invokedDescriptor,
                                                       org.objectweb.asm.Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
                        body.add("D:" + invokedName + ":" + invokedDescriptor + ":"
                                + bootstrapMethodHandle + ":"
                                + java.util.Arrays.toString(bootstrapMethodArguments));
                    }

                    @Override
                    public void visitJumpInsn(int opcode, Label target) {
                        body.add(jumpToken(opcode, labelId.applyAsInt(target)));
                    }

                    @Override
                    public void visitLabel(Label label) {
                        body.add(labelToken(labelId.applyAsInt(label)));
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        body.add(constantToken(value));
                    }

                    @Override
                    public void visitIincInsn(int variable, int increment) {
                        body.add("INC:" + variable + ":" + increment);
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, Label defaultLabel, Label... labels) {
                        List<Integer> targets = new ArrayList<>();
                        for (Label target : labels) targets.add(labelId.applyAsInt(target));
                        body.add("TABLE:" + min + ":" + max + ":" + labelId.applyAsInt(defaultLabel) + ":" + targets);
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) {
                        List<Integer> targets = new ArrayList<>();
                        for (Label target : labels) targets.add(labelId.applyAsInt(target));
                        body.add("LOOKUP:" + java.util.Arrays.toString(keys) + ":"
                                + labelId.applyAsInt(defaultLabel) + ":" + targets);
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
                        body.add("MULTI:" + arrayDescriptor + ":" + dimensions);
                    }

                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String exceptionType) {
                        body.add("TRY:" + labelId.applyAsInt(start) + ":" + labelId.applyAsInt(end) + ":"
                                + labelId.applyAsInt(handler) + ":" + exceptionType);
                    }

                    @Override
                    public void visitAttribute(org.objectweb.asm.Attribute attribute) {
                        body.add("ATTR:" + attribute.type);
                    }

                    @Override
                    public void visitEnd() {
                        methodBodies.put(name, List.copyOf(body));
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (!INJECT_ANNOTATION.equals(annotationDescriptor)) {
                            if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                                if (annotationDescriptor.startsWith(
                                        "Lorg/spongepowered/asm/mixin/injection/")) {
                                    unexpectedInjectorAnnotations.add(name + ":" + annotationDescriptor);
                                } else {
                                    unexpectedMixinMethodAnnotations.add(name + ":" + annotationDescriptor);
                                }
                            }
                            return null;
                        }

                        InjectionMetadata metadata = new InjectionMetadata();
                        if (injections.putIfAbsent(name, metadata) != null) {
                            duplicateInjectMethods.add(name);
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String property, Object value) {
                                if ("method".equals(property) && value instanceof String selector) {
                                    metadata.selectors.add(selector);
                                } else {
                                    metadata.unexpectedInjectProperties.add(property);
                                }
                            }

                            @Override
                            public void visitEnum(String property, String enumDescriptor, String value) {
                                metadata.unexpectedInjectProperties.add(property);
                            }

                            @Override
                            public AnnotationVisitor visitAnnotation(String property, String nestedDescriptor) {
                                if ("at".equals(property) && AT_ANNOTATION.equals(nestedDescriptor)) {
                                    return atVisitor(metadata);
                                }
                                metadata.unexpectedInjectProperties.add(property);
                                return null;
                            }

                            @Override
                            public AnnotationVisitor visitArray(String property) {
                                if ("method".equals(property)) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visit(String ignored, Object value) {
                                            if (value instanceof String selector) {
                                                metadata.selectors.add(selector);
                                            } else {
                                                metadata.malformed = true;
                                            }
                                        }
                                    };
                                }
                                if ("at".equals(property)) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override
                                        public AnnotationVisitor visitAnnotation(String ignored,
                                                String nestedDescriptor) {
                                            if (AT_ANNOTATION.equals(nestedDescriptor)) {
                                                return atVisitor(metadata);
                                            }
                                            metadata.unexpectedInjectProperties.add("at");
                                            return null;
                                        }

                                        @Override
                                        public void visit(String ignored, Object value) {
                                            metadata.unexpectedInjectProperties.add("at");
                                        }

                                        @Override
                                        public void visitEnum(String ignored, String enumDescriptor,
                                                              String value) {
                                            metadata.unexpectedInjectProperties.add("at");
                                        }

                                        @Override
                                        public AnnotationVisitor visitArray(String ignored) {
                                            metadata.unexpectedInjectProperties.add("at");
                                            return null;
                                        }
                                    };
                                }
                                metadata.unexpectedInjectProperties.add(property);
                                return null;
                            }
                        };
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor,
                                                                       boolean visible) {
                        if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                            unexpectedMixinAnnotations.add(name + ":parameter[" + parameter + "]:"
                                    + annotationDescriptor);
                        }
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor, boolean visible) {
                        if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                            unexpectedMixinAnnotations.add(name + ":type:" + annotationDescriptor);
                        }
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitInsnAnnotation(int typeRef, org.objectweb.asm.TypePath typePath,
                                                                 String annotationDescriptor, boolean visible) {
                        if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                            unexpectedMixinAnnotations.add(name + ":instruction:" + annotationDescriptor);
                        }
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitTryCatchAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, String annotationDescriptor, boolean visible) {
                        if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                            unexpectedMixinAnnotations.add(name + ":try-catch:" + annotationDescriptor);
                        }
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitLocalVariableAnnotation(int typeRef,
                            org.objectweb.asm.TypePath typePath, org.objectweb.asm.Label[] start,
                            org.objectweb.asm.Label[] end, int[] index, String annotationDescriptor,
                            boolean visible) {
                        if (isMixinFrameworkAnnotation(annotationDescriptor)) {
                            unexpectedMixinAnnotations.add(name + ":local-variable:" + annotationDescriptor);
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (classAccess[0] != (Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER)
                || !"java/lang/Object".equals(superclass[0]) || !interfaces.isEmpty()) {
            throw new IOException("The legacy screen Mixin class header no longer matches the audited "
                    + "package-private abstract 1.21.11 class; refusing to replace an unrecognized type.");
        }
        if (mixinAnnotationCount[0] != 1 || mixinTargetCount[0] != 1
                || !targets.equals(Set.of(MINECRAFT)) || !unexpectedMixinProperties.isEmpty()) {
            throw new IOException("The legacy screen Mixin must target only " + MINECRAFT
                    + " exactly once with the audited @Mixin metadata; found targets " + targets + ".");
        }
        if (!duplicateFields.isEmpty() || !fields.equals(EXPECTED_LEGACY_FIELDS)) {
            throw new IOException("The legacy screen Mixin fields no longer match the audited 1.21.11 "
                    + "shadow/unique field contract; found " + fields.keySet() + ".");
        }
        if (!duplicateMethods.isEmpty() || !duplicateInjectMethods.isEmpty()
                || !unexpectedInjectorAnnotations.isEmpty() || !unexpectedMixinMethodAnnotations.isEmpty()
                || !unexpectedMixinAnnotations.isEmpty()) {
            throw new IOException("The legacy screen Mixin contains duplicate methods or unreviewed "
                    + "Mixin annotations: " + unexpectedMixinAnnotations + ".");
        }

        Set<String> allowedMethods = new LinkedHashSet<>(EXPECTED_LEGACY_HANDLERS.keySet());
        allowedMethods.add("<init>");
        allowedMethods.add("<clinit>");
        Set<String> unexpectedMethods = new LinkedHashSet<>(methodDescriptors.keySet());
        unexpectedMethods.removeAll(allowedMethods);
        if (!unexpectedMethods.isEmpty() || !injections.keySet().equals(EXPECTED_LEGACY_HANDLERS.keySet())) {
            throw new IOException("The legacy screen Mixin handler set differs from the audited 1.21.11 "
                    + "implementation; found handlers " + injections.keySet() + ".");
        }
        // This adapter replaces the legacy Mixin class rather than copying its
        // constructors/initializer. Require the exact JVM shapes present in the
        // audited package-private Java class before discarding those methods.
        if (!"()V".equals(methodDescriptors.get("<init>"))
                || methodAccesses.getOrDefault("<init>", -1) != 0
                || !"()V".equals(methodDescriptors.get("<clinit>"))
                || methodAccesses.getOrDefault("<clinit>", -1) != Opcodes.ACC_STATIC) {
            throw new IOException("The legacy screen Mixin constructor/class initializer no longer matches "
                    + "the audited package-private 1.21.11 class; refusing to discard unrecognized initialization.");
        }

        for (Map.Entry<String, List<String>> entry : EXPECTED_LEGACY_METHOD_BODIES.entrySet()) {
            List<String> actualBody = methodBodies.get(entry.getKey());
            if (!entry.getValue().equals(actualBody)) {
                throw new IOException("Legacy screen method '" + entry.getKey()
                        + "' no longer matches its audited 1.21.11 bytecode body; refusing to replace "
                        + "unrecognized behavior.");
            }
        }

        for (Map.Entry<String, ExpectedHandler> entry : EXPECTED_LEGACY_HANDLERS.entrySet()) {
            String handlerName = entry.getKey();
            ExpectedHandler expected = entry.getValue();
            InjectionMetadata actual = injections.get(handlerName);
            if (!expected.descriptor().equals(methodDescriptors.get(handlerName))
                    || actual == null
                    || methodAccesses.getOrDefault(handlerName, -1) != Opcodes.ACC_PRIVATE
                    || actual.malformed
                    || !actual.unexpectedInjectProperties.isEmpty()
                    || !actual.unexpectedAtProperties.isEmpty()
                    || !actual.selectors.equals(List.of(expected.selector()))
                    || !actual.points.equals(List.of(new AtSelector(
                            expected.atValue(), expected.atTarget(), expected.atShift())))) {
                throw new IOException("Legacy screen handler '" + handlerName
                        + "' no longer matches its audited 1.21.11 descriptor/selector/@At contract; "
                        + "refusing to replace unrecognized behavior.");
            }
        }
    }

    private static AnnotationVisitor atVisitor(InjectionMetadata metadata) {
        String[] value = {null};
        String[] target = {null};
        String[] shift = {"NONE"};
        Set<String> seen = new LinkedHashSet<>();
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String property, Object propertyValue) {
                if ("value".equals(property) && propertyValue instanceof String selector
                        && seen.add(property)) {
                    value[0] = selector;
                } else if ("target".equals(property) && propertyValue instanceof String selector
                        && seen.add(property)) {
                    target[0] = selector;
                } else {
                    metadata.unexpectedAtProperties.add(property);
                }
            }

            @Override
            public void visitEnum(String property, String enumDescriptor, String enumValue) {
                if ("shift".equals(property) && AT_SHIFT_ANNOTATION.equals(enumDescriptor)
                        && seen.add(property)) {
                    shift[0] = enumValue;
                } else {
                    metadata.unexpectedAtProperties.add(property);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String property, String nestedDescriptor) {
                metadata.unexpectedAtProperties.add(property);
                return null;
            }

            @Override
            public AnnotationVisitor visitArray(String property) {
                metadata.unexpectedAtProperties.add(property);
                return null;
            }

            @Override
            public void visitEnd() {
                metadata.points.add(new AtSelector(value[0], target[0], shift[0]));
            }
        };
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
        // Preserve Java's short-circuit ||: the system property is read only outside a dev environment.
        Label readDebugProperty = new Label();
        Label storeDebugFlag = new Label();
        clinit.visitJumpInsn(Opcodes.IFEQ, readDebugProperty);
        clinit.visitInsn(Opcodes.ICONST_1);
        clinit.visitJumpInsn(Opcodes.GOTO, storeDebugFlag);
        clinit.visitLabel(readDebugProperty);
        clinit.visitLdcInsn("fabric.debugScreen");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                "(Ljava/lang/String;)Z", false);
        clinit.visitLabel(storeDebugFlag);
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

    private static List<String> screenRemoveBody() {
        return List.of(
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "screen", SCREEN_DESCRIPTOR),
                methodToken(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "remove",
                        "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false),
                methodToken(Opcodes.INVOKEVIRTUAL, EVENT, "invoker", "()Ljava/lang/Object;", false),
                typeToken(Opcodes.CHECKCAST, SCREEN_EVENTS + "$Remove"),
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "screen", SCREEN_DESCRIPTOR),
                methodToken(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$Remove", "onRemove",
                        "(" + SCREEN_DESCRIPTOR + ")V", true),
                insnToken(Opcodes.RETURN)
        );
    }

    private static List<String> beforeScreenTickBody() {
        return List.of(
                varToken(Opcodes.ALOAD, 0),
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "screen", SCREEN_DESCRIPTOR),
                fieldToken(Opcodes.PUTFIELD, LEGACY_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR),
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR),
                methodToken(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "beforeTick",
                        "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false),
                methodToken(Opcodes.INVOKEVIRTUAL, EVENT, "invoker", "()Ljava/lang/Object;", false),
                typeToken(Opcodes.CHECKCAST, SCREEN_EVENTS + "$BeforeTick"),
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR),
                methodToken(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$BeforeTick", "beforeTick",
                        "(" + SCREEN_DESCRIPTOR + ")V", true),
                insnToken(Opcodes.RETURN)
        );
    }

    private static List<String> afterScreenTickBody() {
        return List.of(
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR),
                methodToken(Opcodes.INVOKESTATIC, SCREEN_EVENTS, "afterTick",
                        "(" + SCREEN_DESCRIPTOR + ")" + EVENT_DESCRIPTOR, false),
                methodToken(Opcodes.INVOKEVIRTUAL, EVENT, "invoker", "()Ljava/lang/Object;", false),
                typeToken(Opcodes.CHECKCAST, SCREEN_EVENTS + "$AfterTick"),
                varToken(Opcodes.ALOAD, 0),
                fieldToken(Opcodes.GETFIELD, LEGACY_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR),
                methodToken(Opcodes.INVOKEINTERFACE, SCREEN_EVENTS + "$AfterTick", "afterTick",
                        "(" + SCREEN_DESCRIPTOR + ")V", true),
                varToken(Opcodes.ALOAD, 0),
                insnToken(Opcodes.ACONST_NULL),
                fieldToken(Opcodes.PUTFIELD, LEGACY_MIXIN_CLASS, "tickingScreen", SCREEN_DESCRIPTOR),
                insnToken(Opcodes.RETURN)
        );
    }

    private static boolean isMixinFrameworkAnnotation(String descriptor) {
        return descriptor.startsWith("Lorg/spongepowered/asm/mixin/")
                || descriptor.startsWith("Lcom/llamalad7/mixinextras/");
    }

    private static String insnToken(int opcode) {
        return "I:" + opcode;
    }

    private static String varToken(int opcode, int variable) {
        return "V:" + opcode + ":" + variable;
    }

    private static String typeToken(int opcode, String type) {
        return "T:" + opcode + ":" + type;
    }

    private static String fieldToken(int opcode, String owner, String name, String descriptor) {
        return "F:" + opcode + ":" + owner + ":" + name + ":" + descriptor;
    }

    private static String methodToken(int opcode, String owner, String name, String descriptor,
                                      boolean isInterface) {
        return "M:" + opcode + ":" + owner + ":" + name + ":" + descriptor + ":" + isInterface;
    }

    private static String jumpToken(int opcode, int labelId) {
        return "J:" + opcode + ":" + labelId;
    }

    private static String labelToken(int labelId) {
        return "L:" + labelId;
    }

    private static String constantToken(Object value) {
        return "C:" + String.valueOf(value);
    }

    private record ExpectedHandler(String descriptor, String selector, String atValue,
                                   String atTarget, String atShift) {}

    private record FieldShape(int access, String descriptor, Set<String> annotations) {}

    private record AtSelector(String value, String target, String shift) {}

    private static final class InjectionMetadata {
        private final List<String> selectors = new ArrayList<>();
        private final List<AtSelector> points = new ArrayList<>();
        private final Set<String> unexpectedInjectProperties = new LinkedHashSet<>();
        private final Set<String> unexpectedAtProperties = new LinkedHashSet<>();
        private boolean malformed;
    }

    record Result(byte[] minecraftMixinBytes, byte[] guiMixinBytes, byte[] guiExtensionsBytes) {}
}
