package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
                    if (entry.getName().equalsIgnoreCase("fabric.mod.json")) {
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

        JsonObject root = readExistingConfig(configPath, unreadable);
        JsonObject overrides = root.has("overrides") && root.get("overrides").isJsonObject()
                ? root.getAsJsonObject("overrides") : new JsonObject();

        for (Map.Entry<String, JsonObject> item : legacyMods.entrySet()) {
            String modId = item.getKey();
            JsonElement existingPerMod = overrides.get(modId);
            if (existingPerMod != null && !existingPerMod.isJsonObject()) {
                throw new IllegalArgumentException("Existing override for mod '" + modId
                        + "' must be a JSON object; refusing to replace it.");
            }

            JsonObject perMod = existingPerMod == null
                    ? new JsonObject() : existingPerMod.getAsJsonObject();
            JsonElement existingRemoveDepends = perMod.get("-depends");
            if (existingRemoveDepends != null && !existingRemoveDepends.isJsonObject()) {
                throw new IllegalArgumentException("Existing override for mod '" + modId
                        + "' has a '-depends' value that must be a JSON object; refusing to replace it.");
            }

            JsonObject removeMinecraft = existingRemoveDepends == null
                    ? new JsonObject() : existingRemoveDepends.getAsJsonObject();
            removeMinecraft.addProperty("minecraft", "");
            perMod.add("-depends", removeMinecraft);
            overrides.add(modId, perMod);
        }

        root.addProperty("version", 1);
        root.add("overrides", overrides);
        Path output = configPath.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Keep the previous config recoverable and avoid leaving a truncated file if writing fails.
        if (Files.exists(output) && isValidConfigFile(output)) {
            Path backup = output.resolveSibling(output.getFileName() + ".bak");
            // Preserve the first valid pre-GafiLoader config; never replace it with damaged content.
            if (!Files.exists(backup)) {
                Files.copy(output, backup);
            }
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

    private static JsonObject readExistingConfig(Path configPath, List<String> warnings) throws IOException {
        Path backup = configPath.resolveSibling(configPath.getFileName() + ".bak");
        if (!Files.exists(configPath)) {
            if (Files.isRegularFile(backup)) {
                try {
                    JsonObject recovered = parseConfigFile(backup);
                    warnings.add("Dependency override config was missing; recovered the last known-good copy from " + backup);
                    return recovered;
                } catch (IOException exception) {
                    warnings.add("Dependency override backup is also invalid; rebuilding a fresh config. "
                            + "The backup was left untouched: " + exception.getMessage());
                }
            }
            return new JsonObject();
        }
        if (!Files.isRegularFile(configPath)) {
            throw new IOException("Config path is not a regular file: " + configPath);
        }

        try {
            return parseConfigFile(configPath);
        } catch (IOException exception) {
            Path preserved = configPath.resolveSibling(configPath.getFileName() + ".corrupt");
            try {
                if (!Files.exists(preserved)) {
                    Files.copy(configPath, preserved);
                }
            } catch (IOException preservationException) {
                exception.addSuppressed(preservationException);
            }

            if (Files.isRegularFile(backup)) {
                try {
                    JsonObject recovered = parseConfigFile(backup);
                    warnings.add("Invalid dependency override config was preserved at " + preserved
                            + "; recovered the last known-good copy from " + backup);
                    return recovered;
                } catch (IOException backupException) {
                    exception.addSuppressed(backupException);
                }
            }
            String preservationStatus = Files.isRegularFile(preserved)
                    ? "The damaged original was preserved at " + preserved + "."
                    : "The damaged original could not be copied to " + preserved + ".";
            warnings.add("No valid dependency override config or backup was available; rebuilding a fresh config. "
                    + preservationStatus + " Custom overrides from the damaged file could not be recovered.");
            return new JsonObject();
        }
    }

    private static JsonObject parseConfigFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Config path is not a regular file: " + path);
        }
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            throw new IOException("Config contains invalid JSON: " + path, exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Config must contain a JSON object: " + path);
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("version") || !root.get("version").isJsonPrimitive()
                || !"1".equals(root.get("version").getAsString())) {
            throw new IOException("Config must declare dependency override spec version 1: " + path);
        }
        if (root.has("overrides") && !root.get("overrides").isJsonObject()) {
            throw new IOException("Config 'overrides' value must be a JSON object: " + path);
        }
        return root;
    }

    private static boolean isValidConfigFile(Path path) {
        try {
            parseConfigFile(path);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void collectNestedJar(byte[] bytes, String source, TreeMap<String, JsonObject> found,
                                         List<String> unreadable) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getName().equalsIgnoreCase("fabric.mod.json")) {
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
            if (id.isBlank() || !LegacyMinecraftVersionDetector.matches(metadata)) return;
            found.put(id, metadata);
        } catch (RuntimeException exception) {
            unreadable.add(source + ": invalid fabric.mod.json (" + exception.getMessage() + ")");
        }
    }

    public record Result(String configPath, int jarsScanned, int modIdsOverridden,
                         List<String> overriddenModIds, List<String> warnings) {}
}
