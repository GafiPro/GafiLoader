package dev.gafipro.gafiloader;

/**
 * Opt-in diagnostics for very frequent transformation details.
 *
 * <p>Normal launches should not print a line for every Mixin class. Enable these
 * details with {@code -Dgafi.loader.debug=true} when diagnosing compatibility.</p>
 */
final class DebugLog {
    private static final String PROPERTY = "gafi.loader.debug";

    private DebugLog() {}

    static void debug(String message) {
        if (Boolean.getBoolean(PROPERTY)) {
            System.out.println(message);
        }
    }
}
