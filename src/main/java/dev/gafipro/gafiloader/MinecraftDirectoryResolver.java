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
        return resolve(explicitOverride, appData, null, userHome);
    }

    static Resolution resolve(String explicitOverride, String appData, String localAppData, String userHome) {
        if (explicitOverride != null && !explicitOverride.isBlank()) {
            return new Resolution(Path.of(explicitOverride).toAbsolutePath().normalize(),
                    "explicit override (GAFILOADER_MINECRAFT_DIR or -Dgafiloader.minecraftDir)");
        }

        Path home = userHome == null || userHome.isBlank()
                ? null : Path.of(userHome).toAbsolutePath().normalize();
        Path appDataCandidate = appData == null || appData.isBlank()
                ? home == null ? null : home.resolve("AppData").resolve("Roaming").resolve(".minecraft").normalize()
                : Path.of(appData).resolve(".minecraft").toAbsolutePath().normalize();
        Path localAppDataRoot = localAppData == null || localAppData.isBlank()
                ? home == null ? null : home.resolve("AppData").resolve("Local")
                : Path.of(localAppData).toAbsolutePath().normalize();
        Path microsoftStoreCandidate = localAppDataRoot == null ? null
                : localAppDataRoot.resolve("Packages")
                        .resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe")
                        .resolve("LocalCache").resolve("Local").resolve("minecraft")
                        .toAbsolutePath().normalize();

        List<Path> candidates = new ArrayList<>();
        if (appDataCandidate != null) candidates.add(appDataCandidate);
        if (microsoftStoreCandidate != null) candidates.add(microsoftStoreCandidate);
        if (home != null) candidates.add(home.resolve(".minecraft").normalize());
        if (candidates.isEmpty()) {
            candidates.add(Path.of(System.getProperty("user.home", "."))
                    .resolve(".minecraft").toAbsolutePath().normalize());
        }

        List<Path> unique = new ArrayList<>(new LinkedHashSet<>(candidates));
        List<Path> launcherRoots = unique.stream()
                .filter(MinecraftDirectoryResolver::hasLauncherProfiles).toList();

        // A machine can retain an old Roaming .minecraft folder while the Microsoft Store
        // launcher uses its package-local data root. Prefer the only recognized root that
        // actually contains the Fabric 26.2 source installation, rather than blindly choosing
        // APPDATA just because a stale launcher_profiles.json happens to exist there.
        List<Path> rootsWithFabric = launcherRoots.stream()
                .filter(MinecraftDirectoryResolver::hasFabric262Profile).toList();
        if (rootsWithFabric.size() == 1) {
            Path selected = rootsWithFabric.get(0);
            String source = selected.equals(microsoftStoreCandidate)
                    ? "Microsoft Store package data (LocalAppData/Packages) with Fabric 26.2 profile"
                    : selected.equals(appDataCandidate)
                    ? "APPDATA/.minecraft with Fabric 26.2 profile"
                    : "fallback Minecraft directory with Fabric 26.2 profile";
            return new Resolution(selected, source);
        }

        // If both launcher data roots contain Fabric, the package-local root is the active
        // candidate for the Microsoft Store launcher. Otherwise retain the normal APPDATA
        // preference for the traditional desktop launcher.
        if (rootsWithFabric.size() > 1 && microsoftStoreCandidate != null
                && rootsWithFabric.contains(microsoftStoreCandidate)) {
            return new Resolution(microsoftStoreCandidate,
                    "Microsoft Store package data (LocalAppData/Packages; multiple Fabric roots found)");
        }
        if (rootsWithFabric.size() > 1 && appDataCandidate != null
                && rootsWithFabric.contains(appDataCandidate)) {
            return new Resolution(appDataCandidate,
                    "APPDATA/.minecraft (multiple Fabric roots found; desktop default)");
        }
        if (!rootsWithFabric.isEmpty()) {
            return new Resolution(rootsWithFabric.get(0),
                    "recognized launcher root containing Fabric 26.2");
        }

        if (launcherRoots.size() == 1) {
            Path selected = launcherRoots.get(0);
            String source = selected.equals(microsoftStoreCandidate)
                    ? "Microsoft Store package data (LocalAppData/Packages; recognized launcher profile)"
                    : "fallback location with a recognized launcher profile";
            return new Resolution(selected, source);
        }

        if (launcherRoots.size() > 1) {
            if (microsoftStoreCandidate != null && launcherRoots.contains(microsoftStoreCandidate)) {
                return new Resolution(microsoftStoreCandidate,
                        "Microsoft Store package data (LocalAppData/Packages; multiple launcher roots found)");
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
