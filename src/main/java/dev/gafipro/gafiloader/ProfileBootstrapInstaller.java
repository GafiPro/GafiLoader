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

        // Validate all launcher configuration files before writing anything. These can contain
        // login/session state; update them atomically and preserve every unrelated root/profile field.
        List<LauncherProfileUpdate> launcherUpdates = prepareLauncherProfileUpdates(launcherFiles);

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
            FabricSource source = findFabric26Profile(versionsRoot);
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
            atomicMove(temporaryJar, bootstrapPath);
        } finally {
            Files.deleteIfExists(temporaryJar);
        }

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

        String registeredIn = String.join(", ", launcherFiles.stream()
                .map(path -> path.getFileName().toString()).toList());
        String message = "Created/updated the " + PROFILE_NAME + " version and registered it as a new "
                + "Minecraft Launcher installation in " + registeredIn + ". Close and reopen the Launcher "
                + "if it was already running. The source Fabric profile and vanilla profiles were left unchanged.";
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

    private static List<LauncherProfileUpdate> prepareLauncherProfileUpdates(List<Path> launcherFiles)
            throws IOException {
        List<LauncherProfileUpdate> updates = new ArrayList<>();
        String now = Instant.now().toString();
        for (Path path : launcherFiles) {
            JsonObject root = parseObject(Files.readAllBytes(path), "Minecraft Launcher profile file " + path);
            JsonElement profilesElement = root.get("profiles");
            if (profilesElement != null && !profilesElement.isJsonObject()) {
                throw new IOException("The 'profiles' value is not a JSON object in " + path
                        + "; refusing to overwrite the launcher's configuration.");
            }
            JsonObject profiles = profilesElement == null ? new JsonObject() : profilesElement.getAsJsonObject();
            JsonElement existing = profiles.get(PROFILE_ID);
            JsonObject profile;
            if (existing == null) {
                profile = new JsonObject();
                profile.addProperty("created", now);
                profile.addProperty("lastUsed", now);
            } else if (existing.isJsonObject()) {
                profile = existing.getAsJsonObject();
                if (!profile.has("created")) profile.addProperty("created", now);
                if (!profile.has("lastUsed")) profile.addProperty("lastUsed", now);
            } else {
                throw new IOException("The launcher installation entry '" + PROFILE_ID
                        + "' is not a JSON object in " + path + "; refusing to replace it.");
            }
            profile.addProperty("name", PROFILE_NAME);
            profile.addProperty("type", "custom");
            profile.addProperty("lastVersionId", PROFILE_ID);
            profiles.add(PROFILE_ID, profile);
            root.add("profiles", profiles);
            updates.add(new LauncherProfileUpdate(path, root));
        }
        return List.copyOf(updates);
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
                if (!GAME_VERSION.equals(parent) && !(id != null && id.endsWith("-" + GAME_VERSION))) continue;
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

    public record Result(boolean installed, String message, Path profileJson, Path bootstrapJar) {}
    private record FabricSource(Path path, JsonObject profile) {}
    private record LauncherProfileUpdate(Path path, JsonObject updatedRoot) {}
}
