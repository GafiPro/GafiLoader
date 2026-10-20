package dev.gafipro.gafiloader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Exports method-name matches suggested by the API comparator as a review-only TSV.
 *
 * <p>The first column is deliberately CANDIDATE, not METHOD_ALIAS, so the output cannot
 * accidentally be consumed as executable patch rules. Even a unique same-descriptor match
 * is not proof that the methods have equivalent behavior.</p>
 */
public final class MethodAliasCandidateWriter {
    public ExportReport write(GameApiComparator.Report apiReport, Path outputFile) throws IOException {
        if (apiReport == null) {
            throw new IllegalArgumentException("An API comparison report is required.");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("Candidate output path is required.");
        }

        List<GameApiComparator.Difference> candidates = apiReport.differences().stream()
                .filter(difference -> difference.kind().equals("METHOD_NAME_CHANGED_CANDIDATE"))
                .sorted(Comparator.comparing(GameApiComparator.Difference::owner)
                        .thenComparing(GameApiComparator.Difference::name)
                        .thenComparing(GameApiComparator.Difference::descriptor))
                .toList();

        List<String> lines = new ArrayList<>();
        lines.add("# Gafi Loader method alias candidates (review only; never auto-applied)");
        lines.add("# Generated from: " + apiReport.legacyGameJar() + " -> " + apiReport.targetGameJar());
        lines.add("# Columns: CANDIDATE<TAB>METHOD<TAB>owner<TAB>legacy-name<TAB>descriptor<TAB>target-name-candidates<TAB>review-status");
        lines.add("# Convert a row to METHOD_ALIAS rules only after verifying the actual 26.2 method behavior.");
        int unique = 0;
        int ambiguous = 0;

        for (GameApiComparator.Difference difference : candidates) {
            List<String> names = difference.candidates().stream().distinct().sorted().toList();
            if (names.isEmpty()) {
                continue;
            }
            boolean single = names.size() == 1;
            if (single) {
                unique++;
            } else {
                ambiguous++;
            }
            String status = single ? "UNIQUE_NAME_MATCH_REVIEW_REQUIRED" : "AMBIGUOUS_REVIEW_REQUIRED";
            lines.add(String.join("\t", "CANDIDATE", "METHOD", difference.owner(),
                    difference.name(), difference.descriptor(), String.join(";", names), status));
        }

        Path output = outputFile.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "gafi-alias-candidates-", ".tsv");
        try {
            Files.writeString(temporary, String.join(System.lineSeparator(), lines)
                    + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new ExportReport(output.toString(), candidates.size(), unique, ambiguous);
    }

    public record ExportReport(String outputFile, int candidates, int uniqueCandidates,
                               int ambiguousCandidates) {}
}
