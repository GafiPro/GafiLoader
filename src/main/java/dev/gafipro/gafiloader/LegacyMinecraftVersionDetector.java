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
    private static final Pattern UNBOUNDED_LEGACY_LOWER_BOUND =
            Pattern.compile("^\\s*(?:>=|>)\\s*1\\.21\\.11\\s*$");
    private static final Pattern LEGACY_ARTIFACT_SUFFIX =
            Pattern.compile("(?:^|\\+)1\\.21\\.11$");
    private static final Pattern NUMERIC_VERSION =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)(?:-[A-Za-z0-9.-]+)?$");

    private LegacyMinecraftVersionDetector() {}

    static boolean matches(JsonObject metadata) {
        if (metadata == null) return false;

        JsonElement depends = metadata.get("depends");
        if (depends != null && depends.isJsonObject()) {
            JsonElement minecraft = depends.getAsJsonObject().get("minecraft");
            if (minecraft != null) {
                // Dependency constraints may express multiple alternatives. Only add
                // an override when none of them admits the exact Minecraft 26.2 target.
                if (constraintAdmitsTarget(minecraft)) return false;
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

    private static boolean constraintAdmitsTarget(JsonElement value) {
        if (value == null) return false;
        if (value.isJsonArray()) {
            for (JsonElement alternative : value.getAsJsonArray()) {
                if (constraintAdmitsTarget(alternative)) return true;
            }
            return false;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return false;

        String[] alternatives = value.getAsString().split(Pattern.quote("||"));
        for (String alternative : alternatives) {
            if (constraintBranchAdmitsTarget(alternative)) return true;
        }
        return false;
    }

    private static boolean constraintBranchAdmitsTarget(String branch) {
        String trimmed = branch.trim();
        if (trimmed.isEmpty()) return false;
        if ("*".equals(trimmed) || "x".equalsIgnoreCase(trimmed)) return true;

        java.util.StringTokenizer tokens = new java.util.StringTokenizer(trimmed.replace(',', ' '));
        while (tokens.hasMoreTokens()) {
            String token = tokens.nextToken();
            String operator = "";
            for (String candidate : new String[] { ">=", "<=", "!=", ">", "<", "=", "~", "^" }) {
                if (token.startsWith(candidate)) {
                    operator = candidate;
                    break;
                }
            }
            String versionText = token.substring(operator.length());
            Matcher matcher = NUMERIC_VERSION.matcher(versionText);
            if (!matcher.matches()) return false;
            String numericVersion = matcher.group(1);
            int comparison = compareNumericVersions("26.2", numericVersion);

            boolean accepted = switch (operator) {
                case ">=" -> comparison >= 0;
                case ">" -> comparison > 0;
                case "<=" -> comparison <= 0;
                case "<" -> comparison < 0;
                case "!=" -> comparison != 0;
                case "~" -> comparison >= 0
                        && compareNumericVersions("26.2", tildeUpperBound(numericVersion)) < 0;
                case "^" -> comparison >= 0
                        && compareNumericVersions("26.2", caretUpperBound(numericVersion)) < 0;
                case "", "=" -> comparison == 0;
                default -> false;
            };
            if (!accepted) return false;
        }
        return true;
    }

    private static String tildeUpperBound(String version) {
        String[] parts = version.split(Pattern.quote("."));
        if (parts.length == 1) return (Integer.parseInt(parts[0]) + 1) + ".0";
        return parts[0] + "." + (Integer.parseInt(parts[1]) + 1);
    }

    private static String caretUpperBound(String version) {
        String[] parts = version.split(Pattern.quote("."));
        int major = Integer.parseInt(parts[0]);
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        if (major > 0 || parts.length == 1) return (major + 1) + ".0";
        if (minor > 0 || parts.length == 2) return "0." + (minor + 1);
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return "0.0." + (patch + 1);
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
