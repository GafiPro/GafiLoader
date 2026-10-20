package dev.gafipro.gafiloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.objectweb.asm.ClassReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remaps Mixin configuration/refmap JSON and class bytes fetched for Mixin metadata inspection.
 *
 * <p>Only recognized Mixin resources and class-byte reads are considered. Mapping is performed in
 * memory; no resource entry or mod JAR is modified on disk.</p>
 */
final class MixinResourceRemapper {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern INTERNAL_CLASS = Pattern.compile(
            "net/minecraft/class_\\d+(?:\\$[A-Za-z0-9_$]+)*");
    private static final Pattern DOTTED_CLASS = Pattern.compile(
            "net\\.minecraft\\.class_\\d+(?:\\$[A-Za-z0-9_$]+)*");

    private static volatile IntermediaryMappings mappings;
    private static volatile CompatibilityScanner.AliasSet aliases =
            CompatibilityScanner.AliasSet.empty();
    private static volatile TargetApiIndex targetApi;

    private MixinResourceRemapper() {}

    static void configure(IntermediaryMappings intermediaryMappings,
                          CompatibilityScanner.AliasSet bytecodeAliases) {
        configure(intermediaryMappings, bytecodeAliases, null);
    }

    static void configure(IntermediaryMappings intermediaryMappings,
                          CompatibilityScanner.AliasSet bytecodeAliases,
                          TargetApiIndex targetApiIndex) {
        mappings = intermediaryMappings;
        aliases = bytecodeAliases == null
                ? CompatibilityScanner.AliasSet.empty() : bytecodeAliases;
        targetApi = targetApiIndex;
    }

    /**
     * Called from Fabric's MixinServiceKnot.getResourceAsStream method by the runtime hook.
     * The returned stream is self-contained, so Fabric/Mixin can close it as usual.
     */
    public static InputStream transform(String resourceName, InputStream original) {
        IntermediaryMappings currentMappings = mappings;
        boolean classResource = isClassResource(resourceName);
        if (original == null || currentMappings == null
                || (!isMixinResource(resourceName) && !classResource)) {
            return original;
        }

        final byte[] source;
        try {
            source = original.readAllBytes();
        } catch (IOException exception) {
            System.err.println("[Gafi Loader] Could not read Mixin resource " + resourceName
                    + " for in-memory remapping: " + exception.getMessage());
            return original;
        }
        try {
            original.close();
        } catch (IOException exception) {
            // The bytes have been captured; a close failure should not turn the replacement stream
            // into an already-consumed original stream.
            System.err.println("[Gafi Loader] Warning: failed to close original Mixin resource "
                    + resourceName + ": " + exception.getMessage());
        }

        if (classResource) {
            return new ByteArrayInputStream(transformClassBytes(resourceName, source));
        }

        try {
            JsonElement root = JsonParser.parseString(new String(source, StandardCharsets.UTF_8));
            AtomicBoolean changed = new AtomicBoolean(false);
            JsonElement remapped = remapJson(root, currentMappings, aliases, changed);
            if (!changed.get()) {
                return new ByteArrayInputStream(source);
            }

            byte[] output = GSON.toJson(remapped).getBytes(StandardCharsets.UTF_8);
            System.out.println("[Gafi Loader] Remapped Mixin resource in memory: " + resourceName);
            return new ByteArrayInputStream(output);
        } catch (RuntimeException exception) {
            // A Mixin resource that is not valid JSON or contains an unsupported selector should
            // not silently become an empty/broken file. Preserve its exact bytes and report why.
            System.err.println("[Gafi Loader] Could not remap Mixin resource " + resourceName
                    + "; passing its original bytes through unchanged: " + exception.getMessage());
            return new ByteArrayInputStream(source);
        }
    }

    static boolean isMixinResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".refmap.json")
                || (lower.endsWith(".json") && lower.contains("mixin"));
    }

    static boolean isClassResource(String resourceName) {
        if (resourceName == null) {
            return false;
        }
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        return normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".class");
    }

    /**
     * Normalize legacy bytecode when Mixin asks Fabric for class metadata. This is separate from
     * Instrumentation: Mixin can inspect a class before the JVM invokes the Java-agent transformer.
     * Returning the input on failure is intentional; the regular definition-time transformer will
     * still run and report any hard failure through the normal class-loading path.
     */
    public static byte[] transformClassBytes(String requestedClassName, byte[] original) {
        IntermediaryMappings currentMappings = mappings;
        if (original == null || currentMappings == null) {
            return original;
        }

        try {
            ClassReader reader = new ClassReader(original);
            String actualClassName = reader.getClassName();
            if (skipClassResource(actualClassName)) {
                return original;
            }

            byte[] remapped = IntermediaryBytecodeRemapper.remap(
                    original, currentMappings, aliases, targetApi);
            if (remapped == null) {
                return original;
            }
            System.out.println("[Gafi Loader] Normalized legacy bytecode for Mixin inspection: "
                    + requestedClassName + " (" + actualClassName + ")");
            return remapped;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Gafi Loader] Could not normalize class bytes for Mixin inspection: "
                    + requestedClassName + "; original bytes retained: " + exception.getMessage());
            return original;
        }
    }

    private static boolean skipClassResource(String internalName) {
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("com/sun/")
                || internalName.startsWith("net/minecraft/")
                || internalName.startsWith("dev/gafipro/gafiloader/")
                || internalName.startsWith("org/objectweb/asm/");
    }

    private static JsonElement remapJson(JsonElement element, IntermediaryMappings mappings,
                                         CompatibilityScanner.AliasSet aliases,
                                         AtomicBoolean changed) {
        if (element == null || element.isJsonNull()) {
            return element;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString()) {
                return element;
            }
            String oldValue = primitive.getAsString();
            String newValue = remapText(oldValue, mappings, aliases);
            if (!newValue.equals(oldValue)) {
                changed.set(true);
                return new JsonPrimitive(newValue);
            }
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                output.add(remapJson(child, mappings, aliases, changed));
            }
            return output;
        }
        JsonObject output = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String oldKey = entry.getKey();
            String newKey = remapText(oldKey, mappings, aliases);
            if (!newKey.equals(oldKey)) {
                changed.set(true);
            }
            if (output.has(newKey)) {
                throw new IllegalArgumentException("Mixin JSON keys collide after mapping: " + newKey);
            }
            output.add(newKey, remapJson(entry.getValue(), mappings, aliases, changed));
        }
        return output;
    }

    private static String remapText(String input, IntermediaryMappings mappings,
                                    CompatibilityScanner.AliasSet aliases) {
        if (input == null || input.isEmpty()
                || (!input.contains("net/minecraft/") && !input.contains("net.minecraft."))) {
            return input;
        }

        StringBuilder output = new StringBuilder(input.length() + 16);
        int cursor = 0;
        while (cursor < input.length()) {
            if (input.startsWith("Lnet/minecraft/", cursor)) {
                int ownerStart = cursor + 1;
                int ownerEnd = input.indexOf(';', ownerStart);
                if (ownerEnd > ownerStart) {
                    String owner = input.substring(ownerStart, ownerEnd);
                    int memberStart = ownerEnd + 1;
                    int nameEnd = memberStart;
                    while (nameEnd < input.length() && isMemberNameCharacter(input.charAt(nameEnd))) {
                        nameEnd++;
                    }

                    if (nameEnd > memberStart && nameEnd < input.length()
                            && (input.charAt(nameEnd) == '(' || input.charAt(nameEnd) == ':')) {
                        String name = input.substring(memberStart, nameEnd);
                        char separator = input.charAt(nameEnd);
                        int descriptorStart = nameEnd;
                        int descriptorEnd = separator == '('
                                ? methodDescriptorEnd(input, descriptorStart)
                                : fieldDescriptorEnd(input, descriptorStart + 1);
                        if (descriptorEnd > descriptorStart) {
                            String descriptor = separator == ':'
                                    ? input.substring(descriptorStart + 1, descriptorEnd)
                                    : input.substring(descriptorStart, descriptorEnd);
                            CompatibilityScanner.SymbolKind kind = separator == ':'
                                    ? CompatibilityScanner.SymbolKind.FIELD
                                    : CompatibilityScanner.SymbolKind.METHOD;
                            CompatibilityScanner.Reference normalized = mappings.normalize(
                                    new CompatibilityScanner.Reference(kind, owner, name, descriptor));
                            CompatibilityScanner.Reference mapped = aliases.normalize(normalized);
                            output.append('L').append(mapped.owner()).append(';')
                                    .append(mapped.name());
                            if (separator == ':') {
                                output.append(':').append(mapped.descriptor());
                            } else {
                                output.append(mapped.descriptor());
                            }
                            cursor = descriptorEnd;
                            continue;
                        }
                    }

                    output.append('L').append(mapClass(owner, mappings, aliases)).append(';');
                    cursor = ownerEnd + 1;
                    continue;
                }
            }

            Matcher internalMatcher = INTERNAL_CLASS.matcher(input);
            internalMatcher.region(cursor, input.length());
            if (internalMatcher.lookingAt()) {
                String oldClass = internalMatcher.group();
                output.append(mapClass(oldClass, mappings, aliases));
                cursor = internalMatcher.end();
                continue;
            }

            Matcher dottedMatcher = DOTTED_CLASS.matcher(input);
            dottedMatcher.region(cursor, input.length());
            if (dottedMatcher.lookingAt()) {
                String oldClass = dottedMatcher.group().replace('.', '/');
                output.append(mapClass(oldClass, mappings, aliases).replace('/', '.'));
                cursor = dottedMatcher.end();
                continue;
            }

            output.append(input.charAt(cursor));
            cursor++;
        }
        return output.toString();
    }

    private static String mapClass(String internalName, IntermediaryMappings mappings,
                                   CompatibilityScanner.AliasSet aliases) {
        String mappedName = mappings.mapClassName(internalName);
        return aliases.normalize(new CompatibilityScanner.Reference(
                CompatibilityScanner.SymbolKind.CLASS, mappedName, null, null)).owner();
    }

    private static boolean isMemberNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$'
                || value == '<' || value == '>';
    }

    private static int methodDescriptorEnd(String value, int start) {
        if (start >= value.length() || value.charAt(start) != '(') {
            return -1;
        }
        int cursor = start + 1;
        while (cursor < value.length() && value.charAt(cursor) != ')') {
            int next = typeDescriptorEnd(value, cursor, false);
            if (next <= cursor) {
                return -1;
            }
            cursor = next;
        }
        if (cursor >= value.length() || value.charAt(cursor) != ')') {
            return -1;
        }
        return typeDescriptorEnd(value, cursor + 1, true);
    }

    private static int fieldDescriptorEnd(String value, int start) {
        return typeDescriptorEnd(value, start, false);
    }

    private static int typeDescriptorEnd(String value, int start, boolean allowVoid) {
        int cursor = start;
        while (cursor < value.length() && value.charAt(cursor) == '[') {
            cursor++;
        }
        if (cursor >= value.length()) {
            return -1;
        }
        char type = value.charAt(cursor);
        if ("BCDFIJSZ".indexOf(type) >= 0 || (allowVoid && type == 'V')) {
            return cursor + 1;
        }
        if (type == 'L') {
            int end = value.indexOf(';', cursor + 1);
            return end < 0 ? -1 : end + 1;
        }
        return -1;
    }
}
