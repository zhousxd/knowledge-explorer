package com.ke.domain.enums;

/**
 * 审核任务状态（FR-O03）：submit/publish 挂队列即 PENDING；
 * 编辑/运营 approve → APPROVED、reject → REJECTED（notes 必填）。
 */
public enum ReviewStatus {
    PENDING, APPROVED, REJECTED
}
