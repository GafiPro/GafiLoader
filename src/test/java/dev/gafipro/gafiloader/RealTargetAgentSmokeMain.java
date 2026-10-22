package dev.gafipro.gafiloader;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Separate-process entry point that verifies the packaged agent injects bridge methods
 * into classes loaded from Mojang's official Minecraft 26.2 client JAR.
 */
public final class RealTargetAgentSmokeMain {
    private RealTargetAgentSmokeMain() {}

    public static void main(String[] args) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        // Keep initialization disabled: this smoke test validates bytecode definition and
        // method linkage without requiring a full Minecraft runtime or graphics libraries.
        Class<?> displaySlot = Class.forName(
                "net.minecraft.world.scores.DisplaySlot", false, loader);
        Class<?> chatFormatting = Class.forName(
                "net.minecraft.ChatFormatting", false, loader);
        Method teamColorToSlot = displaySlot.getDeclaredMethod("teamColorToSlot", chatFormatting);
        requireBridge(teamColorToSlot, "DisplaySlot.teamColorToSlot");

        Class<?> scoreInfo = Class.forName(
                "net.minecraft.world.scores.ReadOnlyScoreInfo", false, loader);
        Class<?> numberFormat = Class.forName(
                "net.minecraft.network.chat.numbers.NumberFormat", false, loader);
        Method safeFormatValue = scoreInfo.getDeclaredMethod(
                "safeFormatValue", scoreInfo, numberFormat);
        requireBridge(safeFormatValue, "ReadOnlyScoreInfo.safeFormatValue");

        System.out.println("REAL_TARGET_AGENT_BRIDGES_OK");
    }

    private static void requireBridge(Method method, String description) {
        int access = method.getModifiers();
        if (!Modifier.isPublic(access) || !Modifier.isStatic(access) || !method.isSynthetic()) {
            throw new AssertionError(description
                    + " must be a public static synthetic method; access=" + access
                    + ", synthetic=" + method.isSynthetic());
        }
    }
}
