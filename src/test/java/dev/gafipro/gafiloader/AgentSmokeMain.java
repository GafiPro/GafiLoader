package dev.gafipro.gafiloader;

/** Separate-process entry point used to prove the packaged agent transforms a real class load. */
public final class AgentSmokeMain {
    private AgentSmokeMain() {}

    public static void main(String[] args) throws Exception {
        Class<?> target = Class.forName("dev.gafipro.gafiloader.AgentSmokeTarget");
        Object value = target.getMethod("legacyValue").invoke(null);
        if (!Integer.valueOf(42).equals(value)) {
            throw new AssertionError("Expected legacyValue() to return 42, got " + value);
        }
        System.out.println("AGENT_SMOKE_OK=42");
    }
}
