package com.org.llm.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerScorerTest {

    @Test
    void fullRecallScoresOne() {
        assertThat(AnswerScorer.score(
                "We use Postgres 18 behind Keycloak.", List.of("postgres", "18", "keycloak")))
                .isEqualTo(1.0);
    }

    @Test
    void partialRecallScoresFraction() {
        assertThat(AnswerScorer.score(
                "We use Postgres.", List.of("postgres", "keycloak"))).isEqualTo(0.5);
    }

    @Test
    void matchIsCaseInsensitive() {
        assertThat(AnswerScorer.score("PROMETHEUS and Grafana", List.of("prometheus", "grafana")))
                .isEqualTo(1.0);
    }

    @Test
    void blankAnswerScoresZero() {
        assertThat(AnswerScorer.score("  ", List.of("postgres"))).isZero();
        assertThat(AnswerScorer.score(null, List.of("postgres"))).isZero();
    }

    @Test
    void emptyKeywordListScoresZero() {
        assertThat(AnswerScorer.score("anything", List.of())).isZero();
    }
}
