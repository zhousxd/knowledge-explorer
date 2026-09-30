package com.ke.domain.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CardStatusTest {
    @Test
    void publishedCanComeFromPendingOnly() {
        assertThat(CardStatus.PUBLISHED.canComeFrom(CardStatus.PENDING)).isTrue();
        assertThat(CardStatus.PUBLISHED.canComeFrom(CardStatus.DRAFT)).isFalse();
    }

    @Test
    void pendingCanComeFromDraftAndDisabledFromPublished() {
        assertThat(CardStatus.PENDING.canComeFrom(CardStatus.DRAFT)).isTrue();
        assertThat(CardStatus.DISABLED.canComeFrom(CardStatus.PUBLISHED)).isTrue();
    }
}
