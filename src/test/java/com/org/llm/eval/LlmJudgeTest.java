package com.org.llm.eval;

import com.org.llm.eval.dto.LlmJudge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmJudgeTest {

    @Test
    @DisplayName("The verdict is read from the first JSON object even when more braces follow it")
    void parsesFirstObjectOnly() {
        String raw = """
                {"score": 0.8, "verdict": "mostly correct", "reasoning": "misses the {edge} case"}
                For reference, the config looks like: {"retries": 3}
                """;

        LlmJudge.Verdict verdict = LlmJudge.parse(raw);

        assertThat(verdict.score()).isEqualTo(0.8);
        assertThat(verdict.verdict()).isEqualTo("mostly correct");
        assertThat(verdict.reasoning()).isEqualTo("misses the {edge} case");
    }

    @Test
    @DisplayName("A reply without any JSON object is reported as unparseable, keeping the raw text")
    void noJsonIsUnparseable() {
        LlmJudge.Verdict verdict = LlmJudge.parse("I think the answer is fine.");

        assertThat(verdict.verdict()).isEqualTo("unparseable");
        assertThat(verdict.reasoning()).isEqualTo("I think the answer is fine.");
    }
}
