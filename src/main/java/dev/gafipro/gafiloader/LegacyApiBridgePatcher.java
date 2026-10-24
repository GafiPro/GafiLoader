package dev.gafipro.gafiloader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Set;

/**
 * Small, reviewed game-side bridges for removed 1.21.11 APIs.
 *
 * <p>The bridge methods are added to Minecraft class bytes during class definition.
 * Neither the game JAR nor any legacy mod JAR is edited on disk. Every bridge is
 * gated on the actual target JAR having the replacement API it delegates to.</p>
 */
final class LegacyApiBridgePatcher {
    private static final int API = Opcodes.ASM9;

    private static final String DISPLAY_SLOT = "net/minecraft/world/scores/DisplaySlot";
    private static final String CHAT_FORMATTING = "net/minecraft/ChatFormatting";
    private static final String DISPLAY_SLOT_BRIDGE =
            "(Lnet/minecraft/ChatFormatting;)Lnet/minecraft/world/scores/DisplaySlot;";

    private static final String READ_ONLY_SCORE_INFO = "net/minecraft/world/scores/ReadOnlyScoreInfo";
    private static final String NUMBER_FORMAT = "net/minecraft/network/chat/numbers/NumberFormat";
    private static final String MUTABLE_COMPONENT = "net/minecraft/network/chat/MutableComponent";
    private static final String COMMON_COMPONENTS = "net/minecraft/network/chat/CommonComponents";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String ABSTRACT_PACK_RESOURCES =
            "net/minecraft/server/packs/AbstractPackResources";
    private static final String METADATA_FROM_STREAM =
            "(Lnet/minecraft/server/packs/metadata/MetadataSectionType;"
                    + "Ljava/io/InputStream;Lnet/minecraft/server/packs/PackLocationInfo;)Ljava/lang/Object;";
    private static final String LOAD_METADATA =
            "(Lnet/minecraft/server/packs/metadata/MetadataSectionType;"
                    + "Ljava/io/InputStream;)Ljava/lang/Object;";
    private static final String SAFE_FORMAT_VALUE =
            "(Lnet/minecraft/world/scores/ReadOnlyScoreInfo;"
                    + "Lnet/minecraft/network/chat/numbers/NumberFormat;)"
                    + "Lnet/minecraft/network/chat/MutableComponent;";
    private static final String FORMAT_VALUE =
            "(Lnet/minecraft/network/chat/numbers/NumberFormat;)"
                    + "Lnet/minecraft/network/chat/MutableComponent;";

    private static final Set<String> TEAM_COLOR_NAMES = Set.of(
            "BLACK", "DARK_BLUE", "DARK_GREEN", "DARK_AQUA",
            "DARK_RED", "DARK_PURPLE", "GOLD", "GRAY",
            "DARK_GRAY", "BLUE", "GREEN", "AQUA",
            "RED", "LIGHT_PURPLE", "YELLOW", "WHITE");

    private LegacyApiBridgePatcher() {}

    /**
     * Returns transformed target-class bytes when a known bridge is needed and the target
     * API has been verified. Otherwise returns {@code null}.
     */
    static byte[] transformClassForRuntime(String className, byte[] original, TargetApiIndex targetApi) {
        if (className == null || original == null || targetApi == null) {
            return null;
        }

        if (DISPLAY_SLOT.equals(className)
                && targetApi.declaredMethodAccess(DISPLAY_SLOT, "teamColorToSlot", DISPLAY_SLOT_BRIDGE) == null
                && supportsDisplaySlotBridge(targetApi)) {
            return addBridgeMethod(original, "teamColorToSlot", DISPLAY_SLOT_BRIDGE,
                    LegacyApiBridgePatcher::writeTeamColorToSlotBridge);
        }

        if (READ_ONLY_SCORE_INFO.equals(className)
                && targetApi.declaredMethodAccess(READ_ONLY_SCORE_INFO, "safeFormatValue", SAFE_FORMAT_VALUE) == null
                && supportsSafeFormatValueBridge(targetApi)) {
            return addBridgeMethod(original, "safeFormatValue", SAFE_FORMAT_VALUE,
                    LegacyApiBridgePatcher::writeSafeFormatValueBridge);
        }

        if (ABSTRACT_PACK_RESOURCES.equals(className)
                && targetApi.hasClass(ABSTRACT_PACK_RESOURCES)
                && targetApi.declaredMethodAccess(ABSTRACT_PACK_RESOURCES,
                        "getMetadataFromStream", METADATA_FROM_STREAM) == null
                && targetApi.declaredMethodAccess(ABSTRACT_PACK_RESOURCES,
                        "loadMetadata", LOAD_METADATA) != null) {
            return addBridgeMethod(original, "getMetadataFromStream", METADATA_FROM_STREAM,
                    LegacyApiBridgePatcher::writeMetadataFromStreamBridge);
        }

        return null;
    }

    private static boolean supportsDisplaySlotBridge(TargetApiIndex targetApi) {
        if (!targetApi.hasClass(DISPLAY_SLOT) || !targetApi.hasClass(CHAT_FORMATTING)) {
            return false;
        }

        // 26.2 no longer exposes ChatFormatting.isColor(). The old method's behavior is
        // equivalent to membership in the 16 color enum constants, so check those constants
        // explicitly in the real target jar and compare Enum.name() at runtime.
        for (String color : TEAM_COLOR_NAMES) {
            Integer targetSlot = targetApi.fieldAccess(DISPLAY_SLOT, "TEAM_" + color,
                    "L" + DISPLAY_SLOT + ";");
            Integer targetFormatting = targetApi.fieldAccess(CHAT_FORMATTING, color,
                    "L" + CHAT_FORMATTING + ";");
            if (targetSlot == null || (targetSlot & Opcodes.ACC_STATIC) == 0
                    || targetFormatting == null || (targetFormatting & Opcodes.ACC_STATIC) == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean supportsSafeFormatValueBridge(TargetApiIndex targetApi) {
        Integer formatAccess = targetApi.methodAccess(READ_ONLY_SCORE_INFO, "formatValue", FORMAT_VALUE);
        Integer emptyAccess = targetApi.fieldAccess(COMMON_COMPONENTS, "EMPTY", "L" + COMPONENT + ";");
        return targetApi.hasClass(READ_ONLY_SCORE_INFO)
                && targetApi.isInterface(READ_ONLY_SCORE_INFO)
                && targetApi.hasClass(COMMON_COMPONENTS)
                && targetApi.hasClass(COMPONENT)
                && targetApi.hasClass(MUTABLE_COMPONENT)
                && formatAccess != null
                // formatValue is legitimately abstract on the ReadOnlyScoreInfo interface.
                // INVOKEINTERFACE dispatches to the concrete implementation at runtime.
                && (formatAccess & Opcodes.ACC_STATIC) == 0
                && emptyAccess != null
                && (emptyAccess & Opcodes.ACC_STATIC) != 0;
    }

    @FunctionalInterface
    private interface BridgeWriter {
        void write(MethodVisitor method, String owner);
    }

    private static byte[] addBridgeMethod(byte[] original, String name, String descriptor,
                                          BridgeWriter bridgeWriter) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Avoid loading game classes before Minecraft's class loader has defined them.
                return "java/lang/Object";
            }
        };
        boolean[] found = new boolean[1];
        reader.accept(new ClassVisitor(API, writer) {
            private String owner;

            @Override
            public void visit(int version, int access, String className, String signature,
                              String superName, String[] interfaces) {
                owner = className;
                super.visit(version, access, className, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor,
                                             String signature, String[] exceptions) {
                if (methodName.equals(name) && methodDescriptor.equals(descriptor)) {
                    found[0] = true;
                }
                return super.visitMethod(access, methodName, methodDescriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (!found[0]) {
                    MethodVisitor method = super.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            name, descriptor, null, null);
                    method.visitCode();
                    bridgeWriter.write(method, owner);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return found[0] ? null : writer.toByteArray();
    }

    private static void writeTeamColorToSlotBridge(MethodVisitor method, String owner) {
        Label nonNull = new Label();
        Label recognizedColor = new Label();

        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(nonNull);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        for (String color : TEAM_COLOR_NAMES) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                    "()Ljava/lang/String;", false);
            method.visitLdcInsn(color);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false);
            method.visitJumpInsn(Opcodes.IFNE, recognizedColor);
        }
        // Formatting values such as BOLD/ITALIC are not team colors.
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(recognizedColor);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("TEAM_");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                "()Ljava/lang/String;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitLdcInsn(Type.getObjectType(owner));
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, owner);
        method.visitInsn(Opcodes.ARETURN);
    }

    private static void writeMetadataFromStreamBridge(MethodVisitor method, String owner) {
        // 26.2 replaced getMetadataFromStream(type, stream, location) with
        // loadMetadata(type, stream). Call the game-owned implementation directly so
        // the transformed class has no dependency on GafiLoader's isolated classloader.
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "loadMetadata", LOAD_METADATA, false);
        method.visitInsn(Opcodes.ARETURN);
    }

    private static void writeSafeFormatValueBridge(MethodVisitor method, String owner) {
        Label nonNull = new Label();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
        method.visitFieldInsn(Opcodes.GETSTATIC, COMMON_COMPONENTS, "EMPTY", "L" + COMPONENT + ";");
        // 26.2 exposes EMPTY as Component, while the removed 1.21.11 method returns MutableComponent.
        // The constant is initialized via Component.empty(), which creates a MutableComponent.
        method.visitTypeInsn(Opcodes.CHECKCAST, MUTABLE_COMPONENT);
        method.visitInsn(Opcodes.ARETURN);

        method.visitLabel(nonNull);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "formatValue", FORMAT_VALUE, true);
        method.visitInsn(Opcodes.ARETURN);
    }
}
