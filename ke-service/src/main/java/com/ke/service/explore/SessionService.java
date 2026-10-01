package com.ke.service.explore;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ke.domain.enums.CardStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.PathNodeEntity;
import com.ke.infra.entity.SessionEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.EntryMapper;
import com.ke.infra.mapper.PathNodeMapper;
import com.ke.infra.mapper.SessionMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 探索会话与路径树（FR-E01/E03/E05，Phase 3 终审钉死的 dangerous seam 逐条落地）：
 * - 恒需认证：匿名在 SecurityConfig anyRequest().authenticated() 兜底 401，本服务只管 403/404；
 * - 三分语义：不存在 → NotFoundException「会话不存在」；非属主 → AccessDeniedException「无权访问该会话」（不泄露细节）；
 * - latest：无会话返回 null（由 controller 转 404 envelope，保持 P3-13 冻结契约），按 updated_at DESC, id DESC 取第一；
 * - updated_at 应用层维护（无触发器）：创建/追节点/改讲解度时刷新（JVM 应用钟）；
 * - theme 收 key（academy/cuisine/sound，shared THEMES 权威）：后端不硬校验 key（向前兼容自由字符串），
 *   session.theme 存什么就是什么；
 * - 分支=对历史节点再挂子（A2 数据面），parent_node_id 自然成树，不建新表；
 * - isNewKnowledge=MVP 规则：同会话内该 card_version_id 首次出现=true（01 A1 语义）。
 */
@Service
public class SessionService {

    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;

    /** FR-E10 讲解度白名单（会话内记忆） */
    private static final Set<String> EXPLAIN_LEVELS = Set.of("SIMPLE", "DEEP", "CHILD");

    private final SessionMapper sessions;
    private final PathNodeMapper pathNodes;
    private final CardVersionMapper cardVersions;
    private final CardMapper cards;
    private final EntryMapper entries;

    public SessionService(SessionMapper sessions, PathNodeMapper pathNodes,
                          CardVersionMapper cardVersions, CardMapper cards, EntryMapper entries) {
        this.sessions = sessions;
        this.pathNodes = pathNodes;
        this.cardVersions = cardVersions;
        this.cards = cards;
        this.entries = entries;
    }

    // ---------- DTO ----------

    /** 断点续探摘要（Phase 4 冻结形状；前端格式化时间，Task 17 接线） */
    public record ResumeSession(long sessionId, String title, OffsetDateTime lastVisitedAt,
                                long nodeCount, long branchCount, long openQuestionCount) {
    }

    /** 我的路径列表行 */
    public record SessionItem(long sessionId, String theme, String title, String goal,
                              long nodeCount, long branchCount, OffsetDateTime lastVisitedAt, String status) {
    }

    /** offset 分页契约（page 从 1 起；total 恒在），与收藏/工作台列表同形 */
    public record SessionPage(List<SessionItem> items, long total, int page, int size) {
    }

    /** 树节点视图：cardTitle 由 card_version→card 批量 join 带出 */
    public record NodeView(long nodeId, Long parentNodeId, Long cardVersionId, Long entryId,
                           String questionText, Boolean isNewKnowledge, OffsetDateTime visitedAt, String cardTitle) {
    }

    /** 断点续探详情：会话元数据 + 完整树 */
    public record SessionDetail(long sessionId, String theme, String goal, String explainLevel, String status,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt, List<NodeView> nodes) {
    }

    /** 追加节点命令（除 sessionId 外均可选：纯提问节点不带卡） */
    public record AddNodeCommand(Long cardVersionId, Long entryId, Long parentNodeId, String questionText) {
    }

    // ---------- 创建/查询 ----------

    /** 创建会话：status ACTIVE、explain_level SIMPLE、created_at/updated_at 应用层赋 now */
    @Transactional
    public long create(long userId, String theme, String goal) {
        OffsetDateTime now = OffsetDateTime.now();
        SessionEntity session = new SessionEntity();
        session.setUserId(userId);
        session.setTheme(theme);
        session.setGoal(goal);
        session.setExplainLevel("SIMPLE");
        session.setStatus("ACTIVE");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessions.insert(session);
        return session.getId();
    }

    /**
     * 我的路径列表（updated_at DESC, id DESC）：offset 分页，page 从 1 起、size ≤ 50 默认 20。
     * title 取最新节点所访卡题（无节点 → goal 或「新探索」）；nodeCount/branchCount 一次聚合（防 N+1）。
     */
    @Transactional(readOnly = true)
    public SessionPage listMine(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        long total = sessions.selectCount(new LambdaQueryWrapper<SessionEntity>()
                .eq(SessionEntity::getUserId, userId));
        List<SessionEntity> rows = sessions.selectList(new LambdaQueryWrapper<SessionEntity>()
                .eq(SessionEntity::getUserId, userId)
                .orderByDesc(SessionEntity::getUpdatedAt)
                .orderByDesc(SessionEntity::getId)
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize));

        Map<Long, SessionMapper.SessionStatsRow> stats = statsByUser(userId);
        Map<Long, PathNodeMapper.LatestNodeRow> latestNodes = latestNodesByUser(userId);
        Map<Long, String> titles = cardTitlesByVersionIds(latestNodes.values().stream()
                .map(PathNodeMapper.LatestNodeRow::getCardVersionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));

        List<SessionItem> items = rows.stream()
                .map(s -> {
                    SessionMapper.SessionStatsRow stat = stats.get(s.getId());
                    PathNodeMapper.LatestNodeRow latest = latestNodes.get(s.getId());
                    // 全追问会话（最新节点无卡版本）title 走兜底；titles 为空 Map 时对 null 键取值会 NPE，先判
                    String latestTitle = latest == null || latest.getCardVersionId() == null ? null
                            : titles.get(latest.getCardVersionId());
                    return new SessionItem(
                            s.getId(), s.getTheme(), titleOf(s, latestTitle), s.getGoal(),
                            stat == null || stat.getNodeCount() == null ? 0 : stat.getNodeCount(),
                            stat == null || stat.getBranchCount() == null ? 0 : stat.getBranchCount(),
                            latest == null ? s.getUpdatedAt() : latest.getVisitedAt(),
                            s.getStatus());
                })
                .toList();
        return new SessionPage(items, total, safePage, safeSize);
    }

    /** 断点续探摘要：无会话 → null（controller 转 404，保持 P3-13 冻结契约） */
    @Transactional(readOnly = true)
    public ResumeSession latest(long userId) {
        SessionEntity session = sessions.selectList(new LambdaQueryWrapper<SessionEntity>()
                        .eq(SessionEntity::getUserId, userId)
                        .orderByDesc(SessionEntity::getUpdatedAt)
                        .orderByDesc(SessionEntity::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (session == null) {
            return null;
        }
        PathNodeMapper.LatestNodeRow latest = latestNodesByUser(userId).get(session.getId());
        // 最新节点可为纯追问节点（card_version_id 为空）：跳过查题，titleOf 走 goal→「新探索」兜底
        String latestTitle = null;
        if (latest != null && latest.getCardVersionId() != null) {
            latestTitle = cardTitlesByVersionIds(java.util.List.of(latest.getCardVersionId()))
                    .get(latest.getCardVersionId());
        }
        SessionMapper.SessionStatsRow stat = statsByUser(userId).get(session.getId());
        return new ResumeSession(
                session.getId(),
                titleOf(session, latestTitle),
                latest == null ? session.getUpdatedAt() : latest.getVisitedAt(),
                stat == null || stat.getNodeCount() == null ? 0 : stat.getNodeCount(),
                stat == null || stat.getBranchCount() == null ? 0 : stat.getBranchCount(),
                0L);
    }

    /** 会话 + 完整树（selectTree 从根递归），每节点附卡题（批量 join，防 N+1）。三分语义见类注释。 */
    @Transactional(readOnly = true)
    public SessionDetail detail(long userId, long sessionId) {
        SessionEntity session = requireOwned(userId, sessionId);
        List<PathNodeEntity> nodes = pathNodes.selectTree(sessionId);
        Map<Long, String> titles = cardTitlesByVersionIds(nodes.stream()
                .map(PathNodeEntity::getCardVersionId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet()));
        return new SessionDetail(
                session.getId(), session.getTheme(), session.getGoal(),
                session.getExplainLevel(), session.getStatus(),
                session.getCreatedAt(), session.getUpdatedAt(),
                nodes.stream().map(n -> toView(n, titles.get(n.getCardVersionId()))).toList());
    }

    // ---------- 写路径 ----------

    /**
     * 追加节点（FR-E05）：校验会话属主；parentNodeId 必须属于本会话（否则 400）；
     * card_version_id/entry_id 存在性校验（否则 400，避免外键 500）；
     * cardVersionId 所属卡须 PUBLISHED（否则 400「卡片未发布」——草稿/待审版本挂进会话即可经树接口
     * 读出卡题，绕过公开卡 404 可见性规则；版本不存在 400 已有，未发布 400 语义分立）；
     * isNewKnowledge=同会话内该 card_version_id 首次出现；写 visited_at 并刷新 session.updated_at。
     */
    @Transactional
    public NodeView addNode(long userId, long sessionId, AddNodeCommand cmd) {
        requireOwned(userId, sessionId);

        if (cmd.parentNodeId() != null) {
            PathNodeEntity parent = pathNodes.selectById(cmd.parentNodeId());
            // 父节点不属于本会话（别人的会话/不存在）一律 400，不区分泄露
            if (parent == null || !parent.getSessionId().equals(sessionId)) {
                throw new BadRequestException("父节点不属于该会话");
            }
        }
        String cardTitle = null;
        if (cmd.cardVersionId() != null) {
            CardVersionEntity version = cardVersions.selectById(cmd.cardVersionId());
            if (version == null) {
                throw new BadRequestException("卡片版本不存在");
            }
            CardEntity card = cards.selectById(version.getCardId());
            // 仅可挂已发布卡（与公开详情/收藏同则）：DRAFT/PENDING/DISABLED 一律拒之
            if (card == null || !CardStatus.PUBLISHED.name().equals(card.getStatus())) {
                throw new BadRequestException("卡片未发布");
            }
            cardTitle = card.getTitle();
        }
        if (cmd.entryId() != null && entries.selectById(cmd.entryId()) == null) {
            throw new BadRequestException("入口不存在");
        }

        boolean isNewKnowledge = cmd.cardVersionId() != null
                && pathNodes.selectCount(new LambdaQueryWrapper<PathNodeEntity>()
                        .eq(PathNodeEntity::getSessionId, sessionId)
                        .eq(PathNodeEntity::getCardVersionId, cmd.cardVersionId())) == 0;

        OffsetDateTime now = OffsetDateTime.now();
        PathNodeEntity node = new PathNodeEntity();
        node.setSessionId(sessionId);
        node.setParentNodeId(cmd.parentNodeId());
        node.setCardVersionId(cmd.cardVersionId());
        node.setEntryId(cmd.entryId());
        node.setQuestionText(cmd.questionText());
        node.setIsNewKnowledge(isNewKnowledge);
        node.setVisitedAt(now);
        pathNodes.insert(node);

        touch(sessionId, now);
        return toView(node, cardTitle);
    }

    /** FR-E10 讲解度更新：先校验属主（与 addNode 写路径同序，消除陌生会话枚举探测面），再 SIMPLE/DEEP/CHILD 白名单（非法 → 400）；顺带刷新 updated_at（会话活动） */
    @Transactional
    public String updateExplainLevel(long userId, long sessionId, String level) {
        requireOwned(userId, sessionId);
        if (level == null || !EXPLAIN_LEVELS.contains(level)) {
            throw new BadRequestException("讲解度仅支持 SIMPLE/DEEP/CHILD");
        }
        SessionEntity patch = new SessionEntity();
        patch.setId(sessionId);
        patch.setExplainLevel(level);
        patch.setUpdatedAt(OffsetDateTime.now());
        sessions.updateById(patch);
        return level;
    }

    // ---------- Task 18 讲解流水线支撑 ----------

    /**
     * 会话属主校验（讲解提交的前置同步校验用，钉子①）：三分语义与 detail 相同——
     * 不存在 404「会话不存在」、非属主 403「无权访问该会话」，返回会话供流水线复用。
     */
    @Transactional(readOnly = true)
    public SessionEntity ownedSession(long userId, long sessionId) {
        return requireOwned(userId, sessionId);
    }

    /**
     * 会话上下文摘要（讲解提示词用，异步线程内调用、已过属主校验）：
     * 主题/目标/讲解度 + 最近 5 条提问文本（按访问时间正序拼接）；无会话给空串。
     */
    @Transactional(readOnly = true)
    public String contextSummary(long sessionId) {
        SessionEntity session = sessions.selectById(sessionId);
        if (session == null) {
            return "";
        }
        List<String> questions = pathNodes.selectList(new LambdaQueryWrapper<PathNodeEntity>()
                        .eq(PathNodeEntity::getSessionId, sessionId)
                        .isNotNull(PathNodeEntity::getQuestionText)
                        .orderByDesc(PathNodeEntity::getVisitedAt)
                        .orderByDesc(PathNodeEntity::getId)
                        .last("LIMIT 5"))
                .stream().map(PathNodeEntity::getQuestionText).toList();
        StringBuilder sb = new StringBuilder("主题:").append(session.getTheme() == null ? "未设定" : session.getTheme())
                .append(";目标:").append(session.getGoal() == null ? "未设定" : session.getGoal())
                .append(";讲解度:").append(session.getExplainLevel());
        if (!questions.isEmpty()) {
            sb.append(";最近提问:").append(String.join(" / ", questions));
        }
        return sb.toString();
    }

    // ---------- 内部 ----------

    /** 三分语义核心：不存在 → 404「会话不存在」；非属主 → 403「无权访问该会话」（不泄露细节） */
    private SessionEntity requireOwned(long userId, long sessionId) {
        SessionEntity session = sessions.selectById(sessionId);
        if (session == null) {
            throw new NotFoundException("会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw new AccessDeniedException("无权访问该会话");
        }
        return session;
    }

    /** updated_at 应用层维护：仅刷时间列（MyBatis-Plus 默认非空字段更新，不碰其他列） */
    private void touch(long sessionId, OffsetDateTime now) {
        SessionEntity patch = new SessionEntity();
        patch.setId(sessionId);
        patch.setUpdatedAt(now);
        sessions.updateById(patch);
    }

    /** 标题规则：最新节点所访卡题 → goal → 「新探索」 */
    private static String titleOf(SessionEntity session, String latestCardTitle) {
        if (latestCardTitle != null && !latestCardTitle.isBlank()) {
            return latestCardTitle;
        }
        if (session.getGoal() != null && !session.getGoal().isBlank()) {
            return session.getGoal();
        }
        return "新探索";
    }

    /** 每会话 nodeCount/branchCount（按用户一次聚合） */
    private Map<Long, SessionMapper.SessionStatsRow> statsByUser(long userId) {
        return sessions.selectStatsByUser(userId).stream()
                .collect(Collectors.toMap(SessionMapper.SessionStatsRow::getSessionId, Function.identity()));
    }

    /** 每会话最新节点（visited_at DESC, id DESC 第一行） */
    private Map<Long, PathNodeMapper.LatestNodeRow> latestNodesByUser(long userId) {
        return pathNodes.selectLatestNodeByUser(userId).stream()
                .collect(Collectors.toMap(PathNodeMapper.LatestNodeRow::getSessionId, Function.identity()));
    }

    /** card_version_id → card.title 批量解析（版本 in 一次、卡 in 一次，防 N+1） */
    private Map<Long, String> cardTitlesByVersionIds(Collection<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> versionToCard = cardVersions.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(CardVersionEntity::getId, CardVersionEntity::getCardId));
        Map<Long, String> titleByCard = versionToCard.isEmpty() ? Map.of()
                : cards.selectBatchIds(new HashSet<>(versionToCard.values())).stream()
                        .collect(Collectors.toMap(CardEntity::getId, CardEntity::getTitle));
        return versionToCard.entrySet().stream()
                .filter(e -> titleByCard.containsKey(e.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, e -> titleByCard.get(e.getValue())));
    }

    private static NodeView toView(PathNodeEntity node, String cardTitle) {
        return new NodeView(node.getId(), node.getParentNodeId(), node.getCardVersionId(), node.getEntryId(),
                node.getQuestionText(), node.getIsNewKnowledge(), node.getVisitedAt(), cardTitle);
    }
}
