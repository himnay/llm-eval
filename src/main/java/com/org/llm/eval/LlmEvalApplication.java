package com.org.llm.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * CLI evaluation harness: runs the golden dataset against every configured retrieval system
 * (vector RAG pipeline, vectorless RAG, graph RAG, OKF) and writes a markdown comparison report.
 *
 * <p>Usage: start the target systems, then {@code mvn spring-boot:run}. Systems that are down are
 * reported as unavailable rather than failing the whole evaluation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class LlmEvalApplication {

    public static void main(String[] args) {
        new SpringApplication(LlmEvalApplication.class) {
            {
                setWebApplicationType(WebApplicationType.NONE);
            }
        }.run(args);
    }
}
