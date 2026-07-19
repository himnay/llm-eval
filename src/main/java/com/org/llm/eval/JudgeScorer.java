package com.org.llm.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-judge scorer: asks Claude to grade a candidate answer against the golden question's
 * expected facts on a 0.0-1.0 scale. Complements {@link AnswerScorer}'s keyword recall with a
 * finer-grained, semantics-aware judgment that tolerates paraphrase and synonyms — at the cost of
 * one API call per (system, question) pair.
 */
@Slf4j
final class JudgeScorer {

    private static final Pattern SCORE_PATTERN = Pattern.compile("([01](?:\\.\\d+)?)");

    private final AnthropicClient client;
    private final String model;

    JudgeScorer(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    /**
     * Returns a 0.0-1.0 correctness/completeness score, or 0.0 for a blank answer or an
     * unparseable judge reply.
     */
    double score(String question, List<String> expectedKeywords, String answer) {
        if (answer == null || answer.isBlank()) {
            return 0.0;
        }
        String prompt = """
                You are grading a candidate answer for factual correctness and completeness.

                Question: %s
                Facts a correct answer should contain: %s
                Candidate answer: %s

                Score the candidate answer from 0.0 (missing/wrong) to 1.0 (fully correct and \
                complete). Partial credit for partially correct answers. Respond with ONLY the \
                number, nothing else.
                """.formatted(question, String.join(", ", expectedKeywords), answer);

        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(prompt)
                .build();

        Message response = client.messages().create(params);
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .findFirst()
                .orElse("");
        Matcher matcher = SCORE_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Math.clamp(Double.parseDouble(matcher.group(1)), 0.0, 1.0);
            } catch (NumberFormatException ignored) {
                // fall through to the warning below
            }
        }
        log.warn("EVAL | judge returned unparseable score: {}", text);
        return 0.0;
    }
}
