package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Generates Fabric Loader dependency overrides for a deliberately selected set of legacy mod JARs.
 *
 * <p>It does not modify mod JARs. The generated config removes only each selected mod's required
 * "minecraft" version dependency. This relaxes Fabric's version gate; it does not establish that the
 * mod's bytecode or behavior is compatible with the game.</p>
 */
public final class DependencyOverrideGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_VERSION = "version";
    private static final String OVERRIDES = "overrides";
    private static final String REMOVE_DEPENDS = "-depends";

    public OverrideReport generate(List<Path> legacyModJars, Path outputConfig) throws IOException {
        if (legacyModJars == null || legacyModJars.isEmpty()) {
            throw new IllegalArgumentException("At least one legacy Fabric mod JAR is required.");
        }
        if (outputConfig == null) {
            throw new IllegalArgumentException("Dependency override output path is required.");
        }

        List<ModIdentity> mods = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Path jar : legacyModJars) {
            ModIdentity mod = readIdentity(jar);
            if (!seenIds.add(mod.id())) {
                throw new IOException("Duplicate Fabric mod id '" + mod.id()
                        + "' in selected legacy mod JARs (including " + mod.jar() + "). "
                        + "Generate overrides from a set with unique mod IDs.");
            }
            mods.add(mod);
        }
        mods.sort(Comparator.comparing(ModIdentity::id));

        Path output = outputConfig.toAbsolutePath().normalize();
        if (Files.exists(output) && !Files.isRegularFile(output)) {
            throw new IllegalArgumentException("Dependency override output is not a regular file: " + output);
        }

        JsonObject root = readOrCreateConfig(output);
        JsonObject overrides = root.getAsJsonObject(OVERRIDES);
        if (overrides == null) {
            overrides = new JsonObject();
            root.add(OVERRIDES, overrides);
        }

        List<String> added = new ArrayList<>();
        List<String> alreadyConfigured = new ArrayList<>();
        for (ModIdentity mod : mods) {
            JsonElement existingElement = overrides.get(mod.id());
            JsonObject modOverride;
            if (existingElement == null) {
                modOverride = new JsonObject();
                overrides.add(mod.id(), modOverride);
            } else if (existingElement.isJsonObject()) {
                modOverride = existingElement.getAsJsonObject();
            } else {
                throw new IOException("Existing dependency override for mod '" + mod.id()
                        + "' must be a JSON object in " + output);
            }

            JsonElement removeElement = modOverride.get(REMOVE_DEPENDS);
            if (removeElement != null && !removeElement.isJsonObject()) {
                throw new IOException("Existing '-depends' override for mod '" + mod.id()
                        + "' must be a JSON object in " + output);
            }
            if (removeElement != null) {
                JsonObject removeDependencies = removeElement.getAsJsonObject();
                if (removeDependencies.has("minecraft")) {
                    alreadyConfigured.add(mod.id());
                    continue;
                }
                // Adding a new key to an existing removal block is safe and idempotent.
                removeDependencies.addProperty("minecraft", "");
                added.add(mod.id());
                continue;
            }

            // Avoid mixing a removal rule with a user's replacement/augmentation rule for the
            // same dependency map. Failing here is safer than silently changing precedence.
            if (modOverride.has("depends") || modOverride.has("+depends")) {
                throw new IOException("The existing override for mod '" + mod.id()
                        + "' already defines 'depends' or '+depends'. Review and merge its rule manually: "
                        + output);
            }

            JsonObject removeDependencies = new JsonObject();
            removeDependencies.addProperty("minecraft", "");
            modOverride.add(REMOVE_DEPENDS, removeDependencies);
            added.add(mod.id());
        }

        root.addProperty(CONFIG_VERSION, 1);
        writeAtomically(output, GSON.toJson(root) + System.lineSeparator());

        return new OverrideReport(output.toString(), mods.size(), List.copyOf(added),
                List.copyOf(alreadyConfigured), mods.stream().map(ModIdentity::id).toList());
    }

    private static JsonObject readOrCreateConfig(Path file) throws IOException {
        if (!Files.exists(file)) {
            JsonObject root = new JsonObject();
            root.addProperty(CONFIG_VERSION, 1);
            root.add(OVERRIDES, new JsonObject());
            return root;
        }

        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonParseException exception) {
            throw new IOException("Existing dependency override JSON is invalid: " + file, exception);
        }

        if (!parsed.isJsonObject()) {
            throw new IOException("Existing dependency override config must be a JSON object: " + file);
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement version = root.get(CONFIG_VERSION);
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || version.getAsInt() != 1) {
            throw new IOException("Expected dependency override config version 1 in " + file);
        }
        JsonElement overrides = root.get(OVERRIDES);
        if (overrides != null && !overrides.isJsonObject()) {
            throw new IOException("'overrides' must be a JSON object in " + file);
        }
        return root;
    }

    private static ModIdentity readIdentity(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)
                || !path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("Legacy mod must be an existing JAR file: " + path);
        }

        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) {
                throw new IOException("No root fabric.mod.json in " + path
                        + ". Put only legacy Fabric mod JARs in the selected input.");
            }
            try (var input = jar.getInputStream(entry);
                 var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw new IOException("fabric.mod.json must be a JSON object: " + path);
                }
                JsonElement idElement = parsed.getAsJsonObject().get("id");
                if (idElement == null || !idElement.isJsonPrimitive()
                        || !idElement.getAsJsonPrimitive().isString()) {
                    throw new IOException("fabric.mod.json has no string 'id': " + path);
                }
                String id = idElement.getAsString();
                if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
                    throw new IOException("Invalid Fabric mod id '" + id + "' in " + path);
                }
                return new ModIdentity(id, path.toAbsolutePath().normalize().toString());
            } catch (JsonParseException exception) {
                throw new IOException("Invalid fabric.mod.json in " + path, exception);
            }
        }
    }

    private static void writeAtomically(Path output, String content) throws IOException {
        Path parent = output.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "gafi-dependency-overrides-", ".json");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record ModIdentity(String id, String jar) {}

    public record OverrideReport(String outputConfig, int modsSelected, List<String> newlyConfiguredModIds,
                                 List<String> alreadyConfiguredModIds, List<String> allModIds) {
        public OverrideReport {
            newlyConfiguredModIds = List.copyOf(newlyConfiguredModIds);
            alreadyConfiguredModIds = List.copyOf(alreadyConfiguredModIds);
            allModIds = List.copyOf(allModIds);
        }
    }
}
