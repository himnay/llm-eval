package com.org.llm.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs every golden question against every configured system, then writes a markdown report with
 * per-system mean accuracy (keyword recall), latency percentiles and answer length. One system
 * being down never aborts the evaluation — it scores 0 with an "unavailable" note.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunner implements CommandLineRunner {

    record Result(String system, String questionId, double accuracy, long latencyMs,
                  int answerChars, String error) {}

    private final EvalProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    @Override
    public void run(String... args) throws Exception {
        List<GoldenQuestion> dataset = loadDataset();
        log.info("EVAL | {} questions x {} systems", dataset.size(), properties.systems().size());

        List<Result> results = new ArrayList<>();
        for (EvalProperties.SystemUnderTest system : properties.systems()) {
            RestClient client = restClientBuilder.build();
            for (GoldenQuestion question : dataset) {
                results.add(evaluate(client, system, question));
            }
        }
        String report = renderReport(dataset, results);
        Path out = Path.of(properties.reportPath());
        Files.writeString(out, report);
        log.info("EVAL | report written to {}", out.toAbsolutePath());
    }

    private Result evaluate(RestClient client, EvalProperties.SystemUnderTest system, GoldenQuestion q) {
        long start = System.nanoTime();
        try {
            var request = client.post().uri(system.url());
            if (system.headers() != null) {
                system.headers().forEach(request::header);
            }
            String body = request
                    .body(Map.of(system.questionField(), q.question()))
                    .retrieve()
                    .body(String.class);
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;

            JsonNode node = objectMapper.readTree(body).path(system.answerField());
            String answer = node.isMissingNode() ? "" : node.asText("");
            double accuracy = AnswerScorer.score(answer, q.expectedKeywords());
            log.info("EVAL | system={} question={} accuracy={} latencyMs={}",
                    system.name(), q.id(), String.format("%.2f", accuracy), latencyMs);
            return new Result(system.name(), q.id(), accuracy, latencyMs, answer.length(), null);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            log.warn("EVAL | system={} question={} FAILED | {}", system.name(), q.id(), e.getMessage());
            return new Result(system.name(), q.id(), 0, latencyMs, 0, e.getMessage());
        }
    }

    private List<GoldenQuestion> loadDataset() throws Exception {
        try (InputStream in = new DefaultResourceLoader()
                .getResource(properties.datasetPath())
                .getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<>() {});
        }
    }

    private String renderReport(List<GoldenQuestion> dataset, List<Result> results) {
        StringBuilder md = new StringBuilder();
        md.append("# LLM Retrieval Systems — Evaluation Report\n\n")
          .append("Generated: ").append(Instant.now()).append("  \n")
          .append("Questions: ").append(dataset.size()).append("\n\n")
          .append("| System | Mean accuracy | p50 latency (ms) | p95 latency (ms) | Mean answer chars | Errors |\n")
          .append("|---|---|---|---|---|---|\n");

        for (EvalProperties.SystemUnderTest system : properties.systems()) {
            List<Result> rows = results.stream().filter(r -> r.system().equals(system.name())).toList();
            double meanAccuracy = rows.stream().mapToDouble(Result::accuracy).average().orElse(0);
            List<Long> latencies = rows.stream().map(Result::latencyMs).sorted().toList();
            double meanChars = rows.stream().mapToInt(Result::answerChars).average().orElse(0);
            long errors = rows.stream().filter(r -> r.error() != null).count();
            md.append("| ").append(system.name())
              .append(" | ").append(String.format("%.2f", meanAccuracy))
              .append(" | ").append(percentile(latencies, 50))
              .append(" | ").append(percentile(latencies, 95))
              .append(" | ").append(Math.round(meanChars))
              .append(" | ").append(errors)
              .append(" |\n");
        }

        md.append("\n## Per-question accuracy\n\n| Question |");
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
