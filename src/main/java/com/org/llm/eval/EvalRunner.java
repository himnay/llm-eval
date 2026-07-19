package com.org.llm.eval;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every golden question against every configured local model, then writes a markdown report
 * with per-model mean accuracy (keyword recall), an optional LLM-as-judge score, latency
 * percentiles and answer length. One model being unreachable never aborts the evaluation — it
 * scores 0 with an "unavailable" note.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunner implements CommandLineRunner {

    record Result(String system, String questionId, double accuracy, Double judgeScore,
                  long latencyMs, int answerChars, String error) {
    }

    private final EvalProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        List<GoldenQuestion> dataset = loadDataset();
        log.info("EVAL | {} questions x {} systems", dataset.size(), properties.systems().size());

        JudgeScorer judge = properties.judgeEnabled()
                ? new JudgeScorer(AnthropicOkHttpClient.fromEnv(), judgeModel())
                : null;

        List<Result> results = new ArrayList<>();
        RestClient client = buildClient();
        for (EvalProperties.SystemUnderTest system : properties.systems()) {
            for (GoldenQuestion question : dataset) {
                results.add(evaluate(client, system, question, judge));
            }
        }
        String report = renderReport(dataset, results);
        Path out = Path.of(properties.reportPath());
        Files.writeString(out, report);
        log.info("EVAL | report written to {}", out.toAbsolutePath());
    }

    private String judgeModel() {
        return properties.judgeModel() == null || properties.judgeModel().isBlank()
                ? "claude-opus-4-8" : properties.judgeModel();
    }

    private Result evaluate(RestClient client, EvalProperties.SystemUnderTest system,
                             GoldenQuestion q, JudgeScorer judge) {
        long start = System.nanoTime();
        try {
            var request = client.post().uri(system.url());
            if (system.headers() != null) {
                system.headers().forEach(request::header);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (system.extraRequestFields() != null) {
                body.putAll(system.extraRequestFields());
            }
            body.put(system.questionField(), q.question());

            String responseBody = request.body(body).retrieve().body(String.class);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;

            JsonNode node = objectMapper.readTree(responseBody).path(system.answerField());
            String answer = node.isMissingNode() ? "" : node.asText("");
            double accuracy = AnswerScorer.score(answer, q.expectedKeywords());
            Double judgeScore = null;
            if (judge != null) {
                try {
                    judgeScore = judge.score(q.question(), q.expectedKeywords(), answer);
                } catch (Exception e) {
                    log.warn("EVAL | judge call failed for system={} question={}: {}",
                            system.name(), q.id(), e.getMessage());
                }
            }
            log.info("EVAL | system={} question={} accuracy={} judge={} latencyMs={}",
                    system.name(), q.id(), String.format("%.2f", accuracy), judgeScore, latencyMs);
            return new Result(system.name(), q.id(), accuracy, judgeScore, latencyMs, answer.length(), null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            log.warn("EVAL | system={} question={} FAILED | {}", system.name(), q.id(), e.getMessage());
            return new Result(system.name(), q.id(), 0, null, latencyMs, 0, e.getMessage());
        }
    }

    /**
     * Bounded connect/read timeouts so one hung system can't stall the whole evaluation.
     */
    private RestClient buildClient() {
        int timeout = properties.requestTimeoutSeconds() > 0 ? properties.requestTimeoutSeconds() : 30;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(5))
                        .build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(timeout));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Loads every {@code *.json} golden-question file under {@code datasetPath} (a classpath
     * directory glob, e.g. {@code classpath:golden-dataset/*.json}) and concatenates them into one
     * dataset, so each topic can be curated and reviewed as its own file.
     */
    private List<GoldenQuestion> loadDataset() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(properties.datasetPath());
        List<GoldenQuestion> all = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                List<GoldenQuestion> questions = objectMapper.readValue(in, new TypeReference<List<GoldenQuestion>>() {
                });
                all.addAll(questions);
                log.info("EVAL | loaded {} questions from {}", questions.size(), resource.getFilename());
            }
        }
        return all;
    }

    private String renderReport(List<GoldenQuestion> dataset, List<Result> results) {
        boolean judgeEnabled = properties.judgeEnabled();
        StringBuilder md = new StringBuilder();
        md.append("# Local LLM Evaluation Report\n\n")
                .append("Generated: ").append(Instant.now()).append("  \n")
                .append("Questions: ").append(dataset.size()).append("\n\n")
                .append("| Model | Mean keyword recall |");
        if (judgeEnabled) {
            md.append(" Mean judge score |");
        }
        md.append(" p50 latency (ms) | p95 latency (ms) | Mean answer chars | Errors |\n")
                .append("|---|---|").append(judgeEnabled ? "---|" : "").append("---|---|---|---|\n");

        for (EvalProperties.SystemUnderTest system : properties.systems()) {
            List<Result> rows = results.stream().filter(r -> r.system().equals(system.name())).toList();
            double meanAccuracy = rows.stream().mapToDouble(Result::accuracy).average().orElse(0);
            List<Long> latencies = rows.stream().map(Result::latencyMs).sorted().toList();
            double meanChars = rows.stream().mapToInt(Result::answerChars).average().orElse(0);
            long errors = rows.stream().filter(r -> r.error() != null).count();
            md.append("| ").append(system.name())
                    .append(" | ").append(String.format("%.2f", meanAccuracy)).append(" |");
            if (judgeEnabled) {
                double meanJudge = rows.stream().map(Result::judgeScore).filter(java.util.Objects::nonNull)
                        .mapToDouble(Double::doubleValue).average().orElse(0);
                md.append(" ").append(String.format("%.2f", meanJudge)).append(" |");
            }
            md.append(" ").append(percentile(latencies, 50))
                    .append(" | ").append(percentile(latencies, 95))
                    .append(" | ").append(Math.round(meanChars))
                    .append(" | ").append(errors)
                    .append(" |\n");
        }

        md.append("\n## Per-question keyword recall\n\n| Question |");
        properties.systems().forEach(s -> md.append(' ').append(s.name()).append(" |"));
        md.append("\n|---|").append("---|".repeat(properties.systems().size())).append('\n');
        for (GoldenQuestion q : dataset) {
            md.append("| ").append(q.id()).append(" |");
            for (EvalProperties.SystemUnderTest system : properties.systems()) {
                results.stream()
                        .filter(r -> r.system().equals(system.name()) && r.questionId().equals(q.id()))
                        .findFirst()
                        .ifPresentOrElse(
                                r -> md.append(' ').append(r.error() != null
                                        ? "unavailable" : String.format("%.2f", r.accuracy())).append(" |"),
                                () -> md.append(" - |"));
            }
            md.append('\n');
        }
        return md.toString();
    }

    static long percentile(List<Long> sortedAscending, int pct) {
        if (sortedAscending.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sortedAscending.size()) - 1;
        return sortedAscending.get(Math.clamp(index, 0, sortedAscending.size() - 1));
    }
}
