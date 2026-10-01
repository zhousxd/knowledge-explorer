package com.ke.domain.share.dto;

import java.util.List;

/**
 * 卡版本可见性视图（versionId → 本视图，由 Task 30 service 从 card_version / entry 组装）。
 * 状态与范围用字符串直传（与 entry/card 表的 VARCHAR 语义一致，避免映射漂移）：
 * card.status ∈ DRAFT/PENDING/PUBLISHED/DISABLED；entry.status ∈ ACTIVE/PENDING/DISABLED
 * （PUBLIC 前置审核：待审为 PENDING，驳回/下架为 DISABLED）；entry.scope ∈ PRIVATE/PUBLIC。
 */
public record CardVersionView(String title, String status, List<EntryView> entries) {

    /** 入口可见性判定所需的最小字段。 */
    public record EntryView(String name, String relationLabel, String status, String scope, Long authorId) {
    }
}
