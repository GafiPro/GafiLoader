package dev.gafipro.gafiloader;

/**
 * Separate-process entry point that forces the packaged agent to transform and define
 * classes from Mojang's official Minecraft 26.2 client JAR.
 */
public final class RealTargetAgentSmokeMain {
    private RealTargetAgentSmokeMain() {}

    public static void main(String[] args) throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        // Do not reflect over all declared methods: doing so resolves unrelated external method
        // signature dependencies (for example DataFixerUpper's Keyable), which are deliberately
        // outside this bytecode-only smoke test's classpath. Class.forName still makes the JVM
        // define and verify each class after the agent has transformed its bytecode.
        Class.forName("net.minecraft.world.scores.DisplaySlot", false, loader);
        Class.forName("net.minecraft.world.scores.ReadOnlyScoreInfo", false, loader);

        System.out.println("REAL_TARGET_AGENT_BRIDGES_OK");
    }
}
