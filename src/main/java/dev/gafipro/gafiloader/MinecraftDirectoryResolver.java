package dev.gafipro.gafiloader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves the Minecraft data root before the installer touches launcher files.
 * The normal APPDATA location is preferred, but an explicit override and a small
 * set of well-known fallback locations are supported.
 */
final class MinecraftDirectoryResolver {
    private static final String[] LAUNCHER_PROFILE_FILES = {
            "launcher_profiles.json",
            "launcher_profiles_microsoft_store.json"
    };
    private static final String FABRIC_MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    private MinecraftDirectoryResolver() {}

    static Resolution resolve(String explicitOverride, String appData, String userHome) {
        if (explicitOverride != null && !explicitOverride.isBlank()) {
            return new Resolution(Path.of(explicitOverride).toAbsolutePath().normalize(),
                    "explicit override (GAFILOADER_MINECRAFT_DIR or -Dgafiloader.minecraftDir)");
        }

        List<Path> candidates = new ArrayList<>();
        Path appDataCandidate = appData == null || appData.isBlank()
                ? null : Path.of(appData).resolve(".minecraft").toAbsolutePath().normalize();
        if (appDataCandidate != null) candidates.add(appDataCandidate);

        if (userHome != null && !userHome.isBlank()) {
            Path home = Path.of(userHome).toAbsolutePath().normalize();
            candidates.add(home.resolve("AppData").resolve("Roaming").resolve(".minecraft").normalize());
            candidates.add(home.resolve(".minecraft").normalize());
        }
        if (candidates.isEmpty()) {
            candidates.add(Path.of(System.getProperty("user.home", "."))
                    .resolve(".minecraft").toAbsolutePath().normalize());
        }

        List<Path> unique = new ArrayList<>(new LinkedHashSet<>(candidates));
        // APPDATA is the authoritative Windows default when the launcher files are present.
        if (appDataCandidate != null && hasLauncherProfiles(appDataCandidate)) {
            return new Resolution(appDataCandidate, "APPDATA/.minecraft (recognized launcher profile)");
        }

        List<Path> launcherRoots = unique.stream().filter(MinecraftDirectoryResolver::hasLauncherProfiles).toList();
        if (launcherRoots.size() == 1) {
            return new Resolution(launcherRoots.get(0), "fallback location with a recognized launcher profile");
        }
        if (launcherRoots.size() > 1) {
            List<Path> rootsWithFabric = launcherRoots.stream()
                    .filter(MinecraftDirectoryResolver::hasFabric262Profile).toList();
            if (rootsWithFabric.size() == 1) {
                return new Resolution(rootsWithFabric.get(0),
                        "unique fallback location with both launcher data and a Fabric 26.2 profile");
            }
            Path selected = appDataCandidate != null && launcherRoots.contains(appDataCandidate)
                    ? appDataCandidate : launcherRoots.get(0);
            return new Resolution(selected, "fallback selection among multiple recognized launcher roots");
        }

        Path selected = appDataCandidate != null ? appDataCandidate : unique.get(0);
        return new Resolution(selected, "default location; no existing launcher profile file was found");
    }

    private static boolean hasLauncherProfiles(Path root) {
        for (String name : LAUNCHER_PROFILE_FILES) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                if (parsed.isJsonObject()) {
                    JsonElement profiles = parsed.getAsJsonObject().get("profiles");
                    if (profiles == null || profiles.isJsonObject()) return true;
                }
            } catch (IOException | RuntimeException ignored) {
                // A malformed profile file is not considered a usable launcher root.
            }
        }
        return false;
    }

    private static boolean hasFabric262Profile(Path root) {
        Path versions = root.resolve("versions");
        if (!Files.isDirectory(versions)) return false;
        try (Stream<Path> directories = Files.list(versions)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();
                Path json = directory.resolve(id + ".json");
                if (!Files.isRegularFile(json)) continue;
                try {
                    JsonElement parsed = JsonParser.parseString(Files.readString(json));
                    if (!parsed.isJsonObject()) continue;
                    JsonObject profile = parsed.getAsJsonObject();
                    if (!FABRIC_MAIN_CLASS.equals(stringValue(profile.get("mainClass")))) continue;
                    String parent = stringValue(profile.get("inheritsFrom"));
                    String profileId = stringValue(profile.get("id"));
                    if (parent != null && !"26.2".equals(parent)) continue;
                    if (parent == null && (profileId == null || !profileId.endsWith("-26.2"))) continue;
                    JsonElement libraries = profile.get("libraries");
                    if (libraries == null || !libraries.isJsonArray()) continue;
                    for (JsonElement library : libraries.getAsJsonArray()) {
                        if (!library.isJsonObject()) continue;
                        String name = stringValue(library.getAsJsonObject().get("name"));
                        if (name != null && name.startsWith("net.fabricmc:fabric-loader:")) return true;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // Other damaged versions must not block discovery of a valid Fabric profile.
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    record Resolution(Path path, String source) {}
}
