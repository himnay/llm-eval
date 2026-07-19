package com.org.llm.eval;

import java.util.List;

/**
 * One golden-dataset entry. {@code expectedKeywords} are the facts (case-insensitive substrings)
 * a correct answer must mention; accuracy for a question is the fraction of keywords present.
 */
public record GoldenQuestion(String id, String question, List<String> expectedKeywords) {
}
