package com.org.llm.eval.dto;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-judge scoring: asks a separate judge model to rate a system's answer, as a richer
 * alternative to keyword recall ({@link AnswerScorer}). The judge is prompted to respond with a
 * single JSON object so its verdict can be parsed deterministically even though the judge itself
 * is non-deterministic. Fixed grading instructions live in
 * {@code classpath:prompts/judge-system.st}, editable without a code change; only the
 * per-question parts are assembled here.
 */
public class LlmJudge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    public record Verdict(Double score, String verdict, String reasoning) {
    }

    private static String loadSystemPrompt() {
        try {
            byte[] bytes = FileCopyUtils.copyToByteArray(new ClassPathResource("prompts/judge-system.st").getInputStream());
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load prompts/judge-system.st", e);
        }
    }

    public static String buildPrompt(String question, String answer, String referenceAnswer, List<String> expectedKeywords) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT).append("\n\n");
        sb.append("Question: ").append(question).append('\n');
        sb.append("Answer to grade: ").append(answer == null ? "" : answer).append('\n');
        if (referenceAnswer != null && !referenceAnswer.isBlank()) {
            sb.append("Reference (ideal) answer: ").append(referenceAnswer).append('\n');
        }
        if (expectedKeywords != null && !expectedKeywords.isEmpty()) {
            sb.append("Key facts expected: ").append(String.join(", ", expectedKeywords)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    public static Verdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Verdict(null, "unparseable", "empty judge response");
        }
        Matcher m = JSON_OBJECT.matcher(raw);
        if (!m.find()) {
            return new Verdict(null, "unparseable", raw.strip());
        }
        try {
            JsonNode node = MAPPER.readTree(m.group());
            Double score = node.path("score").isMissingNode() ? null : node.path("score").asDouble();
            String verdict = node.path("verdict").isMissingNode() ? null : node.path("verdict").asText();
            String reasoning = node.path("reasoning").isMissingNode() ? null : node.path("reasoning").asText();
            return new Verdict(score, verdict, reasoning);
        } catch (Exception e) {
            return new Verdict(null, "unparseable", raw.strip());
        }
    }
}
