package com.org.llm.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void percentileOfEmptyListIsZero() {
        assertThat(EvalRunner.percentile(List.of(), 95)).isZero();
    }

    @Test
    void percentileSelectsExpectedRank() {
        List<Long> latencies = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);

        assertThat(EvalRunner.percentile(latencies, 50)).isEqualTo(50L);
        assertThat(EvalRunner.percentile(latencies, 95)).isEqualTo(100L);
        assertThat(EvalRunner.percentile(latencies, 100)).isEqualTo(100L);
    }

    @Test
    void percentileOfSingleElementIsThatElement() {
        assertThat(EvalRunner.percentile(List.of(42L), 50)).isEqualTo(42L);
        assertThat(EvalRunner.percentile(List.of(42L), 95)).isEqualTo(42L);
    }
}
