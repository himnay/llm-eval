package com.org.llm.eval;

import com.org.llm.eval.dto.AnswerScorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerScorerTest {

    @Test
    @DisplayName("Score is 1.0 when the answer contains every expected keyword")
    void fullRecallScoresOne() {
        assertThat(AnswerScorer.score(
                "We use Postgres 18 behind Keycloak.", List.of("postgres", "18", "keycloak")))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Score reflects the fraction of expected keywords found in the answer")
    void partialRecallScoresFraction() {
        assertThat(AnswerScorer.score(
                "We use Postgres.", List.of("postgres", "keycloak"))).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Keyword matching ignores case differences between answer and keywords")
    void matchIsCaseInsensitive() {
        assertThat(AnswerScorer.score("PROMETHEUS and Grafana", List.of("prometheus", "grafana")))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Blank or null answers score zero regardless of expected keywords")
    void blankAnswerScoresZero() {
        assertThat(AnswerScorer.score("  ", List.of("postgres"))).isZero();
        assertThat(AnswerScorer.score(null, List.of("postgres"))).isZero();
    }

    @Test
    @DisplayName("An empty keyword list scores zero even for a non-empty answer")
    void emptyKeywordListScoresZero() {
        assertThat(AnswerScorer.score("anything", List.of())).isZero();
    }
}
