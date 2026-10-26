package dev.gafipro.gafiloader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugLogTest {
    private String previousDebugSetting;
    private PrintStream previousOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureOutput() {
        previousDebugSetting = System.getProperty("gafi.loader.debug");
        previousOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.clearProperty("gafi.loader.debug");
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(previousOut);
        if (previousDebugSetting == null) {
            System.clearProperty("gafi.loader.debug");
        } else {
            System.setProperty("gafi.loader.debug", previousDebugSetting);
        }
    }

    @Test
    void verboseDiagnosticsAreSilentByDefault() {
        DebugLog.debug("[Gafi Loader] DEBUG should not appear by default");
        assertEquals("", capturedOut.toString(StandardCharsets.UTF_8));
    }

    @Test
    void verboseDiagnosticsCanBeEnabledExplicitly() {
        System.setProperty("gafi.loader.debug", "true");
        DebugLog.debug("[Gafi Loader] DEBUG enabled diagnostic");
        assertEquals("[Gafi Loader] DEBUG enabled diagnostic" + System.lineSeparator(),
                capturedOut.toString(StandardCharsets.UTF_8));
    }
}
