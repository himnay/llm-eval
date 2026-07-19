package com.org.llm.eval;

import java.util.List;
import java.util.Locale;

/**
 * Keyword-recall scoring: fraction of expected keywords present in the answer,
 * case-insensitive substring match. Deliberately simple and deterministic — it needs no LLM and
 * can run in CI. Swap in an LLM-as-judge scorer once relative rankings need finer resolution.
 */
final class AnswerScorer {

    private AnswerScorer() {
    }

    /**
     * Returns keyword recall in [0,1]; 0 for null/blank answers or empty keyword lists.
     */
    static double score(String answer, List<String> expectedKeywords) {
        if (answer == null || answer.isBlank() || expectedKeywords == null || expectedKeywords.isEmpty()) {
            return 0;
        }
        String haystack = answer.toLowerCase(Locale.ROOT);
        long hits = expectedKeywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .filter(k -> haystack.contains(k.toLowerCase(Locale.ROOT)))
                .count();
        return (double) hits / expectedKeywords.size();
    }
}
