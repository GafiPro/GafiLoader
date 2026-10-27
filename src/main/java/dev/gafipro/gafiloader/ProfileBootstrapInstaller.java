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

/**
 * Installs a tiny launch-time bootstrap into the dedicated gafiloader-26.2 version profile.
 * It never changes the vanilla or other Fabric profiles.
 */
public final class ProfileBootstrapInstaller {
    public static final String PROFILE_ID = "gafiloader-26.2";
    public static final String FABRIC_MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    public static final String BOOTSTRAP_MAIN_CLASS = "dev.gafipro.gafiloader.LaunchBootstrap";
    private static final String LIBRARY_GROUP = "dev.gafipro";
    private static final String LIBRARY_ARTIFACT = "gafiloader-bootstrap";
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

        Path profileDirectory = minecraftDirectory.resolve("versions").resolve(PROFILE_ID);
        Path profileJson = profileDirectory.resolve(PROFILE_ID + ".json");
        if (!Files.isRegularFile(profileJson)) {
            return new Result(false, "Minecraft profile not found; expected " + profileJson
                    + ". Create/install the gafiloader-26.2 Fabric profile first.", profileJson, null);
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(profileJson, StandardCharsets.UTF_8));
        } catch (com.google.gson.JsonParseException exception) {
            throw new IOException("GafiLoader profile JSON is invalid: " + profileJson, exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("GafiLoader profile JSON must contain a JSON object: " + profileJson);
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has("id") && !PROFILE_ID.equals(root.get("id").getAsString())) {
            return new Result(false, "Refusing to patch a version file whose id is not " + PROFILE_ID + ".",
                    profileJson, null);
        }

        String currentMain = root.has("mainClass") && root.get("mainClass").isJsonPrimitive()
                ? root.get("mainClass").getAsString() : "";
        if (!currentMain.equals(FABRIC_MAIN_CLASS) && !currentMain.equals(BOOTSTRAP_MAIN_CLASS)) {
            return new Result(false, "Profile mainClass is not the expected Fabric Loader entrypoint; "
                    + "left it unchanged (" + currentMain + ").", profileJson, null);
        }

        JsonArray libraries = root.has("libraries") && root.get("libraries").isJsonArray()
                ? root.getAsJsonArray("libraries") : new JsonArray();
        String coordinate = LIBRARY_GROUP + ":" + LIBRARY_ARTIFACT + ":" + version;
        String relativeJarPath = LIBRARY_GROUP.replace('.', '/') + "/" + LIBRARY_ARTIFACT + "/"
                + version + "/" + LIBRARY_ARTIFACT + "-" + version + ".jar";
        Path bootstrapPath = minecraftDirectory.resolve("libraries").resolve(relativeJarPath).normalize();
        Path librariesRoot = minecraftDirectory.resolve("libraries").toAbsolutePath().normalize();
        if (!bootstrapPath.toAbsolutePath().normalize().startsWith(librariesRoot)) {
            throw new IOException("Refusing to write the bootstrap outside the Minecraft libraries directory.");
        }
        Files.createDirectories(bootstrapPath.getParent());

        Path temporaryJar = bootstrapPath.resolveSibling(bootstrapPath.getFileName() + ".tmp");
        try {
            Files.copy(bootstrapJar, temporaryJar, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporaryJar, bootstrapPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryJar, bootstrapPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryJar);
        }

        Path backup = profileJson.resolveSibling(profileJson.getFileName() + ".gafiloader-backup");
        if (!Files.exists(backup)) {
            Files.copy(profileJson, backup);
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
        // The artifact is installed locally by GafiLoader. The repository URL is a fallback
        // for launchers that need to resolve library metadata; it is not used as the bootstrap source.
        bootstrapLibrary.addProperty("url", "https://maven.fabricmc.net/");
        updatedLibraries.add(bootstrapLibrary);

        root.add("libraries", updatedLibraries);
        root.addProperty("mainClass", BOOTSTRAP_MAIN_CLASS);

        Path temporaryJson = profileJson.resolveSibling(profileJson.getFileName() + ".tmp");
        try {
            Files.writeString(temporaryJson, GSON.toJson(root) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporaryJson, profileJson, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryJson, profileJson, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryJson);
        }

        return new Result(true, "Installed launch-time compatibility bootstrap for " + PROFILE_ID
                + ". The original profile is backed up at " + backup + ".", profileJson, bootstrapPath);
    }

    public record Result(boolean installed, String message, Path profileJson, Path bootstrapJar) {}
}
