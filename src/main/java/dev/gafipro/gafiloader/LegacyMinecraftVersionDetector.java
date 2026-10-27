package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.regex.Pattern;

/**
 * Shared definition of a mod targeting Minecraft 1.21.11.
 * Accepts either a Minecraft dependency range or an artifact version such as
 * fabric-api's 0.141.6+1.21.11.
 */
final class LegacyMinecraftVersionDetector {
    private static final Pattern TARGET_LEGACY_VERSION =
            Pattern.compile("(?<![0-9])1\\.21\\.11(?![0-9])");

    private LegacyMinecraftVersionDetector() {}

    static boolean matches(JsonObject metadata) {
        if (metadata == null) return false;
        if (containsLegacyVersion(metadata.get("version"))) return true;

        JsonElement depends = metadata.get("depends");
        if (depends == null || !depends.isJsonObject()) return false;
        JsonElement minecraft = depends.getAsJsonObject().get("minecraft");
        if (containsLegacyVersion(minecraft)) return true;
        if (minecraft == null || !minecraft.isJsonArray()) return false;

        JsonArray ranges = minecraft.getAsJsonArray();
        for (JsonElement range : ranges) {
            if (containsLegacyVersion(range)) return true;
        }
        return false;
    }

    private static boolean containsLegacyVersion(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString()
                && TARGET_LEGACY_VERSION.matcher(primitive.getAsString()).find();
    }
}
