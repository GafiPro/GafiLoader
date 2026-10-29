package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Creates and registers a dedicated GafiLoader installation based on an installed
 * Fabric profile for Minecraft 26.2. It never edits the source or vanilla profile.
 */
public final class ProfileBootstrapInstaller {
    public static final String PROFILE_ID = "gafiloader-26.2";
    public static final String PROFILE_NAME = "GafiLoader 26.2";
    public static final String GAME_VERSION = "26.2";
    public static final String FABRIC_MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    public static final String BOOTSTRAP_MAIN_CLASS = "dev.gafipro.gafiloader.LaunchBootstrap";
    private static final String LIBRARY_GROUP = "dev.gafipro";
    private static final String LIBRARY_ARTIFACT = "gafiloader-bootstrap";
    private static final String FABRIC_LOADER_COORDINATE = "net.fabricmc:fabric-loader:";
    private static final List<String> LAUNCHER_PROFILE_FILES = List.of(
            "launcher_profiles.json",
            "launcher_profiles_microsoft_store.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Result install(Path minecraftDirectory, InputStream bootstrapJar, String version) throws IOException {
        if (minecraftDirectory == null) {
            throw new IllegalArgumentException("Minecraft directory is required.");
        }
        if (bootstrapJar == null) {
            throw new IllegalArgumentException("The embedded GafiLoader bootstrap JAR is missing.");
        }
        if (version == null || version.isBlank() || version.contains("/") || version.contains("\\")) {
            throw new IllegalArgumentException("A valid GafiLoader version is required.");
        }

        Path minecraft = minecraftDirectory.toAbsolutePath().normalize();
        Path versionsRoot = minecraft.resolve("versions");
        Path profileDirectory = versionsRoot.resolve(PROFILE_ID);
        Path profileJson = profileDirectory.resolve(PROFILE_ID + ".json");

        List<Path> launcherFiles = existingLauncherProfileFiles(minecraft);
        if (launcherFiles.isEmpty()) {
            return new Result(false, "Minecraft Launcher profile file was not found. Open the official "
                    + "Minecraft Launcher once, close it completely, then run GafiLoader again. Expected "
                    + "launcher_profiles.json or launcher_profiles_microsoft_store.json in " + minecraft,
                    profileJson, null);
        }

        // Locate the source Fabric installation even if a previous run already created the GafiLoader
        // version JSON. This lets updates repair a missing launcher entry or fill missing profile settings.
        FabricSource source = findFabric26Profile(versionsRoot);
        String sourceVersionId = source == null ? null : stringValue(source.profile().get("id"));
        if (source != null && sourceVersionId == null) {
            sourceVersionId = source.path().getParent().getFileName().toString();
        }

        JsonObject profileRoot;
        boolean createdFromFabric = false;
        byte[] existingProfileBytes = null;
        if (Files.isRegularFile(profileJson)) {
            existingProfileBytes = Files.readAllBytes(profileJson);
            profileRoot = parseObject(existingProfileBytes, "GafiLoader profile " + profileJson);
            if (profileRoot.has("id") && (!profileRoot.get("id").isJsonPrimitive()
                    || !PROFILE_ID.equals(profileRoot.get("id").getAsString()))) {
                return new Result(false, "Refusing to patch a version file whose id is not "
                        + PROFILE_ID + "; left it unchanged: " + profileJson, profileJson, null);
            }
        } else {
            if (source == null) {
                return new Result(false, "Could not create the GafiLoader installation because no installed "
                        + "Fabric profile for Minecraft " + GAME_VERSION + " was found under " + versionsRoot
                        + ". Install Fabric for Minecraft " + GAME_VERSION + " once, close the Launcher, and "
                        + "run GafiLoader again. No files were changed.", profileJson, null);
            }
            profileRoot = source.profile();
            profileRoot.addProperty("id", PROFILE_ID);
            createdFromFabric = true;
        }

        String currentMain = profileRoot.has("mainClass") && profileRoot.get("mainClass").isJsonPrimitive()
                ? profileRoot.get("mainClass").getAsString() : "";
        if (!currentMain.equals(FABRIC_MAIN_CLASS) && !currentMain.equals(BOOTSTRAP_MAIN_CLASS)) {
            return new Result(false, "Profile mainClass is not the expected Fabric Loader entrypoint; "
                    + "left it unchanged (" + currentMain + ").", profileJson, null);
        }
        if (!hasFabricLoaderLibrary(profileRoot)) {
            return new Result(false, "The source profile does not declare the Fabric Loader library; "
                    + "refusing to create a non-working GafiLoader installation.", profileJson, null);
        }
        String parentVersion = stringValue(profileRoot.get("inheritsFrom"));
        if (parentVersion == null && source != null) {
            parentVersion = stringValue(source.profile().get("inheritsFrom"));
            if (GAME_VERSION.equals(parentVersion)) {
                profileRoot.addProperty("inheritsFrom", GAME_VERSION);
            }
        }
        if (!GAME_VERSION.equals(parentVersion)) {
            return new Result(false, "The GafiLoader profile must inherit from Minecraft " + GAME_VERSION
                    + "; found inheritsFrom=" + parentVersion + ". Install/repair the Fabric " + GAME_VERSION
                    + " profile first. No launcher profile was registered.", profileJson, null);
        }

        // Register only after the source/version data has been validated. Preserve existing user settings,
        // filling only missing keys from the matching Fabric installation even on subsequent repairs.
        List<LauncherProfileUpdate> launcherUpdates =
                prepareLauncherProfileUpdates(launcherFiles, sourceVersionId, minecraft);
        assertLauncherProfilesUnchanged(launcherUpdates);

        JsonArray libraries = profileRoot.has("libraries") && profileRoot.get("libraries").isJsonArray()
                ? profileRoot.getAsJsonArray("libraries") : new JsonArray();
        String coordinate = LIBRARY_GROUP + ":" + LIBRARY_ARTIFACT + ":" + version;
        String relativeJarPath = LIBRARY_GROUP.replace('.', '/') + "/" + LIBRARY_ARTIFACT + "/"
                + version + "/" + LIBRARY_ARTIFACT + "-" + version + ".jar";
        Path bootstrapPath = minecraft.resolve("libraries").resolve(relativeJarPath).normalize();
        Path librariesRoot = minecraft.resolve("libraries").toAbsolutePath().normalize();
        if (!bootstrapPath.toAbsolutePath().normalize().startsWith(librariesRoot)) {
            throw new IOException("Refusing to write the bootstrap outside the Minecraft libraries directory.");
        }

        JsonArray updatedLibraries = new JsonArray();
        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) {
                updatedLibraries.add(element);
                continue;
            }
            JsonObject library = element.getAsJsonObject();
            String name = library.has("name") && library.get("name").isJsonPrimitive()
                    ? library.get("name").getAsString() : "";
            if (!name.startsWith(LIBRARY_GROUP + ":" + LIBRARY_ARTIFACT + ":")) {
                updatedLibraries.add(library);
            }
        }
        JsonObject bootstrapLibrary = new JsonObject();
        bootstrapLibrary.addProperty("name", coordinate);
        bootstrapLibrary.addProperty("url", "https://maven.fabricmc.net/");
        updatedLibraries.add(bootstrapLibrary);

        profileRoot.add("libraries", updatedLibraries);
        profileRoot.addProperty("id", PROFILE_ID);
        profileRoot.addProperty("mainClass", BOOTSTRAP_MAIN_CLASS);

        // Create the version JSON and bootstrap JAR before registering the installation. A failure
        // while editing launcher_profiles.json can then be repaired by rerunning setup; all writes
        // are atomic and the vanilla/source Fabric version is never modified.
        Files.createDirectories(bootstrapPath.getParent());
        Path temporaryJar = bootstrapPath.resolveSibling(bootstrapPath.getFileName() + ".tmp");
        try {
            Files.copy(bootstrapJar, temporaryJar, StandardCopyOption.REPLACE_EXISTING);
            verifyBootstrapJar(temporaryJar, version);
            atomicMove(temporaryJar, bootstrapPath);
        } finally {
            Files.deleteIfExists(temporaryJar);
        }

        assertLauncherProfilesUnchanged(launcherUpdates);
        Files.createDirectories(profileDirectory);
        if (!createdFromFabric && existingProfileBytes != null) {
            Path backup = profileJson.resolveSibling(profileJson.getFileName() + ".gafiloader-backup");
            if (!Files.exists(backup)) {
                Files.copy(profileJson, backup);
            }
        }
        writeJsonAtomically(profileJson, profileRoot);

        for (LauncherProfileUpdate update : launcherUpdates) {
            writeJsonAtomically(update.path(), update.updatedRoot());
        }

        CleanupResult cleanup = cleanupOrphanedBootstrapArtifacts(minecraft, version);
        String cleanupMessage = cleanup.warning() == null
                ? " Removed " + cleanup.removedDirectories() + " unreferenced old GafiLoader bootstrap folder(s)."
                : " Warning: old GafiLoader bootstrap cleanup was skipped/partial: " + cleanup.warning();

        String registeredIn = String.join(", ", launcherFiles.stream()
                .map(path -> path.getFileName().toString()).toList());
        String message = "Created/updated the " + PROFILE_NAME + " version and registered it as a new "
                + "Minecraft Launcher installation in " + registeredIn + ". Close and reopen the Launcher "
                + "if it was already running. The source Fabric profile and vanilla profiles were left unchanged."
                + cleanupMessage;
        return new Result(true, message, profileJson, bootstrapPath);
    }

    private static List<Path> existingLauncherProfileFiles(Path minecraft) {
        List<Path> found = new ArrayList<>();
        for (String name : LAUNCHER_PROFILE_FILES) {
            Path path = minecraft.resolve(name);
            if (Files.isRegularFile(path)) found.add(path);
        }
        return List.copyOf(found);
    }

    private static List<LauncherProfileUpdate> prepareLauncherProfileUpdates(List<Path> launcherFiles,
            String sourceVersionId, Path minecraftDirectory) throws IOException {
        List<LauncherProfileUpdate> updates = new ArrayList<>();
        String now = Instant.now().toString();
        for (Path path : launcherFiles) {
            byte[] originalBytes = Files.readAllBytes(path);
            JsonObject root = parseObject(originalBytes, "Minecraft Launcher profile file " + path);
            JsonElement profilesElement = root.get("profiles");
            if (profilesElement != null && !profilesElement.isJsonObject()) {
                throw new IOException("The 'profiles' value is not a JSON object in " + path
                        + "; refusing to overwrite the launcher's configuration.");
            }
            JsonObject profiles = profilesElement == null ? new JsonObject() : profilesElement.getAsJsonObject();
            JsonElement existing = profiles.get(PROFILE_ID);
            JsonObject profile;
            if (existing == null) {
                profile = copySourceInstallationSettings(profiles, sourceVersionId);
                if (profile == null) profile = new JsonObject();
            } else if (existing.isJsonObject()) {
                profile = existing.getAsJsonObject();
            } else {
                throw new IOException("The launcher installation entry '" + PROFILE_ID
                        + "' is not a JSON object in " + path + "; refusing to replace it.");
            }
            // Merge only absent values: preserve any GafiLoader-specific choices made by the user.
            JsonObject sourceSettings = copySourceInstallationSettings(profiles, sourceVersionId);
            if (sourceSettings != null) {
                for (var setting : sourceSettings.entrySet()) {
                    if (!profile.has(setting.getKey())) {
                        profile.add(setting.getKey(), setting.getValue().deepCopy());
                    }
                }
            }
            // If neither this installation nor its source profile has a custom gameDir, reuse an
            // already-existing GafiLoader-specific directory. Keep the launcher data root for
            // versions/libraries; this setting selects only mods, config, saves and logs.
            String configuredGameDirectory = stringValue(profile.get("gameDir"));
            if (configuredGameDirectory == null || configuredGameDirectory.isBlank()) {
                Path dedicatedGameDirectory = existingDedicatedGameDirectory(minecraftDirectory);
                if (dedicatedGameDirectory != null) {
                    profile.addProperty("gameDir", dedicatedGameDirectory.toString());
                }
            }
            if (!profile.has("created")) profile.addProperty("created", now);
            if (!profile.has("lastUsed")) profile.addProperty("lastUsed", now);
            profile.addProperty("name", PROFILE_NAME);
            profile.addProperty("type", "custom");
            profile.addProperty("lastVersionId", PROFILE_ID);
            profiles.add(PROFILE_ID, profile);
            root.add("profiles", profiles);
            updates.add(new LauncherProfileUpdate(path, originalBytes, root));
        }
        return List.copyOf(updates);
    }

    private static Path existingDedicatedGameDirectory(Path minecraftDirectory) {
        Path candidate = minecraftDirectory.resolve("GafiLoader 26.2").toAbsolutePath().normalize();
        return Files.isDirectory(candidate) ? candidate : null;
    }

    private static JsonObject copySourceInstallationSettings(JsonObject profiles, String sourceVersionId) {
        if (sourceVersionId == null || sourceVersionId.isBlank()) return null;
        String[] settingKeys = {
                "gameDir", "javaDir", "javaArgs", "resolution", "logConfig", "logConfigIsXML", "icon"
        };
        for (var entry : profiles.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonObject()) continue;
            JsonObject sourceEntry = value.getAsJsonObject();
            if (!sourceEntry.has("lastVersionId")
                    || !sourceEntry.get("lastVersionId").isJsonPrimitive()
                    || !sourceVersionId.equals(sourceEntry.get("lastVersionId").getAsString())) {
                continue;
            }
            JsonObject copied = new JsonObject();
            for (String key : settingKeys) {
                if (sourceEntry.has(key)) {
                    copied.add(key, sourceEntry.get(key).deepCopy());
                }
            }
            return copied;
        }
        return null;
    }

    private static FabricSource findFabric26Profile(Path versionsRoot) throws IOException {
        if (!Files.isDirectory(versionsRoot)) return null;
        List<Path> jsonFiles = new ArrayList<>();
        try (Stream<Path> directories = Files.list(versionsRoot)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path json = directory.resolve(directory.getFileName().toString() + ".json");
                if (Files.isRegularFile(json)) jsonFiles.add(json);
            }
        }
        jsonFiles.sort(Comparator.comparing(Path::toString));

        List<FabricSource> candidates = new ArrayList<>();
        for (Path json : jsonFiles) {
            if (json.toAbsolutePath().normalize().equals(
                    versionsRoot.resolve(PROFILE_ID).resolve(PROFILE_ID + ".json").toAbsolutePath().normalize())) {
                continue;
            }
            try {
                JsonObject root = parseObject(Files.readAllBytes(json), "version JSON " + json);
                if (!FABRIC_MAIN_CLASS.equals(stringValue(root.get("mainClass")))) continue;
                if (!hasFabricLoaderLibrary(root)) continue;
                String parent = stringValue(root.get("inheritsFrom"));
                String id = stringValue(root.get("id"));
                if (parent != null && !GAME_VERSION.equals(parent)) continue;
                if (parent == null && !(id != null && id.endsWith("-" + GAME_VERSION))) continue;
                candidates.add(new FabricSource(json, root));
            } catch (IOException | RuntimeException ignored) {
                // A corrupt or unrelated version JSON must not prevent discovery of another valid profile.
            }
        }
        return candidates.stream().max(Comparator.comparing(source -> {
            try {
                return Files.getLastModifiedTime(source.path()).toMillis();
            } catch (IOException ignored) {
                return 0L;
            }
        })).orElse(null);
    }

    private static boolean hasFabricLoaderLibrary(JsonObject root) {
        JsonElement librariesElement = root.get("libraries");
        if (librariesElement == null || !librariesElement.isJsonArray()) return false;
        for (JsonElement element : librariesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonElement name = element.getAsJsonObject().get("name");
            if (name != null && name.isJsonPrimitive()
                    && name.getAsString().startsWith(FABRIC_LOADER_COORDINATE)) return true;
        }
        return false;
    }

    private static void assertLauncherProfilesUnchanged(List<LauncherProfileUpdate> updates) throws IOException {
        for (LauncherProfileUpdate update : updates) {
            if (!Files.isRegularFile(update.path())
                    || !java.util.Arrays.equals(update.originalBytes(), Files.readAllBytes(update.path()))) {
                throw new IOException("Minecraft Launcher profile changed while GafiLoader was preparing the installation: "
                        + update.path() + ". Close Minecraft Launcher completely and rerun GafiLoader; "
                        + "the launcher profile JSON was not overwritten.");
            }
        }
    }

    /**
     * Removes only unreferenced, version-shaped folders under GafiLoader's own bootstrap artifact
     * path. It never traverses symlinks and never deletes a library still referenced by any version
     * JSON or by the saved GafiLoader version backup.
     */
    private static CleanupResult cleanupOrphanedBootstrapArtifacts(Path minecraft, String currentVersion) {
        Path libraryRoot = minecraft.resolve("libraries/dev/gafipro/gafiloader-bootstrap").normalize();
        if (!Files.isDirectory(libraryRoot) || Files.isSymbolicLink(libraryRoot)) {
            return new CleanupResult(0, null);
        }

        try {
            java.util.Set<String> referencedVersions = collectReferencedBootstrapVersions(minecraft);
            referencedVersions.add(currentVersion);
            int removed = 0;
            try (Stream<Path> children = Files.list(libraryRoot)) {
                for (Path directory : children.toList()) {
                    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) continue;
                    String version = directory.getFileName().toString();
                    if (!version.matches("[0-9]+(?:\\.[A-Za-z0-9_-]+){2,}")
                            || referencedVersions.contains(version)) continue;

                    String expectedJar = "gafiloader-bootstrap-" + version + ".jar";
                    String expectedTemp = expectedJar + ".tmp";
                    List<Path> contents;
                    try (Stream<Path> entries = Files.list(directory)) {
                        contents = entries.toList();
                    }
                    if (contents.isEmpty() || contents.stream().anyMatch(path ->
                            Files.isSymbolicLink(path)
                                    || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                    || !(path.getFileName().toString().equals(expectedJar)
                                    || path.getFileName().toString().equals(expectedTemp)))) {
                        continue;
                    }
                    for (Path content : contents) Files.deleteIfExists(content);
                    Files.deleteIfExists(directory);
                    removed++;
                }
            }
            return new CleanupResult(removed, null);
        } catch (IOException exception) {
            return new CleanupResult(0, exception.getMessage());
        }
    }

    private static java.util.Set<String> collectReferencedBootstrapVersions(Path minecraft) throws IOException {
        java.util.Set<String> referenced = new java.util.HashSet<>();
        Path versionsRoot = minecraft.resolve("versions");
        if (!Files.isDirectory(versionsRoot)) return referenced;
        String coordinatePrefix = LIBRARY_GROUP + ":" + LIBRARY_ARTIFACT + ":";
        try (Stream<Path> directories = Files.list(versionsRoot)) {
            for (Path directory : directories.toList()) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) continue;
                try (Stream<Path> entries = Files.list(directory)) {
                    for (Path file : entries.toList()) {
                        String name = file.getFileName().toString();
                        if (Files.isSymbolicLink(file)
                                || !(name.endsWith(".json") || name.endsWith(".json.gafiloader-backup"))
                                || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                        JsonObject root;
                        try {
                            root = parseObject(Files.readAllBytes(file), "version JSON " + file);
                        } catch (IOException exception) {
                            // If a profile/backup cannot be inspected, avoid deleting a library that
                            // the unreadable file may still reference.
                            throw new IOException("could not safely inspect " + file + ": " + exception.getMessage(),
                                    exception);
                        }
                        JsonElement librariesElement = root.get("libraries");
                        if (librariesElement == null || !librariesElement.isJsonArray()) continue;
                        for (JsonElement library : librariesElement.getAsJsonArray()) {
                            if (!library.isJsonObject()) continue;
                            String libraryName = stringValue(library.getAsJsonObject().get("name"));
                            if (libraryName != null && libraryName.startsWith(coordinatePrefix)) {
                                String referencedVersion = libraryName.substring(coordinatePrefix.length());
                                if (!referencedVersion.isBlank()) referenced.add(referencedVersion);
                            }
                        }
                    }
                }
            }
        }
        return referenced;
    }

    private static JsonObject parseObject(byte[] bytes, String description) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException(description + " must contain a JSON object.");
            }
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException exception) {
            throw new IOException(description + " contains invalid JSON.", exception);
        }
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static void writeJsonAtomically(Path path, JsonObject root) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("File path has no parent directory: " + path);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            atomicMove(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicMove(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyBootstrapJar(Path path, String expectedVersion) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new IOException("Embedded GafiLoader bootstrap has no manifest.");
            }
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            if (!BOOTSTRAP_MAIN_CLASS.equals(mainClass)) {
                throw new IOException("Bootstrap manifest Main-Class was " + mainClass
                        + "; expected " + BOOTSTRAP_MAIN_CLASS + ".");
            }
            String embeddedVersion = manifest.getMainAttributes().getValue("Implementation-Version");
            if (!expectedVersion.equals(embeddedVersion)) {
                throw new IOException("Bootstrap version mismatch: embedded=" + embeddedVersion
                        + ", expected=" + expectedVersion + ".");
            }

            String[] requiredEntries = {
                    "dev/gafipro/gafiloader/LaunchBootstrap.class",
                    "dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator.class",
                    "dev/gafipro/gafiloader/LegacyDependencyOverrideGenerator$Result.class",
                    "dev/gafipro/gafiloader/LegacyMinecraftVersionDetector.class",
                    "com/google/gson/Gson.class",
                    "com/google/gson/GsonBuilder.class",
                    "com/google/gson/JsonParser.class",
                    "com/google/gson/JsonObject.class",
                    "com/google/gson/JsonArray.class",
                    "com/google/gson/JsonElement.class",
                    "com/google/gson/JsonPrimitive.class"
            };
            for (String entryName : requiredEntries) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("Bootstrap JAR is missing required entry: " + entryName);
                }
                byte[] classFile;
                try (InputStream stream = jar.getInputStream(entry)) {
                    classFile = stream.readAllBytes();
                }
                if (classFile.length < 8
                        || (classFile[0] & 0xFF) != 0xCA
                        || (classFile[1] & 0xFF) != 0xFE
                        || (classFile[2] & 0xFF) != 0xBA
                        || (classFile[3] & 0xFF) != 0xBE) {
                    throw new IOException("Bootstrap JAR entry is not a valid Java class file: " + entryName);
                }
            }
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("Embedded GafiLoader bootstrap is not a valid JAR archive: " + path, exception);
        }
    }

    public record Result(boolean installed, String message, Path profileJson, Path bootstrapJar) {}
    private record FabricSource(Path path, JsonObject profile) {}
    private record LauncherProfileUpdate(Path path, byte[] originalBytes, JsonObject updatedRoot) {}
    private record CleanupResult(int removedDirectories, String warning) {}
}
