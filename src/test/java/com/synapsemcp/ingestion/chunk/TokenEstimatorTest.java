package com.synapsemcp.ingestion.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimatesTokensAsCeilingOfCharsDividedByFour() {
        assertThat(TokenEstimator.estimateTokens("")).isZero();
        assertThat(TokenEstimator.estimateTokens("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimateTokens("abcde")).isEqualTo(2);
        assertThat(TokenEstimator.estimateTokens("a")).isEqualTo(1);
    }

    @Test
    void windowTokensUsesTheThreeTierTableWithExactBoundaries() {
        assertThat(TokenEstimator.windowTokensFor(0)).isEqualTo(256);
        assertThat(TokenEstimator.windowTokensFor(999)).isEqualTo(256);
        assertThat(TokenEstimator.windowTokensFor(1_000)).isEqualTo(512);
        assertThat(TokenEstimator.windowTokensFor(50_000)).isEqualTo(512);
        assertThat(TokenEstimator.windowTokensFor(50_001)).isEqualTo(1_024);
    }

    @Test
    void overlapRatioUsesTheThreeTierTableWithExactBoundaries() {
        assertThat(TokenEstimator.overlapRatioFor(999)).isEqualTo(0.10);
        assertThat(TokenEstimator.overlapRatioFor(1_000)).isEqualTo(0.15);
        assertThat(TokenEstimator.overlapRatioFor(50_000)).isEqualTo(0.15);
        assertThat(TokenEstimator.overlapRatioFor(50_001)).isEqualTo(0.20);
    }

    @Test
    void windowCharsIsWindowTokensTimesFour() {
        assertThat(TokenEstimator.windowCharsFor(500)).isEqualTo(1_024);
        assertThat(TokenEstimator.windowCharsFor(2_000)).isEqualTo(2_048);
        assertThat(TokenEstimator.windowCharsFor(60_000)).isEqualTo(4_096);
    }
}
