package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Generates official Fabric Loader dependency overrides for legacy mods.
 *
 * <p>This only bypasses metadata version gates. It does not make old bytecode or APIs
 * compatible with the target Minecraft version.</p>
 */
public final class LegacyDependencyOverrideGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern TARGET_LEGACY_VERSION = Pattern.compile("(?<![0-9])1\\.21\\.11(?![0-9])");

    public Result generate(Path modsPath, Path configPath) throws IOException {
        if (modsPath == null || !Files.isDirectory(modsPath)) {
            throw new IllegalArgumentException("Mods path must be an existing directory: " + modsPath);
        }
        if (configPath == null) {
            throw new IllegalArgumentException("A fabric_loader_dependencies.json output path is required.");
        }

        List<Path> jars;
        try (var paths = Files.walk(modsPath)) {
            jars = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("No mod JAR files were found in: " + modsPath);
        }

        TreeMap<String, JsonObject> legacyMods = new TreeMap<>();
        List<String> unreadable = new ArrayList<>();
        for (Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    if (entry.getName().equals("fabric.mod.json")) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            collectMetadata(input.readAllBytes(), jarPath + "!/" + entry.getName(), legacyMods, unreadable);
                        }
                    } else if (entry.getName().toLowerCase().endsWith(".jar")) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            collectNestedJar(input.readAllBytes(), jarPath + "!/" + entry.getName(), legacyMods, unreadable);
                        }
                    }
                }
            } catch (IOException exception) {
                unreadable.add(jarPath + ": " + exception.getMessage());
            }
        }

        JsonObject root = readExistingConfig(configPath);
        JsonObject overrides = root.has("overrides") && root.get("overrides").isJsonObject()
                ? root.getAsJsonObject("overrides") : new JsonObject();

        for (Map.Entry<String, JsonObject> item : legacyMods.entrySet()) {
            JsonObject perMod = overrides.has(item.getKey()) && overrides.get(item.getKey()).isJsonObject()
                    ? overrides.getAsJsonObject(item.getKey()) : new JsonObject();
            JsonObject removeMinecraft = perMod.has("-depends") && perMod.get("-depends").isJsonObject()
                    ? perMod.getAsJsonObject("-depends") : new JsonObject();
            removeMinecraft.addProperty("minecraft", "");
            perMod.add("-depends", removeMinecraft);
            overrides.add(item.getKey(), perMod);
        }

        root.addProperty("version", 1);
        root.add("overrides", overrides);
        Path output = configPath.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Keep the previous config recoverable and avoid leaving a truncated file if writing fails.
        if (Files.exists(output)) {
            Path backup = output.resolveSibling(output.getFileName() + ".bak");
            Files.copy(output, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }

        return new Result(output.toString(), jars.size(), legacyMods.size(),
                List.copyOf(legacyMods.keySet()), List.copyOf(unreadable));
    }

    private static JsonObject readExistingConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) return new JsonObject();
        if (!Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("Config path is not a file: " + configPath);
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Existing config must be a JSON object.");
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("overrides") && !root.get("overrides").isJsonObject()) {
                throw new IllegalArgumentException("Existing config 'overrides' value must be a JSON object.");
            }
            return root;
        } catch (com.google.gson.JsonParseException exception) {
            throw new IllegalArgumentException("Existing Fabric dependency override config is invalid JSON: "
                    + configPath, exception);
        }
    }

    private static void collectNestedJar(byte[] bytes, String source, TreeMap<String, JsonObject> found,
                                         List<String> unreadable) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getName().equals("fabric.mod.json")) {
                    collectMetadata(zip.readAllBytes(), source + "!/" + entry.getName(), found, unreadable);
                } else if (entry.getName().toLowerCase().endsWith(".jar")) {
                    collectNestedJar(zip.readAllBytes(), source + "!/" + entry.getName(), found, unreadable);
                }
            }
        } catch (IOException exception) {
            unreadable.add(source + ": " + exception.getMessage());
        }
    }

    private static void collectMetadata(byte[] bytes, String source, TreeMap<String, JsonObject> found,
                                        List<String> unreadable) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return;
            JsonObject metadata = parsed.getAsJsonObject();
            if (!metadata.has("id") || !metadata.get("id").isJsonPrimitive()) return;
            String id = metadata.get("id").getAsString();
            if (id.isBlank() || !(dependsOnLegacyMinecraft(metadata.get("depends"))
                    || declaresLegacyMinecraftVersion(metadata.get("version")))) return;
            found.put(id, metadata);
        } catch (RuntimeException exception) {
            unreadable.add(source + ": invalid fabric.mod.json (" + exception.getMessage() + ")");
        }
    }

    private static boolean dependsOnLegacyMinecraft(JsonElement depends) {
        if (depends == null || !depends.isJsonObject()) return false;
        JsonElement minecraft = depends.getAsJsonObject().get("minecraft");
        if (minecraft == null) return false;
        if (minecraft.isJsonPrimitive()) return containsLegacyVersion(minecraft.getAsString());
        if (minecraft.isJsonArray()) {
            JsonArray ranges = minecraft.getAsJsonArray();
            for (JsonElement range : ranges) {
                if (range.isJsonPrimitive() && containsLegacyVersion(range.getAsString())) return true;
            }
        }
        return false;
    }

    private static boolean declaresLegacyMinecraftVersion(JsonElement version) {
        return version != null && version.isJsonPrimitive()
                && containsLegacyVersion(version.getAsString());
    }

    private static boolean containsLegacyVersion(String range) {
        return range != null && TARGET_LEGACY_VERSION.matcher(range).find();
    }

    public record Result(String configPath, int jarsScanned, int modIdsOverridden,
                         List<String> overriddenModIds, List<String> warnings) {}
}
