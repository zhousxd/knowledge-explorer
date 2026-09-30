package com.ke.domain.enums;

public enum CardStatus {
    DRAFT, PENDING, PUBLISHED, DISABLED;

    private static final java.util.Map<CardStatus, CardStatus> ALLOWED_FROM =
        java.util.Map.of(PENDING, DRAFT, PUBLISHED, PENDING, DISABLED, PUBLISHED, DRAFT, PENDING);

    /** 仅允许编辑/运营执行的状态流转（W2 审核模块使用，方向：目标 ← 来源） */
    public boolean canComeFrom(CardStatus from) {
        return ALLOWED_FROM.get(this) == from;
    }
}
