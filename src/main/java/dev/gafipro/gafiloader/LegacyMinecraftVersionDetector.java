package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared definition of a mod targeting Minecraft 1.21.11.
 * Accepts either a Minecraft dependency range or an artifact version such as
 * fabric-api's 0.141.6+1.21.11.
 */
final class LegacyMinecraftVersionDetector {
    private static final Pattern TARGET_LEGACY_VERSION =
            Pattern.compile("(?<![0-9])1\\.21\\.11(?![0-9])");
    private static final Pattern UNBOUNDED_LOWER_BOUND =
            Pattern.compile("^\\s*(>=|>)\\s*([0-9]+(?:\\.[0-9]+)+)\\s*$");
    private static final Pattern LEGACY_ARTIFACT_SUFFIX =
            Pattern.compile("(?:^|\\+)1\\.21\\.11$");
    private static final Pattern TARGET_MINECRAFT_VERSION =
            Pattern.compile("(?<![0-9])26\\.2(?![0-9])");

    private LegacyMinecraftVersionDetector() {}

    static boolean matches(JsonObject metadata) {
        if (metadata == null) return false;

        JsonElement depends = metadata.get("depends");
        if (depends != null && depends.isJsonObject()) {
            JsonElement minecraft = depends.getAsJsonObject().get("minecraft");
            if (minecraft != null) {
                // Fabric accepts any alternative in a dependency array / disjunction.
                // If one alternative admits 26.2, removing the dependency is unnecessary.
                if (containsTargetVersion(minecraft) || containsUnboundedLowerBoundAdmittingTarget(minecraft)) {
                    return false;
                }
                if (containsLegacyVersion(minecraft)) return true;
                if (minecraft.isJsonArray()) {
                    JsonArray ranges = minecraft.getAsJsonArray();
                    for (JsonElement range : ranges) {
                        if (containsLegacyVersion(range)) return true;
                    }
                }
                // An explicit Minecraft dependency is authoritative. Never override a
                // modern (or otherwise non-1.21.11) range merely because the mod's own
                // artifact version happens to contain the text "1.21.11".
                return false;
            }
        }

        // Fabric API artifacts encode their target game version in values such as
        // 0.141.6+1.21.11, so retain this fallback when no Minecraft dependency exists.
        return containsLegacyVersion(metadata.get("version"));
    }

    /**
     * Identifies binaries compiled for the 1.21.11 Intermediary namespace.
     * This is deliberately separate from {@link #matches(JsonObject)}, which decides
     * whether Fabric's declared Minecraft dependency needs an override.
     */
    static boolean targetsLegacyBytecode(JsonObject metadata) {
        if (metadata == null) return false;
        JsonElement depends = metadata.get("depends");
        if (depends != null && depends.isJsonObject()) {
            JsonElement minecraft = depends.getAsJsonObject().get("minecraft");
            if (containsLegacyVersionToken(minecraft)) return true;
            if (minecraft != null && minecraft.isJsonArray()) {
                for (JsonElement range : minecraft.getAsJsonArray()) {
                    if (containsLegacyVersionToken(range)) return true;
                }
            }
        }
        JsonElement version = metadata.get("version");
        return version != null && version.isJsonPrimitive()
                && version.getAsJsonPrimitive().isString()
                && LEGACY_ARTIFACT_SUFFIX.matcher(version.getAsString().trim()).find();
    }

    private static boolean containsTargetVersion(JsonElement value) {
        if (value == null) return false;
        if (value.isJsonArray()) {
            for (JsonElement alternative : value.getAsJsonArray()) {
                if (containsTargetVersion(alternative)) return true;
            }
            return false;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
        return TARGET_MINECRAFT_VERSION.matcher(value.getAsString()).find();
    }

    private static boolean containsUnboundedLowerBoundAdmittingTarget(JsonElement value) {
        if (value == null) return false;
        if (value.isJsonArray()) {
            for (JsonElement alternative : value.getAsJsonArray()) {
                if (containsUnboundedLowerBoundAdmittingTarget(alternative)) return true;
            }
            return false;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;
        String[] alternatives = value.getAsString().split(Pattern.quote("||"));
        for (String alternative : alternatives) {
            Matcher matcher = UNBOUNDED_LOWER_BOUND.matcher(alternative.trim());
            if (!matcher.matches()) continue;
            int comparison = compareNumericVersions(matcher.group(2), "26.2");
            if (comparison < 0 || (comparison == 0 && ">=".equals(matcher.group(1)))) {
                return true;
            }
        }
        return false;
    }

    private static int compareNumericVersions(String left, String right) {
        String[] leftParts = left.split(Pattern.quote("."));
        String[] rightParts = right.split(Pattern.quote("."));
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftPart = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            int rightPart = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static boolean containsLegacyVersionToken(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) return false;
        return TARGET_LEGACY_VERSION.matcher(value.getAsString()).find();
    }

    private static boolean containsLegacyVersion(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) return false;
        String constraint = primitive.getAsString().trim();
        if (UNBOUNDED_LEGACY_LOWER_BOUND.matcher(constraint).matches()) {
            // A lower bound with no upper limit also admits 26.2, so it does not
            // identify a mod that needs the 1.21.11 compatibility path.
            return false;
        }
        return TARGET_LEGACY_VERSION.matcher(constraint).find();
    }
}
