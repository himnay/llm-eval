package com.org.llm.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Catches configuration/bean wiring breaks (e.g. after a Spring Boot upgrade) without calling any model. */
@SpringBootTest
class LlmEvalApplicationTests {

    @Test
    @DisplayName("Application context starts")
    void contextLoads() {
    }
}
