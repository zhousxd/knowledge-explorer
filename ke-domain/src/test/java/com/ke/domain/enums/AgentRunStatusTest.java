package com.ke.domain.enums;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentRunStatusTest {
    @Test
    void queuedCanGoRunningButNotDone() {
        assertThat(AgentRunStatus.QUEUED.canTransitionTo(AgentRunStatus.RUNNING)).isTrue();
        assertThat(AgentRunStatus.QUEUED.canTransitionTo(AgentRunStatus.DONE)).isFalse();
    }

    @Test
    void terminalStatesAreDoneFailedTimeout() {
        assertThat(AgentRunStatus.DONE.isTerminal()).isTrue();
        assertThat(AgentRunStatus.RUNNING.isTerminal()).isFalse();
    }
}
