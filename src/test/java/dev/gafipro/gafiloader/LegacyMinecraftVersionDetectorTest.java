package dev.gafipro.gafiloader;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMinecraftVersionDetectorTest {
    @Test
    void recognizesMinecraftDependencyRanges() {
        assertTrue(matches("""
                {"id":"legacy","depends":{"minecraft":"~1.21.11"}}
                """));
        assertTrue(matches("""
                {"id":"legacy","depends":{"minecraft":["1.21.11","~1.21.10"]}}
                """));
    }

    @Test
    void doesNotTreatAnUnboundedLegacyLowerBoundAsLegacyOnly() {
        assertFalse(matches("""
                {"id":"broad","depends":{"minecraft":">=1.21.11"}}
                """));
        assertFalse(matches("""
                {"id":"broad","version":"0.1.0+1.21.11","depends":{"minecraft":">1.21.11"}}
                """));
    }

    @Test
    void recognizesLegacyBytecodeEvenWhenTheDependencyIsAnUnboundedLowerBound() {
        var metadata = JsonParser.parseString("""
                {"id":"legacy-binary","version":"0.1.0+1.21.11","depends":{"minecraft":">=1.21.11"}}
                """).getAsJsonObject();
        assertFalse(LegacyMinecraftVersionDetector.matches(metadata),
                "An unbounded lower bound does not need a Fabric version-gate override.");
        assertTrue(LegacyMinecraftVersionDetector.targetsLegacyBytecode(metadata),
                "The build marker still identifies a 1.21.11 binary that needs namespace conversion.");
    }

    @Test
    void recognizesLegacyBytecodeFromAnExactMinecraftDependency() {
        var metadata = JsonParser.parseString("""
                {"id":"legacy-binary","version":"1.0.0","depends":{"minecraft":"~1.21.11"}}
                """).getAsJsonObject();
        assertTrue(LegacyMinecraftVersionDetector.targetsLegacyBytecode(metadata));
    }

    @Test
    void recognizesLegacyArtifactVersionWhenMinecraftRangeIsIndirect() {
        assertTrue(matches("""
                {"id":"fabric-api","version":"0.141.6+1.21.11","depends":{"fabricloader":">=0.16.0"}}
                """));
    }

    @Test
    void rejectsOtherMinecraftVersionsAndNumericPrefixTraps() {
        assertFalse(matches("""
                {"id":"modern","version":"0.160.0+26.2","depends":{"minecraft":"~26.2"}}
                """));
        assertFalse(matches("""
                {"id":"not-legacy","version":"1.21.110","depends":{"minecraft":"1.21.110"}}
                """));
    }

    private static boolean matches(String metadata) {
        return LegacyMinecraftVersionDetector.matches(
                JsonParser.parseString(metadata).getAsJsonObject());
    }
}
