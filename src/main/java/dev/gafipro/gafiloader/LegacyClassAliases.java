package dev.gafipro.gafiloader;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reviewed class-name bridges for known moved Minecraft API types.
 *
 * <p>These aliases are enabled only when the configured target JAR proves the legacy class is
 * absent and the replacement class exists. They normalize references in memory and never create
 * or rewrite files in the original game/mod archives.</p>
 */
final class LegacyClassAliases {
    private static final List<ClassMove> MOVES = List.of(
            new ClassMove("net/minecraft/world/level/BlockAndTintGetter",
                    "net/minecraft/client/renderer/block/BlockAndTintGetter"),
            new ClassMove("net/minecraft/client/renderer/block/model/BlockStateModel",
                    "net/minecraft/client/renderer/block/dispatch/BlockStateModel"),
            new ClassMove("net/minecraft/client/renderer/block/model/BlockModelPart",
                    "net/minecraft/client/renderer/block/dispatch/BlockStateModelPart"),
            new ClassMove("net/minecraft/client/renderer/block/model/BlockModelDefinition",
                    "net/minecraft/client/renderer/block/dispatch/BlockStateModelDispatcher")
    );
    private static final Pattern OBJECT_TYPE = Pattern.compile("L([^;]+);");

    private LegacyClassAliases() {}

    static String mapClass(String internalName, TargetApiIndex targetApi) {
        if (internalName == null || targetApi == null || targetApi.hasClass(internalName)) {
            return internalName;
        }
        for (ClassMove move : MOVES) {
            if (!internalName.equals(move.legacyName())
                    && !internalName.startsWith(move.legacyName() + "$")) {
                continue;
            }
            if (targetApi.hasClass(move.legacyName())) {
                continue;
            }
            String candidate = move.targetName()
                    + internalName.substring(move.legacyName().length());
            if (targetApi.hasClass(candidate)) {
                return candidate;
            }
        }
        return internalName;
    }

    static String mapDescriptor(String descriptor, TargetApiIndex targetApi) {
        if (descriptor == null || targetApi == null) {
            return descriptor;
        }
        Matcher matcher = OBJECT_TYPE.matcher(descriptor);
        StringBuffer result = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String originalName = matcher.group(1);
            String mappedName = mapClass(originalName, targetApi);
            if (!mappedName.equals(originalName)) {
                changed = true;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement("L" + mappedName + ";"));
        }
        matcher.appendTail(result);
        return changed ? result.toString() : descriptor;
    }

    private record ClassMove(String legacyName, String targetName) {}
}
