package dev.gafipro.gafiloader;

/** Loaded only after the Java agent is installed by the smoke-test JVM. */
public final class AgentSmokeTarget {
    private AgentSmokeTarget() {}

    public static int currentValue() {
        return 42;
    }
}
