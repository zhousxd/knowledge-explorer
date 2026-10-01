package com.ke.domain.share.dto;

import java.util.List;

/**
 * 分享快照 JSON 模型（02 §9「有权阅读≠有权转发」，Task 29）：{@code SnapshotFilter.build} 的输出，
 * 由 Task 30 的 service 包装成响应/落对象存储。所有列表不可变，节点保持输入顺序。
 *
 * @param title       快照标题（titleOverride 优先，否则会话标题，最终回退「探索分享」）
 * @param summary     快照摘要（summaryOverride 优先，否则「探索分享」）
 * @param nodes       按输入顺序的快照节点；被剥离节点以 {@code removed:true} 占位，保证树形可渲染
 * @param generatedAt ISO-8601 时间戳（由调用方传入，纯函数不取时钟）
 */
public record SnapshotJson(String title, String summary, List<SnapshotNode> nodes, String generatedAt) {

    /**
     * 快照节点：{@code cardVersionId} 为 null 表示纯追问节点（无挂接卡版本，title 即追问文本）；
     * {@code removed=true} 表示卡版本已不可用（非 PUBLISHED），title 用追问文本占位、不泄露卡标题。
     */
    public record SnapshotNode(String title, Long cardVersionId, List<SnapshotEntry> entries,
                               String question, String visitedAt, boolean removed, String note) {
    }

    /** 快照入口：只含展示所需的最小字段（名称/关系标签），不泄露 authorId/scope/status。 */
    public record SnapshotEntry(String name, String relationLabel) {
    }
}
