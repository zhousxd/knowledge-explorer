package com.ke.domain.enums;

public enum AgentRunStatus {
    QUEUED, RUNNING, DONE, FAILED, TIMEOUT;

    private static final java.util.Map<AgentRunStatus, java.util.Set<AgentRunStatus>> ALLOWED =
        java.util.Map.of(
            QUEUED, java.util.Set.of(RUNNING),
            RUNNING, java.util.Set.of(DONE, FAILED, TIMEOUT));

    public boolean canTransitionTo(AgentRunStatus next) {
        return ALLOWED.getOrDefault(this, java.util.Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == TIMEOUT;
    }
}
