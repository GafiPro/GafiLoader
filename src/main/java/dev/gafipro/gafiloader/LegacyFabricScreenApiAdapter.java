package dev.gafipro.gafiloader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Splits the 1.21.11 Fabric Screen API Minecraft mixin across the 26.2
 * MinecraftClient and GuiManager targets. The original screen/tick callbacks
 * are moved to a generated GuiManager mixin; the world-loading callbacks stay
 * on Minecraft and read the current screen through Minecraft.gui.screen().
 */
final class LegacyFabricScreenApiAdapter {
    static final String LEGACY_MIXIN =
            "net/fabricmc/fabric/mixin/screen/MinecraftMixin";
    static final String GENERATED_MIXIN =
            "net/fabricmc/fabric/mixin/screen/GafiLoaderGuiManagerMixin";
    static final String GENERATED_MIXIN_ENTRY = GENERATED_MIXIN + ".class";
    static final String MIXIN_CONFIG = "fabric-screen-api-v1.mixins.json";

    private static final String LEGACY_MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MINECRAFT = "net/minecraft/client/MinecraftClient";
    private static final String LEGACY_SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String CURRENT_SCREEN = "net/minecraft/client/gui/screen/Screen";
    private static final String GUI_MANAGER = "net/minecraft/client/gui/GuiManager";
    private static final String MIXIN_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String FINAL_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Final;";
    private static final String UNIQUE_ANNOTATION =
            "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String SCREEN_FIELD = "screen";
    private static final String TICKING_SCREEN_FIELD = "tickingScreen";
    private static final String GUI_FIELD = "gui";
    private static final Set<String> MOVED_METHODS =
            Set.of("onScreenRemove", "beforeScreenTick", "afterScreenTick");
    private static final Set<String> REMOVED_METHODS = Set.of(
            "checkThreadOnDev",
            "onScreenRemoveBecauseStopping",
            "onScreenRemove",
            "beforeScreenTick",
            "afterScreenTick",
            "<clinit>");

    private LegacyFabricScreenApiAdapter() {}

    static String mapLegacyClassName(String internalName) {
        if (LEGACY_MINECRAFT.equals(internalName)) return MINECRAFT;
        if (LEGACY_SCREEN.equals(internalName)) return CURRENT_SCREEN;
        return internalName;
    }

    static Result adapt(byte[] remappedClass) throws IOException {
        final ClassReader reader;
        try {
            reader = new ClassReader(remappedClass);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Could not inspect a remapped class for the Fabric Screen API adapter.",
                    exception);
        }

        String className = reader.getClassName();
        if (!LEGACY_MIXIN.equals(className)) {
            return new Result(remappedClass, Map.of(), false);
        }

        MixinShape shape = inspect(reader);
        if (!shape.targetsMinecraft() || !shape.hasScreenShadow()
                || !("L" + CURRENT_SCREEN + ";").equals(shape.screenDescriptor())
                || !shape.hasGameThreadShadow()
                || !shape.hasMethod("onScreenRemove")
                || !shape.hasMethod("beforeScreenTick")
                || !shape.hasMethod("afterScreenTick")
                || !shape.hasMethod("beforeLoadingScreenTick")
                || !shape.hasMethod("afterLoadingScreenTick")) {
            throw new IOException("The legacy Fabric Screen API MinecraftMixin no longer has the expected "
                    + "1.21.11 structure. Refusing to apply a partial 26.2 adaptation.");
        }

        byte[] minecraftMixin = adaptMinecraftMixin(reader, shape.screenDescriptor());
        byte[] guiManagerMixin = createGuiManagerMixin(reader, shape.screenDescriptor());
        return new Result(minecraftMixin, Map.of(GENERATED_MIXIN_ENTRY, guiManagerMixin), true);
    }

    static byte[] addGeneratedMixinToConfig(byte[] originalConfig) throws IOException {
        final com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(
                    new String(originalConfig, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse " + MIXIN_CONFIG + " while registering the generated "
                    + "GuiManager mixin.", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException(MIXIN_CONFIG + " must contain a JSON object.");
        }

        com.google.gson.JsonObject root = parsed.getAsJsonObject();
        com.google.gson.JsonElement clientElement = root.get("client");
        if (clientElement == null || !clientElement.isJsonArray()) {
            throw new IOException(MIXIN_CONFIG + " has no client mixin array; refusing to register "
                    + GENERATED_MIXIN + ".");
        }

        com.google.gson.JsonArray clientMixins = clientElement.getAsJsonArray();
        String simpleName = GENERATED_MIXIN.substring(GENERATED_MIXIN.lastIndexOf('/') + 1);
        for (com.google.gson.JsonElement entry : clientMixins) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()
                    && simpleName.equals(entry.getAsString())) {
                return originalConfig;
            }
        }
        clientMixins.add(simpleName);
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static MixinShape inspect(ClassReader reader) {
        boolean[] targetMinecraft = {false};
        boolean[] screenShadow = {false};
        boolean[] gameThreadShadow = {false};
        String[] screenDescriptor = {null};
        Set<String> methods = new java.util.LinkedHashSet<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION.equals(descriptor)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof Type target
                                && MINECRAFT.equals(target.getInternalName())) {
                            targetMinecraft[0] = true;
                        }
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (SCREEN_FIELD.equals(name)) screenDescriptor[0] = descriptor;
                if (!SCREEN_FIELD.equals(name) && !"gameThread".equals(name)) return null;
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        if (SHADOW_ANNOTATION.equals(annotationDescriptor)) {
                            if (SCREEN_FIELD.equals(name)) screenShadow[0] = true;
                            if ("gameThread".equals(name)) gameThreadShadow[0] = true;
                        }
                        return null;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                methods.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return new MixinShape(targetMinecraft[0], screenShadow[0], gameThreadShadow[0],
                screenDescriptor[0], Set.copyOf(methods));
    }

    private static byte[] adaptMinecraftMixin(ClassReader reader, String screenDescriptor) {
        ClassWriter writer = new ClassWriter(reader, 0);
        String originalClass = reader.getClassName();
        String guiDescriptor = "L" + GUI_MANAGER + ";";

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (SCREEN_FIELD.equals(name) || "gameThread".equals(name)
                        || "LOGGER".equals(name) || "DEBUG_SCREEN".equals(name)) {
                    return null;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (REMOVED_METHODS.contains(name)) return null;
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (delegate == null || !("beforeLoadingScreenTick".equals(name)
                        || "afterLoadingScreenTick".equals(name))) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        if (opcode == Opcodes.GETFIELD && originalClass.equals(owner)
                                && SCREEN_FIELD.equals(fieldName)
                                && screenDescriptor.equals(fieldDescriptor)) {
                            super.visitFieldInsn(Opcodes.GETFIELD, originalClass, GUI_FIELD, guiDescriptor);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUI_MANAGER,
                                    SCREEN_FIELD, "()" + screenDescriptor, false);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                    }
                };
            }

            @Override
            public void visitEnd() {
                FieldVisitor gui = super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                        GUI_FIELD, guiDescriptor, null, null);
                if (gui != null) {
                    gui.visitAnnotation(SHADOW_ANNOTATION, true).visitEnd();
                    gui.visitAnnotation(FINAL_ANNOTATION, true).visitEnd();
                    gui.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }

    private static byte[] createGuiManagerMixin(ClassReader reader, String screenDescriptor) {
        ClassWriter writer = new ClassWriter(0);
        String originalClass = reader.getClassName();

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                super.visit(version, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        GENERATED_MIXIN, null, "java/lang/Object", null);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                // The generated mixin has its own target annotation, not the old Minecraft target.
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!MOVED_METHODS.contains(name)) return null;
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (delegate == null) return null;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                                               String fieldDescriptor) {
                        if (originalClass.equals(owner)
                                && (SCREEN_FIELD.equals(fieldName)
                                || TICKING_SCREEN_FIELD.equals(fieldName))) {
                            super.visitFieldInsn(opcode, GENERATED_MIXIN, fieldName, fieldDescriptor);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                    }
                };
            }

            @Override
            public void visitEnd() {
                AnnotationVisitor mixin = super.visitAnnotation(MIXIN_ANNOTATION, true);
                mixin.visit("value", Type.getObjectType(GUI_MANAGER));
                mixin.visitEnd();

                FieldVisitor screen = super.visitField(Opcodes.ACC_PRIVATE,
                        SCREEN_FIELD, screenDescriptor, null, null);
                screen.visitAnnotation(SHADOW_ANNOTATION, true).visitEnd();
                screen.visitEnd();

                FieldVisitor tickingScreen = super.visitField(Opcodes.ACC_PRIVATE,
                        TICKING_SCREEN_FIELD, screenDescriptor, null, null);
                tickingScreen.visitAnnotation(UNIQUE_ANNOTATION, true).visitEnd();
                tickingScreen.visitEnd();

                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }

    record Result(byte[] bytes, Map<String, byte[]> generatedClasses, boolean adapted) {}

    private record MixinShape(boolean targetsMinecraft, boolean hasScreenShadow,
                              boolean hasGameThreadShadow, String screenDescriptor, Set<String> methods) {
        boolean hasMethod(String name) {
            return methods.contains(name);
        }
    }
}
