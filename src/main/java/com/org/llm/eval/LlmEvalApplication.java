package com.org.llm.eval;

import com.org.llm.eval.controller.EvalController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * REST API for evaluating configured systems (local Ollama models, RAG pipelines, etc) one
 * question at a time. See {@link EvalController} for the endpoints — the caller drives the golden
 * dataset (in {@code golden-dataset/} at the project root) question-by-question and does its own
 * verification.
 *
 * <p>Usage: {@code mvn spring-boot:run}, then POST questions to {@code /api/ask}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class LlmEvalApplication {

    static void main(String[] args) {
        SpringApplication.run(LlmEvalApplication.class, args);
    }
}
