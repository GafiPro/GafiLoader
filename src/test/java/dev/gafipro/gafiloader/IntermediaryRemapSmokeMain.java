package dev.gafipro.gafiloader;

/**
 * Child-JVM entry point that reflectively calls a class initially compiled to Intermediary names.
 */
public final class IntermediaryRemapSmokeMain {
    private IntermediaryRemapSmokeMain() {}

    public static void main(String[] args) throws Exception {
        Class<?> fooType = Class.forName("net.minecraft.Foo");
        Class<?> barType = Class.forName("net.minecraft.Bar");
        Object foo = fooType.getConstructor().newInstance();
        Object bar = barType.getConstructor().newInstance();
        Class<?> legacyMod = Class.forName("test.LegacyIntermediaryMod");
        legacyMod.getMethod("run", fooType, barType).invoke(null, foo, bar);
        System.out.println("INTERMEDIARY_AGENT_REMAP_OK");
    }
}
