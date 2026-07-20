package com.org.llm.eval.service;

import com.org.llm.eval.controller.EvalController;
import com.org.llm.eval.dto.AnswerScorer;
import com.org.llm.eval.dto.AskResult;
import com.org.llm.eval.dto.EvalProperties;
import com.org.llm.eval.dto.JudgeResult;
import com.org.llm.eval.dto.LlmJudge;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls one configured system-under-test with one question. No dataset loading or batch looping
 * here — the golden dataset lives in {@code golden-dataset/} at the project root and is driven
 * question-by-question over the REST API in {@link EvalController}, with verification done by
 * whoever (or whatever) is calling the API.
 */
@Slf4j
@Service
public class EvalRunner {

    private final EvalProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public EvalRunner(EvalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int timeout = properties.requestTimeoutSeconds() > 0 ? properties.requestTimeoutSeconds() : 30;
        this.client = buildClient(timeout);
    }

    public EvalProperties.SystemUnderTest findSystem(String name) {
        return properties.systems().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown system: " + name));
    }

    public List<EvalProperties.SystemUnderTest> systems() {
        return properties.systems();
    }

    public EvalProperties.SystemUnderTest findJudge(String name) {
        List<EvalProperties.SystemUnderTest> judges = properties.judges();
        return (judges == null ? List.<EvalProperties.SystemUnderTest>of() : judges).stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown judge: " + name));
    }

    public String defaultJudge() {
        return properties.judgeSystem();
    }

    public AskResult ask(EvalProperties.SystemUnderTest system, String question, List<String> expectedKeywords) {
        long start = System.nanoTime();
        try {
            String answer = callAnswer(system, question);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            Double accuracy = expectedKeywords == null ? null : AnswerScorer.score(answer, expectedKeywords);
            log.info("EVAL | model={} latencyMs={} accuracy={}", system.name(), latencyMs, accuracy);
            return new AskResult(system.name(), answer, accuracy, latencyMs, null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            log.warn("EVAL | system={} FAILED | {}", system.name(), e.getMessage());
            return new AskResult(system.name(), null, null, latencyMs, e.getMessage());
        }
    }

    /**
     * Scores an already-produced answer with a separate judge model ({@link LlmJudge}). The judge
     * is just another configured system (see {@code eval.judges}) — it receives a grading prompt
     * as its "question" and its raw text response is parsed for a JSON verdict.
     */
    public JudgeResult judge(EvalProperties.SystemUnderTest judgeSystem, String question, String answer,
                              String referenceAnswer, List<String> expectedKeywords) {
        long start = System.nanoTime();
        try {
            String prompt = LlmJudge.buildPrompt(question, answer, referenceAnswer, expectedKeywords);
            String raw = callAnswer(judgeSystem, prompt);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            LlmJudge.Verdict verdict = LlmJudge.parse(raw);
            log.info("EVAL | judge={} latencyMs={} score={} verdict={}",
                    judgeSystem.name(), latencyMs, verdict.score(), verdict.verdict());
            return new JudgeResult(judgeSystem.name(), verdict.score(), verdict.verdict(), verdict.reasoning(), latencyMs, null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            log.warn("EVAL | judge={} FAILED | {}", judgeSystem.name(), e.getMessage());
            return new JudgeResult(judgeSystem.name(), null, null, null, latencyMs, e.getMessage());
        }
    }

    private String callAnswer(EvalProperties.SystemUnderTest system, String questionFieldValue) {
        var request = client.post().uri(system.url());
        if (system.headers() != null) {
            system.headers().forEach(request::header);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (system.extraRequestFields() != null) {
            body.putAll(system.extraRequestFields());
        }
        body.put(system.questionField(), questionFieldValue);

        String responseBody = request.body(body).retrieve().body(String.class);
        JsonNode node = objectMapper.readTree(responseBody).path(system.answerField());
        return node.isMissingNode() ? "" : node.asText("");
    }

    /**
     * Evicts the model from Ollama's VRAM/RAM ({@code keep_alive: 0} unloads immediately after this
     * prompt-less call completes) so switching models doesn't pile up multiple local models
     * resident at once. Best-effort: extraRequestFields must carry a "model" key.
     */
    public boolean unload(EvalProperties.SystemUnderTest system) {
        Object model = system.extraRequestFields() == null ? null : system.extraRequestFields().get("model");
        if (model == null) {
            return false;
        }
        try {
            var request = client.post().uri(system.url());
            if (system.headers() != null) {
                system.headers().forEach(request::header);
            }
            request.body(Map.of("model", model, "keep_alive", 0)).retrieve().toBodilessEntity();
            log.info("EVAL | model={} unloaded", system.name());
            return true;
        } catch (Exception e) {
            log.warn("EVAL | model={} unload failed: {}", system.name(), e.getMessage());
            return false;
        }
    }

    /**
     * Bounded connect/read timeouts so one hung system can't stall a request indefinitely.
     */
    private static RestClient buildClient(int timeoutSeconds) {
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }
}
