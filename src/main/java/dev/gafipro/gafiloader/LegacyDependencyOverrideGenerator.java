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

        if (legacyMods.isEmpty()) {
            return new Result(configPath.toAbsolutePath().normalize().toString(), jars.size(),
                    0, List.of(), List.copyOf(unreadable));
        }

        JsonObject root = readExistingConfig(configPath);
        JsonObject overrides = root.has("overrides") && root.get("overrides").isJsonObject()
                ? root.getAsJsonObject("overrides") : new JsonObject();
        boolean changed = !hasNumericVersionOne(root.get("version"))
                || !root.has("overrides") || !root.get("overrides").isJsonObject();

        for (Map.Entry<String, JsonObject> item : legacyMods.entrySet()) {
            JsonElement existingPerMod = overrides.get(item.getKey());
            if (existingPerMod != null && !existingPerMod.isJsonObject()) {
                throw new IOException("Existing dependency override for mod '" + item.getKey()
                        + "' must be a JSON object; refusing to replace it.");
            }
            JsonObject perMod = existingPerMod == null ? new JsonObject() : existingPerMod.getAsJsonObject();
            JsonElement existingDepends = perMod.get("-depends");
            if (existingDepends != null && !existingDepends.isJsonObject()) {
                throw new IOException("Existing '-depends' override for mod '" + item.getKey()
                        + "' must be a JSON object; refusing to replace it.");
            }
            JsonObject removeMinecraft = existingDepends == null
                    ? new JsonObject() : existingDepends.getAsJsonObject();
            JsonElement existingMinecraft = removeMinecraft.get("minecraft");
            boolean alreadyCorrect = existingMinecraft != null
                    && existingMinecraft.isJsonPrimitive()
                    && existingMinecraft.getAsJsonPrimitive().isString()
                    && existingMinecraft.getAsString().isEmpty();
            if (!alreadyCorrect) {
                removeMinecraft.addProperty("minecraft", "");
                changed = true;
            }
            if (existingDepends == null) changed = true;
            if (existingPerMod == null) changed = true;
            perMod.add("-depends", removeMinecraft);
            overrides.add(item.getKey(), perMod);
        }

        Path output = configPath.toAbsolutePath().normalize();
        if (!changed && Files.isRegularFile(output)) {
            return new Result(output.toString(), jars.size(), legacyMods.size(),
                    List.copyOf(legacyMods.keySet()), List.copyOf(unreadable));
        }

        root.addProperty("version", 1);
        root.add("overrides", overrides);
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

    private static boolean hasNumericVersionOne(JsonElement value) {
        return value != null && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber()
                && value.getAsInt() == 1;
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

        // Only bypass an actual version gate: the declared predicate must allow 1.21.11
        // but reject the target 26.2. Fabric accepts both strings and arrays of alternatives.
        // Checking the complete array avoids overriding a mod that already permits 26.2.
        return matchesAnyMinecraftVersion(minecraft, "1.21.11")
                && !matchesAnyMinecraftVersion(minecraft, "26.2");
    }

    private static boolean matchesAnyMinecraftVersion(JsonElement requirement, String version) {
        if (requirement == null) return false;
        if (requirement.isJsonPrimitive()) {
            return matchesFabricVersionPredicate(requirement.getAsString(), version);
        }
        if (requirement.isJsonArray()) {
            for (JsonElement alternative : requirement.getAsJsonArray()) {
                if (alternative.isJsonPrimitive()
                        && matchesFabricVersionPredicate(alternative.getAsString(), version)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Evaluate the numeric forms of Fabric's extended-SemVer predicates needed for
     * dependency-gate detection: exact versions, comparison conjunctions, tilde/caret,
     * and .x, dot-star, or .X wildcards. Unknown forms fail closed rather than trigger an override.
     */
    private static boolean matchesFabricVersionPredicate(String predicate, String candidate) {
        if (predicate == null || predicate.isBlank()) return false;
        String expression = predicate.trim();
        if (expression.equals("*")) return true;

        if (expression.contains("||")) {
            for (String alternative : expression.split("\\|\\|")) {
                if (matchesFabricVersionPredicate(alternative, candidate)) return true;
            }
            return false;
        }

        if (expression.startsWith("~") || expression.startsWith("^")) {
            char operator = expression.charAt(0);
            String lowerBound = expression.substring(1).trim();
            if (!isNumericVersion(lowerBound)
                    || compareVersions(candidate, lowerBound) < 0) return false;
            int[] parts = numericComponents(lowerBound);
            int[] upper;
            if (operator == '^' || parts.length == 1) {
                upper = new int[]{parts[0] + 1, 0};
            } else {
                upper = new int[]{parts[0], parts[1] + 1, 0};
            }
            return compareVersions(candidate, formatVersion(upper)) < 0;
        }

        // Fabric's .x, .* and .X forms denote a wildcard suffix such as 1.21.x.
        if (expression.matches("[0-9]+(?:\\.[0-9]+)*\\.(?:x|X|\\*)")) {
            String[] wanted = expression.split("\\.");
            String[] actual = candidate.split("\\+")[0].split("[-.]");
            int numericCount = wanted.length - 1;
            if (actual.length < numericCount) return false;
            for (int i = 0; i < numericCount; i++) {
                if (!wanted[i].equals(actual[i])) return false;
            }
            return true;
        }

        String normalized = expression.replace(',', ' ').trim();
        String[] terms = normalized.split("\\s+");
        for (String term : terms) {
            if (term.isBlank()) continue;
            String operator = "=";
            String versionTerm = term;
            for (String possible : new String[]{">=", "<=", ">", "<", "="}) {
                if (term.startsWith(possible)) {
                    operator = possible;
                    versionTerm = term.substring(possible.length());
                    break;
                }
            }
            if (!isNumericVersion(versionTerm)) return false;
            int comparison = compareVersions(candidate, versionTerm);
            boolean matched = switch (operator) {
                case ">=" -> comparison >= 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case "<" -> comparison < 0;
                default -> comparison == 0;
            };
            if (!matched) return false;
        }
        return terms.length > 0;
    }

    private static boolean isNumericVersion(String version) {
        return version != null && version.matches(
                "[0-9]+(?:\\.[0-9]+)*(?:-[A-Za-z0-9.-]+)?(?:\\+[A-Za-z0-9.-]+)?");
    }

    private static int compareVersions(String left, String right) {
        String leftWithoutBuild = left.split("\\+", 2)[0];
        String rightWithoutBuild = right.split("\\+", 2)[0];
        String[] leftPrerelease = leftWithoutBuild.split("-", 2);
        String[] rightPrerelease = rightWithoutBuild.split("-", 2);
        int[] leftParts = numericComponents(leftPrerelease[0]);
        int[] rightParts = numericComponents(rightPrerelease[0]);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int l = i < leftParts.length ? leftParts[i] : 0;
            int r = i < rightParts.length ? rightParts[i] : 0;
            if (l != r) return Integer.compare(l, r);
        }
        boolean leftIsPrerelease = leftPrerelease.length > 1;
        boolean rightIsPrerelease = rightPrerelease.length > 1;
        if (leftIsPrerelease != rightIsPrerelease) {
            return leftIsPrerelease ? -1 : 1;
        }
        if (leftIsPrerelease) {
            return leftPrerelease[1].compareTo(rightPrerelease[1]);
        }
        return 0;
    }

    private static int[] numericComponents(String version) {
        String core = version.split("[-+]", 2)[0];
        String[] tokens = core.split("\\.");
        int[] parts = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            try {
                parts[i] = Integer.parseInt(tokens[i]);
            } catch (NumberFormatException exception) {
                return new int[0];
            }
        }
        return parts;
    }

    private static String formatVersion(int[] parts) {
        StringBuilder version = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) version.append('.');
            version.append(parts[i]);
        }
        return version.toString();
    }

    private static boolean declaresLegacyMinecraftVersion(JsonElement version) {
        // Fabric API artifacts often encode the Minecraft line in their build metadata,
        // e.g. 0.141.5+1.21.11. This is distinct from a Minecraft dependency predicate.
        return version != null && version.isJsonPrimitive()
                && containsLegacyVersion(version.getAsString());
    }

    private static boolean containsLegacyVersion(String versionText) {
        return versionText != null && TARGET_LEGACY_VERSION.matcher(versionText).find();
    }

    public record Result(String configPath, int jarsScanned, int modIdsOverridden,
                         List<String> overriddenModIds, List<String> warnings) {}
}
