package dev.gafipro.gafiloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/**
 * Retrieves the immutable 1.21.11 mapping inputs required to translate legacy
 * Intermediary ClassTweaker targets into Mojang's official runtime namespace.
 */
final class LegacyMappingRepository {
    private static final String LEGACY_GAME_VERSION = "1.21.11";
    private static final URI VERSION_MANIFEST =
            URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final URI INTERMEDIARY_MAPPINGS = URI.create(
            "https://maven.fabricmc.net/net/fabricmc/intermediary/1.21.11/intermediary-1.21.11-v2.jar");

    private LegacyMappingRepository() {}

    static MappingFiles ensure(Path gameDirectory) throws IOException {
        Path root = gameDirectory.toAbsolutePath().normalize()
                .resolve(".gafiloader").resolve("mappings").resolve(LEGACY_GAME_VERSION);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to use a symbolic-link legacy mappings cache: " + root);
        }
        if (Files.exists(root) && !Files.isDirectory(root)) {
            throw new IOException("Legacy mappings cache path is not a directory: " + root);
        }
        Files.createDirectories(root);

        Path intermediary = root.resolve("intermediary-1.21.11-v2.jar");
        Path mojang = root.resolve("client-mappings-1.21.11.txt");
        if (validIntermediaryJar(intermediary) && Files.isRegularFile(mojang)
                && Files.size(mojang) > 0) {
            return new MappingFiles(intermediary, mojang);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            JsonObject manifest = getJson(client, VERSION_MANIFEST);
            String versionUrl = findVersionUrl(manifest, LEGACY_GAME_VERSION);
            JsonObject version = getJson(client, URI.create(versionUrl));
            JsonObject clientMappings = version.getAsJsonObject("downloads").getAsJsonObject("client_mappings");
            if (clientMappings == null) {
                throw new IOException("Mojang's " + LEGACY_GAME_VERSION
                        + " metadata does not publish client mappings.");
            }
            String mappingsUrl = requiredString(clientMappings, "url");
            String expectedSha1 = requiredString(clientMappings, "sha1");

            Path intermediaryTemp = Files.createTempFile(root, "intermediary-", ".download");
            Path mojangTemp = Files.createTempFile(root, "mojang-mappings-", ".download");
            try {
                download(client, INTERMEDIARY_MAPPINGS, intermediaryTemp);
                if (!validIntermediaryJar(intermediaryTemp)) {
                    throw new IOException("Downloaded Fabric Intermediary archive is missing "
                            + "mappings/mappings.tiny.");
                }

                download(client, URI.create(mappingsUrl), mojangTemp);
                String actualSha1 = sha1(mojangTemp);
                if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
                    throw new IOException("Mojang mappings SHA-1 verification failed for "
                            + LEGACY_GAME_VERSION + ".");
                }

                Files.move(intermediaryTemp, intermediary, StandardCopyOption.REPLACE_EXISTING);
                Files.move(mojangTemp, mojang, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(intermediaryTemp);
                Files.deleteIfExists(mojangTemp);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrieving 1.21.11 mappings.", exception);
        }

        if (!validIntermediaryJar(intermediary) || !Files.isRegularFile(mojang)) {
            throw new IOException("The 1.21.11 mapping cache could not be validated at " + root);
        }
        return new MappingFiles(intermediary, mojang);
    }

    private static JsonObject getJson(HttpClient client, URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "GafiLoader/1.0 (Minecraft compatibility bootstrap)")
                .GET().build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Mapping metadata request failed with HTTP "
                    + response.statusCode() + ": " + uri);
        }
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                throw new IOException("Mapping metadata is not a JSON object: " + uri);
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Could not parse mapping metadata from " + uri, exception);
        }
    }

    private static String findVersionUrl(JsonObject manifest, String versionId) throws IOException {
        JsonArray versions = manifest.getAsJsonArray("versions");
        if (versions == null) throw new IOException("Mojang version manifest has no versions array.");
        for (JsonElement item : versions) {
            if (!item.isJsonObject()) continue;
            JsonObject version = item.getAsJsonObject();
            if (versionId.equals(stringValue(version.get("id")))) {
                return requiredString(version, "url");
            }
        }
        throw new IOException("Mojang's version manifest does not contain " + versionId + ".");
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        String value = stringValue(object.get(key));
        if (value == null || value.isBlank()) {
            throw new IOException("Required mapping metadata field is missing: " + key);
        }
        return value;
    }

    private static String stringValue(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static void download(HttpClient client, URI uri, Path destination)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "GafiLoader/1.0 (Minecraft compatibility bootstrap)")
                .GET().build();
        HttpResponse<Path> response = client.send(request,
                HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(destination);
            throw new IOException("Mapping download failed with HTTP " + response.statusCode() + ": " + uri);
        }
        if (!Files.isRegularFile(destination) || Files.size(destination) == 0) {
            throw new IOException("Mapping download produced an empty file: " + uri);
        }
    }

    private static boolean validIntermediaryJar(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry("mappings/mappings.tiny") != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is required by the Mojang mappings manifest.", exception);
        }
    }

    record MappingFiles(Path intermediaryJar, Path mojangMappings) {}
}
