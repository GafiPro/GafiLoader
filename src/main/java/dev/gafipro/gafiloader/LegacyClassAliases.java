package dev.gafipro.gafiloader;

/**
 * Reviewed class-name bridges for known moved Minecraft API types.
 *
 * <p>These aliases are enabled only when the configured target JAR proves the legacy class is
 * absent and the replacement class exists. They normalize references in memory and never create
 * or rewrite files in the original game/mod archives.</p>
 */
final class LegacyClassAliases {
    private static final String LEGACY_BLOCK_AND_TINT_GETTER =
            "net/minecraft/world/level/BlockAndTintGetter";
    private static final String RENDER_BLOCK_AND_TINT_GETTER =
            "net/minecraft/client/renderer/block/BlockAndTintGetter";

    private LegacyClassAliases() {}

    static String mapClass(String internalName, TargetApiIndex targetApi) {
        if (internalName == null || targetApi == null) {
            return internalName;
        }
        if (internalName.equals(LEGACY_BLOCK_AND_TINT_GETTER)
                && !targetApi.hasClass(LEGACY_BLOCK_AND_TINT_GETTER)
                && targetApi.hasClass(RENDER_BLOCK_AND_TINT_GETTER)) {
            return RENDER_BLOCK_AND_TINT_GETTER;
        }
        return internalName;
    }

    static String mapDescriptor(String descriptor, TargetApiIndex targetApi) {
        if (descriptor == null || targetApi == null
                || targetApi.hasClass(LEGACY_BLOCK_AND_TINT_GETTER)
                || !targetApi.hasClass(RENDER_BLOCK_AND_TINT_GETTER)) {
            return descriptor;
        }
        return descriptor.replace("L" + LEGACY_BLOCK_AND_TINT_GETTER + ";",
                "L" + RENDER_BLOCK_AND_TINT_GETTER + ";");
    }
}
