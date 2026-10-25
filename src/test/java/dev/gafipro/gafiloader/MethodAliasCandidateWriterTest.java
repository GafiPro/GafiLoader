package dev.gafipro.gafiloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodAliasCandidateWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsReviewOnlyCandidatesAndDoesNotEmitExecutableRules() throws Exception {
        Path output = temporaryDirectory.resolve("candidates.tsv");
        GameApiComparator.Report report = new GameApiComparator.Report(
                "legacy.jar", "target.jar", "mappings.txt", 12, 34, 12, 15,
                new GameApiComparator.Summary(2, 0, 0, 0, 2, 0),
                List.of(
                        new GameApiComparator.Difference("METHOD_NAME_CHANGED_CANDIDATE",
                                "net/minecraft/Example", "oldCall", "(I)V",
                                List.of("newCall"), "Same descriptor; review semantics."),
                        new GameApiComparator.Difference("METHOD_NAME_CHANGED_CANDIDATE",
                                "net/minecraft/Example", "oldOverload", "()V",
                                List.of("first", "second"), "Ambiguous matches.")
                ));

        MethodAliasCandidateWriter.ExportReport result = new MethodAliasCandidateWriter().write(report, output);
        String text = Files.readString(output);

        assertEquals(2, result.candidates());
        assertEquals(1, result.uniqueCandidates());
        assertEquals(1, result.ambiguousCandidates());
        assertTrue(text.contains("CANDIDATE\tMETHOD\tnet/minecraft/Example\toldCall\t(I)V\tnewCall\tUNIQUE_NAME_MATCH_REVIEW_REQUIRED"));
        assertTrue(text.contains("first;second"));
        assertTrue(text.contains("REVIEW_REQUIRED"));
        assertFalse(text.contains("METHOD_ALIAS\t"));
    }
}
