package com.org.llm.eval.dto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configures which systems are evaluated and where the report lands. Each system entry describes
 * one REST question-answering endpoint generically, so a new backend needs configuration only:
 *
 * <ul>
 *   <li>{@code url} — full endpoint URL
 *   <li>{@code question-field} — request-body JSON field carrying the question
 *   <li>{@code answer-field} — response-body JSON field carrying the answer
 *   <li>{@code headers} — optional static headers (API keys etc.)
 *   <li>{@code extra-request-fields} — additional fixed fields merged into every request body
 *       (e.g. Ollama's {@code model} and {@code stream:false})
 * </ul>
 */
@ConfigurationProperties(prefix = "eval")
public record EvalProperties(
        String datasetPath,
        String reportPath,
        int requestTimeoutSeconds,
        boolean judgeEnabled,
        String judgeModel,
        List<SystemUnderTest> systems) {

    public record SystemUnderTest(
            String name,
            String url,
            String questionField,
            String answerField,
            Map<String, String> headers,
            Map<String, Object> extraRequestFields) {
    }
}
