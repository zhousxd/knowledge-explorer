package com.ke.domain.share;

import com.ke.domain.share.dto.CardVersionView;
import com.ke.domain.share.dto.SessionTreeInput;
import com.ke.domain.share.dto.SnapshotJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分享快照权限过滤器穷举（02 §9「有权阅读≠有权转发」，Task 29）：
 * 勾选子集生效 / 未勾选子树与隐藏分支不入 / 非公开卡版本整点剥离留占位（不泄露卡标题）/
 * 入口四态过滤（PENDING 钉子、DISABLED、他人 PRIVATE、本人 PRIVATE、PUBLIC）/
 * 入口滤空节点保留 / 纯追问保留 / 标题摘要覆盖 / 防御性拷贝 / 空集与 null 容忍。
 */
class SnapshotFilterTest {

    private static final Long SHARER = 7L;
    private static final Long OTHER = 9L;

    // ---------- 夹具 ----------

    private static SessionTreeInput.SessionNodeInput node(long nodeId, Long parentNodeId,
                                                          Long cardVersionId, String question) {
        return new SessionTreeInput.SessionNodeInput(nodeId, parentNodeId, cardVersionId,
                question, "2026-09-30T10:00:0" + nodeId % 10 + "Z");
    }

    private static CardVersionView.EntryView entry(String name, String status, String scope, Long authorId) {
        return new CardVersionView.EntryView(name, "包含", status, scope, authorId);
    }

    private static CardVersionView published(CardVersionView.EntryView... entries) {
        return new CardVersionView("已发布卡", "PUBLISHED", new ArrayList<>(Arrays.asList(entries)));
    }

    /** 三层树：1(卡100) → 2(纯追问) → 3(卡200)，外加独立节点 4(卡300)。 */
    private static SessionTreeInput tree() {
        return new SessionTreeInput(50L, "探索会话", "搞清楚 X",
                new ArrayList<>(List.of(
                        node(1L, null, 100L, "根问题"),
                        node(2L, 1L, null, "追问一"),
                        node(3L, 2L, 200L, "追问二"),
                        node(4L, null, 300L, "支线"))));
    }

    private static Set<Long> selected(Long... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    // ---------- 1. 勾选子集生效（简报原案一） ----------

    @Test
    void selectedSubsetOnly() {
        SnapshotJson json = SnapshotFilter.build(tree(), selected(1L, 4L),
                versions(), SHARER, null, null, "2026-09-30T12:00:00Z");

        assertThat(json.nodes()).extracting(SnapshotJson.SnapshotNode::question)
                .containsExactly("根问题", "支线");
        assertThat(json.nodes()).allMatch(n -> !n.removed());
        assertThat(json.generatedAt()).isEqualTo("2026-09-30T12:00:00Z");
        // Task 31 接续副本：nodeRef=原节点 id 供复制建映射（根节点 parentNodeRef=null）
        assertThat(json.nodes().get(0).nodeRef()).isEqualTo(1L);
        assertThat(json.nodes().get(0).parentNodeRef()).isNull();
        assertThat(json.nodes().get(1).nodeRef()).isEqualTo(4L);
        assertThat(json.nodes().get(1).parentNodeRef()).isNull();
    }

    // ---------- 2. 未勾选子树不入（勾选父节点不自动带上子树） ----------

    @Test
    void unselectedSubtreeExcluded() {
        // 只勾 1：子节点 2、孙节点 3 均未勾选，即使位于已勾选节点子树内也不入快照
        SnapshotJson json = SnapshotFilter.build(tree(), selected(1L),
                versions(), SHARER, null, null, "t0");

        assertThat(json.nodes()).hasSize(1);
        assertThat(json.nodes().get(0).question()).isEqualTo("根问题");
    }

    // ---------- 3. 隐藏分支不入（简报原案二：调用方剪枝后的选择集不含整条分支） ----------

    @Test
    void hiddenBranchExcluded() {
        // 选择集只含 1 与 4：整条 2→3 分支（含已勾选语义上的隐藏分支）被排除
        SnapshotJson json = SnapshotFilter.build(tree(), selected(1L, 4L),
                versions(), SHARER, null, null, "t0");

        assertThat(json.nodes()).extracting(SnapshotJson.SnapshotNode::question)
                .containsExactly("根问题", "支线")
                .doesNotContain("追问一", "追问二");
    }

    // ---------- 4. 非公开卡版本 → 整点剥离留占位（简报原案三） ----------

    @Test
    void unpublishedVersionRemovedWithPlaceholder() {
        Map<Long, CardVersionView> versions = versions();
        versions.put(100L, new CardVersionView("草稿卡", "DISABLED", List.of()));

        SnapshotJson json = SnapshotFilter.build(tree(), selected(1L, 2L),
                versions, SHARER, null, null, "t0");

        SnapshotJson.SnapshotNode placeholder = json.nodes().get(0);
        assertThat(placeholder.removed()).isTrue();
        assertThat(placeholder.note()).isEqualTo("内容已不可用");
        assertThat(placeholder.title()).isEqualTo("根问题"); // 不泄露不可用卡的标题
        assertThat(placeholder.cardVersionId()).isEqualTo(100L);
        assertThat(placeholder.entries()).isEmpty();
        // 占位节点保留追问上下文，树形可渲染
        assertThat(placeholder.question()).isEqualTo("根问题");
        assertThat(placeholder.visitedAt()).isEqualTo("2026-09-30T10:00:01Z");
    }

    // ---------- 5. 版本视图缺失（漏取/已删）→ 同样占位（fail-closed） ----------

    @Test
    void missingVersionViewRemovedWithPlaceholder() {
        // tree() 节点 4 引用 cardVersionId=300，此处版本表故意不含该键
        Map<Long, CardVersionView> incomplete = new LinkedHashMap<>();
        incomplete.put(100L, published());
        SnapshotJson json = SnapshotFilter.build(tree(), selected(4L),
                incomplete, SHARER, null, null, "t0");

        assertThat(json.nodes().get(0).removed()).isTrue();
        assertThat(json.nodes().get(0).note()).isEqualTo("内容已不可用");
        assertThat(json.nodes().get(0).title()).isEqualTo("支线");
    }

    // ---------- 6. P6 钉子：PENDING（待审）入口不入快照 ----------

    @Test
    void pendingEntryExcluded() {
        CardVersionView version = published(
                entry("待审入口", "PENDING", "PUBLIC", SHARER));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, version), SHARER, null, null, "t0");

        assertThat(json.nodes().get(0).removed()).isFalse();
        assertThat(json.nodes().get(0).entries()).isEmpty(); // 待审即使作者本人也不入
    }

    // ---------- 7. DISABLED（被驳回/下架）入口不入 ----------

    @Test
    void disabledEntryExcluded() {
        CardVersionView version = published(
                entry("被驳入口", "DISABLED", "PUBLIC", SHARER));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, version), SHARER, null, null, "t0");

        assertThat(json.nodes().get(0).entries()).isEmpty();
    }

    // ---------- 8. 他人 PRIVATE 入口不入 ----------

    @Test
    void privateOthersExcluded() {
        CardVersionView version = published(
                entry("他人私有", "ACTIVE", "PRIVATE", OTHER));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, version), SHARER, null, null, "t0");

        assertThat(json.nodes().get(0).entries()).isEmpty();
    }

    // ---------- 9. 本人 PRIVATE 入口入（分享者有权转发自己的私有入口） ----------

    @Test
    void privateOwnIncluded() {
        CardVersionView version = published(
                entry("本人私有", "ACTIVE", "PRIVATE", SHARER));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, version), SHARER, null, null, "t0");

        assertThat(json.nodes().get(0).entries()).hasSize(1);
        assertThat(json.nodes().get(0).entries().get(0).name()).isEqualTo("本人私有");
        // 快照入口只暴露展示字段，不泄露 authorId/scope/status
        assertThat(json.nodes().get(0).entries().get(0).relationLabel()).isEqualTo("包含");
    }

    // ---------- 10. ACTIVE PUBLIC 入口入（非本人创作也入：公共区人人可读可转发） ----------

    @Test
    void activePublicIncluded() {
        CardVersionView version = published(
                entry("公共入口", "ACTIVE", "PUBLIC", OTHER));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, version), SHARER, null, null, "t0");

        assertThat(json.nodes().get(0).entries()).extracting(SnapshotJson.SnapshotEntry::name)
                .containsExactly("公共入口");
    }

    // ---------- 11. 入口被滤干净（原本非空）→ 节点保留但 entries 空，不置 removed ----------

    @Test
    void cleanEntriesKeepNode() {
        CardVersionView version = published(
                entry("他人私有", "ACTIVE", "PRIVATE", OTHER),
                entry("待审", "PENDING", "PUBLIC", SHARER));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, version), SHARER, null, null, "t0");

        SnapshotJson.SnapshotNode n = json.nodes().get(0);
        assertThat(n.removed()).isFalse(); // 内容还在，入口没了——不伪装成不可用
        assertThat(n.note()).isNull();
        assertThat(n.title()).isEqualTo("已发布卡");
        assertThat(n.entries()).isEmpty();
    }

    // ---------- 12. 纯追问节点（无 cardVersionId）保留 ----------

    @Test
    void pureQuestionNodeKept() {
        SnapshotJson json = SnapshotFilter.build(tree(), selected(2L),
                versions(), SHARER, null, null, "t0");

        SnapshotJson.SnapshotNode n = json.nodes().get(0);
        assertThat(n.removed()).isFalse();
        assertThat(n.cardVersionId()).isNull();
        assertThat(n.title()).isEqualTo("追问一");
        assertThat(n.question()).isEqualTo("追问一");
        assertThat(n.entries()).isEmpty();
        assertThat(n.visitedAt()).isEqualTo("2026-09-30T10:00:02Z");
        // 父链引用随节点一并保留（接续副本重建树用）：追问一 nodeRef=2、父=根问题(1)
        assertThat(n.nodeRef()).isEqualTo(2L);
        assertThat(n.parentNodeRef()).isEqualTo(1L);
    }

    // ---------- 12b. 三层链的父引用完整（接续副本按 parentNodeRef 重建树） ----------

    @Test
    void parentRefChainPreserved() {
        SnapshotJson json = SnapshotFilter.build(tree(), selected(1L, 2L, 3L),
                versions(), SHARER, null, null, "t0");

        assertThat(json.nodes()).extracting(SnapshotJson.SnapshotNode::nodeRef)
                .containsExactly(1L, 2L, 3L);
        assertThat(json.nodes()).extracting(SnapshotJson.SnapshotNode::parentNodeRef)
                .containsExactly(null, 1L, 2L);
    }

    // ---------- 13. 标题摘要覆盖与回退 ----------

    @Test
    void titleSummaryOverride() {
        SnapshotJson overridden = SnapshotFilter.build(tree(), selected(1L), versions(),
                SHARER, "我的路线图", "自选摘要", "t0");
        assertThat(overridden.title()).isEqualTo("我的路线图");
        assertThat(overridden.summary()).isEqualTo("自选摘要");

        SnapshotJson defaults = SnapshotFilter.build(tree(), selected(1L), versions(),
                SHARER, null, null, "t0");
        assertThat(defaults.title()).isEqualTo("探索会话"); // 否则会话标题
        assertThat(defaults.summary()).isEqualTo("探索分享"); // 摘要无会话来源 → 默认文案

        // 空白覆盖视同未提供；会话标题也缺失时标题回退默认文案
        SessionTreeInput untitled = new SessionTreeInput(50L, " ", null,
                new ArrayList<>(List.of(node(1L, null, 100L, "q"))));
        SnapshotJson blankOverride = SnapshotFilter.build(untitled, selected(1L), versions(),
                SHARER, "  ", "", "t0");
        assertThat(blankOverride.title()).isEqualTo("探索分享");
        assertThat(blankOverride.summary()).isEqualTo("探索分享");
    }

    // ---------- 14. 防御性拷贝：不改入参、输出不可变 ----------

    @Test
    void inputNotMutated() {
        SessionTreeInput tree = tree();
        List<SessionTreeInput.SessionNodeInput> originalNodes =
                new ArrayList<>(tree.nodes());
        CardVersionView version = published(entry("公共", "ACTIVE", "PUBLIC", OTHER));
        List<CardVersionView.EntryView> originalEntries = new ArrayList<>(version.entries());
        Map<Long, CardVersionView> versions = new LinkedHashMap<>();
        versions.put(100L, version);

        SnapshotJson json = SnapshotFilter.build(tree, selected(1L), versions, SHARER, null, null, "t0");

        assertThat(tree.nodes()).containsExactlyElementsOf(originalNodes); // 输入树未被改写
        assertThat(version.entries()).containsExactlyElementsOf(originalEntries);
        assertThatThrownBy(() -> json.nodes().add(json.nodes().get(0)))
                .isInstanceOf(UnsupportedOperationException.class); // 输出不可变
        assertThatThrownBy(() -> json.nodes().get(0).entries().add(
                new SnapshotJson.SnapshotEntry("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- 15. 空选择 → 空节点 ----------

    @Test
    void emptySelectionEmptyNodes() {
        SnapshotJson emptySet = SnapshotFilter.build(tree(), Set.of(), versions(), SHARER, null, null, "t0");
        assertThat(emptySet.nodes()).isEmpty();

        SnapshotJson nullSelection = SnapshotFilter.build(tree(), null, versions(), SHARER, null, null, "t0");
        assertThat(nullSelection.nodes()).isEmpty(); // null 选择集视为空集（不 NPE）
    }

    // ---------- 16. null 容忍：null 树 / null 版本表 / null 未知状态（fail-closed） ----------

    @Test
    void nullInputsFailClosed() {
        SnapshotJson nullTree = SnapshotFilter.build(null, selected(1L), versions(), SHARER, null, null, "t0");
        assertThat(nullTree.nodes()).isEmpty();
        assertThat(nullTree.title()).isEqualTo("探索分享");

        SnapshotJson nullVersions = SnapshotFilter.build(tree(), selected(1L, 2L), null, SHARER, null, null, "t0");
        assertThat(nullVersions.nodes()).hasSize(2); // 节点 1 占位剥离，纯追问 2 保留
        assertThat(nullVersions.nodes().get(0).removed()).isTrue(); // 卡版本表缺失 → 占位
        assertThat(nullVersions.nodes().get(1).removed()).isFalse();

        // 未知状态字符串一律按不可见处理（白名单判等，fail-closed）；null 入口元素跳过
        CardVersionView odd = new CardVersionView("卡", "PUBLISHED",
                new ArrayList<>(Arrays.asList(entry("怪状态", "ARCHIVED", "PUBLIC", OTHER),
                        entry("怪scope", "ACTIVE", "FRIENDS", OTHER),
                        null)));
        SnapshotJson json = SnapshotFilter.build(treeWithCard(), selected(1L),
                Map.of(100L, odd), SHARER, null, null, "t0");
        assertThat(json.nodes().get(0).entries()).isEmpty();
    }

    // ---------- 夹具辅助 ----------

    private static Map<Long, CardVersionView> versions() {
        Map<Long, CardVersionView> versions = new LinkedHashMap<>();
        versions.put(100L, published(entry("公共入口", "ACTIVE", "PUBLIC", OTHER),
                entry("本人私有", "ACTIVE", "PRIVATE", SHARER)));
        versions.put(200L, published());
        versions.put(300L, published());
        return versions;
    }

    private static SessionTreeInput treeWithCard() {
        return new SessionTreeInput(50L, "探索会话", "搞清楚 X",
                new ArrayList<>(List.of(node(1L, null, 100L, "根问题"))));
    }
}
