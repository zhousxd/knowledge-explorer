package com.ke.domain.share;

import com.ke.domain.share.dto.CardVersionView;
import com.ke.domain.share.dto.SessionTreeInput;
import com.ke.domain.share.dto.SnapshotJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分享快照权限过滤器（02 §9「有权阅读≠有权转发」，Task 29）：把一次探索会话裁剪成
 * 接收方有权阅读的只读快照。独立落实入口可见性规则（Phase 6 终审 seam 钉子）：
 * <ul>
 *   <li>未勾选节点不入快照——即使位于已勾选节点的子树内（子树剪枝由调用方在勾选时完成）；</li>
 *   <li>节点挂接的卡版本 status != PUBLISHED（或版本视图缺失）→ 节点整点剥离，
 *       留 {@code removed:true, note:'内容已不可用'} 占位，title 用追问文本，不泄露卡标题；</li>
 *   <li>纯追问节点（无 cardVersionId）保留，entries 恒空；</li>
 *   <li>入口可见 = status=ACTIVE 且（scope=PUBLIC 或 authorId=分享者本人）；
 *       PENDING（待审）/DISABLED（驳回下架）/他人 PRIVATE 一律不入。入口被滤干净
 *       （原本非空）→ 节点保留但 entries 空，不置 removed——内容还在，入口没了。</li>
 * </ul>
 *
 * <p>纯函数：零 IO、零 Spring、无时钟（generatedAt 由调用方传入 ISO 时间）；不修改任何入参，
 * 输出全部不可变拷贝；节点按输入顺序渲染，不递归遍历父链（成环安全）。
 * 状态/范围按字符串白名单判等，未知值一律按不可见处理（fail-closed）。
 */
public final class SnapshotFilter {

    /** 卡版本不可用占位文案（removed 节点的 note）。 */
    public static final String UNAVAILABLE_NOTE = "内容已不可用";

    /** 标题/摘要的最终回退文案。 */
    public static final String DEFAULT_TITLE = "探索分享";

    private static final String CARD_PUBLISHED = "PUBLISHED";
    private static final String ENTRY_ACTIVE = "ACTIVE";
    private static final String SCOPE_PUBLIC = "PUBLIC";

    private SnapshotFilter() {
    }

    /**
     * @param tree            会话树输入（可为 null，视为空树）
     * @param selectedNodeIds 分享者勾选入快照的节点 id 集合（勾选动作在分享设置页完成；null 视为空集 → 空快照）
     * @param cardVersions    versionId → 卡版本可见性视图（null 视为空表 → 全部占位剥离）
     * @param sharerUserId    分享者用户 id（null 时仅 PUBLIC 入口可见）
     * @param titleOverride   快照标题覆盖（空白视同未提供）
     * @param summaryOverride 快照摘要覆盖（空白视同未提供）
     * @param generatedAt     ISO-8601 生成时间（调用方传入，纯函数不取时钟）
     */
    public static SnapshotJson build(SessionTreeInput tree, Set<Long> selectedNodeIds,
                                     Map<Long, CardVersionView> cardVersions, Long sharerUserId,
                                     String titleOverride, String summaryOverride, String generatedAt) {
        Set<Long> selected = selectedNodeIds == null ? Set.of() : selectedNodeIds;
        Map<Long, CardVersionView> versions = cardVersions == null ? Map.of() : cardVersions;
        List<SnapshotJson.SnapshotNode> nodes = new ArrayList<>();
        if (tree != null && tree.nodes() != null) {
            for (SessionTreeInput.SessionNodeInput node : tree.nodes()) {
                if (node == null || node.nodeId() == null || !selected.contains(node.nodeId())) {
                    continue; // 未勾选节点不入快照
                }
                // 纯追问节点 cardVersionId 为 null：跳过查表（不可变 Map 对 null 键不友好）
                nodes.add(renderNode(node,
                        node.cardVersionId() == null ? null : versions.get(node.cardVersionId()),
                        sharerUserId));
            }
        }
        return new SnapshotJson(
                firstNonBlank(titleOverride, tree == null ? null : tree.title(), DEFAULT_TITLE),
                firstNonBlank(summaryOverride, DEFAULT_TITLE),
                List.copyOf(nodes),
                generatedAt);
    }

    private static SnapshotJson.SnapshotNode renderNode(SessionTreeInput.SessionNodeInput node,
                                                        CardVersionView version, Long sharerUserId) {
        if (node.cardVersionId() == null) {
            // 纯追问：保留（title=追问文本，entries 恒空）
            return new SnapshotJson.SnapshotNode(node.questionText(), null, List.of(),
                    node.questionText(), node.visitedAt(), false, null,
                    node.nodeId(), node.parentNodeId());
        }
        if (version == null || !CARD_PUBLISHED.equals(version.status())) {
            // 无权阅读的卡版本：整点剥离留占位（不泄露不可用版本的标题与入口）
            return new SnapshotJson.SnapshotNode(node.questionText(), node.cardVersionId(),
                    List.of(), node.questionText(), node.visitedAt(), true, UNAVAILABLE_NOTE,
                    node.nodeId(), node.parentNodeId());
        }
        List<SnapshotJson.SnapshotEntry> entries = new ArrayList<>();
        if (version.entries() != null) {
            for (CardVersionView.EntryView entry : version.entries()) {
                if (entry == null || !visibleTo(entry, sharerUserId)) {
                    continue;
                }
                entries.add(new SnapshotJson.SnapshotEntry(entry.name(), entry.relationLabel()));
            }
        }
        return new SnapshotJson.SnapshotNode(version.title(), node.cardVersionId(),
                List.copyOf(entries), node.questionText(), node.visitedAt(), false, null,
                node.nodeId(), node.parentNodeId());
    }

    /** 入口可见 = ACTIVE 且（PUBLIC 或 分享者本人创作）；未知状态/范围按不可见（fail-closed）。 */
    private static boolean visibleTo(CardVersionView.EntryView entry, Long sharerUserId) {
        return ENTRY_ACTIVE.equals(entry.status())
                && (SCOPE_PUBLIC.equals(entry.scope())
                        || (sharerUserId != null && sharerUserId.equals(entry.authorId())));
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
