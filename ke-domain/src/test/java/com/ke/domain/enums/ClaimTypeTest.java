package com.ke.domain.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimTypeTest {
    @Test
    void symbolsAreTripleCircleEncoding() {
        assertThat(ClaimType.FACT.symbol()).isEqualTo("●");
        assertThat(ClaimType.SYNTHESIS.symbol()).isEqualTo("◐");
        assertThat(ClaimType.GEN.symbol()).isEqualTo("○");
    }
}
