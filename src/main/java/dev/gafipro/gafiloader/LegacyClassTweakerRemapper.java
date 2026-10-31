package dev.gafipro.gafiloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Converts the namespace header and every supported vanilla target in a legacy
 * ClassTweaker file. Merely changing the header is unsafe because the body also
 * contains Intermediary class/member names and descriptors.
 */
final class LegacyClassTweakerRemapper {
    private static final String INTERMEDIARY_NAMESPACE = "intermediary";
    private static final String OFFICIAL_NAMESPACE = "official";

    private final Path gameDirectory;
    private final LegacyClassTweakerMappings suppliedMappings;
    private LegacyClassTweakerMappings mappings;

    LegacyClassTweakerRemapper(Path gameDirectory) {
        this(gameDirectory, null);
    }

    LegacyClassTweakerRemapper(Path gameDirectory, LegacyClassTweakerMappings suppliedMappings) {
        this.gameDirectory = gameDirectory;
        this.suppliedMappings = suppliedMappings;
        this.mappings = suppliedMappings;
    }

    Result remap(String text) throws IOException {
        if (text == null || text.isEmpty()) return new Result(text, false);
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0) return new Result(text, false);

        // Be tolerant of a UTF-8 BOM preceded by whitespace. Some tools prepend
        // indentation before the BOM, so checking only charAt(0) is insufficient.
        String firstLine = lines[0];
        int headerStart = 0;
        while (headerStart < firstLine.length()) {
            char character = firstLine.charAt(headerStart);
            if (character == (char) 0xFEFF || Character.isWhitespace(character)) {
                headerStart++;
            } else {
                break;
            }
        }
        String[] header = firstLine.substring(headerStart).trim().split("\\s+");
        if (header.length != 3
                || !(header[0].equals("classTweaker") || header[0].equals("accessWidener"))) {
            return new Result(text, false);
        }
        if (header[2].equals(OFFICIAL_NAMESPACE)) return new Result(text, false);
        if (!header[2].equals(INTERMEDIARY_NAMESPACE)) return new Result(text, false);

        LegacyClassTweakerMappings activeMappings = mappings();
        StringBuilder output = new StringBuilder(text.length() + 32);
        output.append(header[0]).append(' ').append(header[1]).append(' ')
                .append(OFFICIAL_NAMESPACE);
        for (int i = 1; i < lines.length; i++) {
            output.append(System.lineSeparator()).append(remapLine(lines[i], activeMappings, i + 1));
        }
        return new Result(output.toString(), true);
    }

    private LegacyClassTweakerMappings mappings() throws IOException {
        if (mappings != null) return mappings;
        LegacyMappingRepository.MappingFiles files = LegacyMappingRepository.ensure(gameDirectory);
        mappings = LegacyClassTweakerMappings.load(files.intermediaryJar(), files.mojangMappings());
        return mappings;
    }

    private static String remapLine(String line, LegacyClassTweakerMappings mappings, int lineNumber)
            throws IOException {
        int commentOffset = line.indexOf('#');
        String body = commentOffset >= 0 ? line.substring(0, commentOffset) : line;
        String comment = commentOffset >= 0 ? line.substring(commentOffset) : "";
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return line;

        String[] tokens = trimmed.split("\\s+");
        String directive = tokens[0];
        String baseDirective = directive.startsWith("transitive-")
                ? directive.substring("transitive-".length()) : directive;

        try {
            if (baseDirective.equals("accessible") || baseDirective.equals("extendable")
                    || baseDirective.equals("mutable")) {
                if (tokens.length < 3) throw new IOException("Incomplete access directive.");
                String targetType = tokens[1];
                if (targetType.equals("class")) {
                    if (tokens.length != 3) throw new IOException("Class directive has unexpected arguments.");
                    tokens[2] = mappings.mapClass(tokens[2]);
                } else if (targetType.equals("field") || targetType.equals("method")) {
                    if (tokens.length != 5) throw new IOException("Member directive must include owner, name and descriptor.");
                    LegacyClassTweakerMappings.MappedMember member =
                            mappings.mapMember(tokens[2], tokens[3], tokens[4],
                                    targetType.equals("method"));
                    tokens[2] = member.owner();
                    tokens[3] = member.name();
                    tokens[4] = member.descriptor();
                } else {
                    throw new IOException("Unsupported access target type: " + targetType);
                }
            } else if (baseDirective.equals("inject-interface")
                    || baseDirective.equals("extend-enum")) {
                if (tokens.length != 3) throw new IOException("ClassTweaker directive must have two targets.");
                tokens[1] = mappings.mapClass(tokens[1]);
                if (baseDirective.equals("inject-interface")) {
                    tokens[2] = mappings.mapInterfaceName(tokens[2]);
                }
            } else {
                throw new IOException("Unsupported ClassTweaker directive: " + directive);
            }
        } catch (IOException exception) {
            throw new IOException("ClassTweaker line " + lineNumber + ": " + exception.getMessage(), exception);
        }

        return String.join(" ", tokens) + (comment.isEmpty() ? "" : " " + comment);
    }

    record Result(String contents, boolean remapped) {}
}
