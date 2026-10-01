package com.ke.domain.share.dto;

import java.util.List;

/**
 * 分享快照的会话树输入形状（纯数据，由 Task 30 service 从 exploration_session / session_node 组装）：
 * 节点为按展示顺序的扁平列表，parentNodeId 仅描述树形——SnapshotFilter 按扁平列表渲染、
 * 不递归遍历，父链成环也不会死循环（环防护天然成立）。
 *
 * @param sessionId 会话 id（仅透传溯源，过滤器不使用）
 * @param title     会话标题（无 titleOverride 时的快照标题来源）
 * @param goal      探索目标（仅透传，过滤器不使用）
 * @param nodes     会话节点列表（可为 null，视为空树）
 */
public record SessionTreeInput(Long sessionId, String title, String goal, List<SessionNodeInput> nodes) {

    /** 会话树节点：cardVersionId 为 null 表示纯追问节点（该步未挂接卡版本）。 */
    public record SessionNodeInput(Long nodeId, Long parentNodeId, Long cardVersionId,
                                   String questionText, String visitedAt) {
    }
}
