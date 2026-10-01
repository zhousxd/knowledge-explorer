package com.ke.service.share;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.share.SnapshotFilter;
import com.ke.domain.share.dto.CardVersionView;
import com.ke.domain.share.dto.SessionTreeInput;
import com.ke.domain.share.dto.SnapshotJson;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.CardVersionEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.entity.PathNodeEntity;
import com.ke.infra.entity.SessionEntity;
import com.ke.infra.entity.ShareEntity;
import com.ke.infra.entity.ShareSnapshotEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.CardVersionMapper;
import com.ke.infra.mapper.EntryMapper;
import com.ke.infra.mapper.PathNodeMapper;
import com.ke.infra.mapper.SessionMapper;
import com.ke.infra.mapper.ShareMapper;
import com.ke.infra.mapper.ShareSnapshotMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import com.ke.service.analytics.AnalyticsEvents;
import com.ke.service.analytics.AnalyticsService;
import com.ke.service.explore.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分享创建/撤销/免登录浏览/接续副本（FR-H01–H07，02 §9「有权阅读≠有权转发」）：
 * - create：会话属主校验（403/404 三分，复用 {@link SessionService#ownedSession}）→ 勾选集校验
 *   （**P7-29 钉②：只接受属于该会话的节点 id**，外会话/不存在 → 400；空集 → 400。隐藏分支语义由此
 *   字面保障：SnapshotFilter 按选择集裁剪，未勾选的任何节点——含已勾选节点的子树——不入快照）→
 *   批量组装 SessionTreeInput + 卡版本视图（卡 title/status + ACTIVE 入口，scope/作者可见性交
 *   SnapshotFilter 按分享者判定——Task 29 单一实现；3 次批量查询防 N+1）→ SnapshotFilter.build →
 *   share + share_snapshot 一次事务 INSERT（快照不可变，只插不改）；token 冲突换号重试 ≤3 次。
 *   **裁决钉①**：快照=节点+标题+来源（SnapshotJson 形状天然不含 findings/artifacts——Task 29 裁决豁免）。
 * - revoke：属主校验 → revoked=true；已 revoked 幂等。
 * - viewPublic：revoked/不存在/快照缺失 → 统一 404「分享不存在」（不泄露存在性）；
 *   响应附 {@link #CONTINUE_NOTICE} 静态差异提示（FR-H07 一期简化：仅提示条，不做后端差异计算）。
 * - continueFrom（Task 31，FR-H05，A5）：登录态按快照复制独立会话副本——user=接收者、theme 取原会话、
 *   goal=「接续自分享:{快照 title}」截 100、origin_share_id=shareId；节点按 snapshot.nodes 顺序复制
 *   （removed 占位跳过；parentNodeRef→新父 id 映射重建树，父不可映射挂根；isNewKnowledge 按副本内
 *   首现规则重算）；快照自足（分享可撤销/原会话可变，不读原树）；**原会话零写入**（A5）。
 * - 埋点 share_create / share_view / share_continue：**分析表由 Phase 8 Task 33 建（analytics_event），
 *   本任务仅打日志**。
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    /** token 唯一冲突换号重试上限（62^21 空间，实际不可达） */
    private static final int TOKEN_ATTEMPTS = 3;

    /** 接续副本 goal 前缀 + 总长上限（brief Task 31：截 100） */
    private static final String CONTINUE_GOAL_PREFIX = "接续自分享:";
    private static final int GOAL_MAX_LENGTH = 100;

    /** FR-H07 一期简化（决策钉）：静态差异提示常量，随 GET /s/{token} 下发，前端渲染常驻提示条；
     *  不做后端差异计算（来源/模型/入口变更的逐节点 diff 二期再做） */
    public static final String CONTINUE_NOTICE = "来源与模型可能已更新，接续后的结果或有差异";

    private final SessionService sessions;
    private final SessionMapper sessionRows;
    private final PathNodeMapper pathNodes;
    private final CardVersionMapper cardVersions;
    private final CardMapper cards;
    private final EntryMapper entries;
    private final ShareMapper shares;
    private final ShareSnapshotMapper snapshots;
    private final ObjectMapper objectMapper;
    /** 埋点（FR-O05，Task 33）：share_create / share_view / share_continue（Phase 7 日志钉子的落表兑现） */
    private final AnalyticsService analytics;
    /** share+snapshot 双写原子性 + token 冲突重试需独立事务：不走 @Transactional 自调用代理，显式模板 */
    private final TransactionTemplate writeTx;

    public ShareService(SessionService sessions, SessionMapper sessionRows, PathNodeMapper pathNodes,
                        CardVersionMapper cardVersions, CardMapper cards, EntryMapper entries,
                        ShareMapper shares, ShareSnapshotMapper snapshots, ObjectMapper objectMapper,
                        AnalyticsService analytics, PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.sessionRows = sessionRows;
        this.pathNodes = pathNodes;
        this.cardVersions = cardVersions;
        this.cards = cards;
        this.entries = entries;
        this.shares = shares;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.analytics = analytics;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // ---------- DTO ----------

    /**
     * 创建命令（objectType MVP 仅 'SESSION'）。必填/长度卡口在 controller 层
     * （{@code @NotBlank @Size(max=60)} title / {@code @Size(max=200)} summary，P7 复审 FIX），
     * 本层保持防御性宽松（空白视同未提供 → 快照标题走 goal/默认回退）。
     */
    public record CreateCommand(String objectType, Long objectId, List<Long> nodeIds, String title, String summary) {
    }

    /** 创建结果：token + 免登录页 url 路径片段（前端拼 origin） */
    public record ShareCreated(String token, String url) {
    }

    /** 接续副本结果：新会话 id + 复制节点数（removed 占位不入副本，故可小于快照节点数） */
    public record ContinueResult(long sessionId, long nodeCount) {
    }

    /**
     * 匿名浏览视图：title/summary 与快照同源（share 表不存标题，撤销后即 404 不存在泄露面）；
     * continueNotice=静态差异提示常量（FR-H07 一期简化，前端渲染常驻提示条）。
     */
    public record ShareView(String token, String title, String summary, SnapshotJson snapshot,
                            OffsetDateTime createdAt, String continueNotice) {
    }

    // ---------- 创建 ----------

    /**
     * 创建分享：校验链见类注释。读路径（属主/节点/视图组装）无副作用不占事务；
     * 写路径 {@link #insertShareAtomically} 独立事务（token 冲突可整单重试，无双写半截）。
     */
    public ShareCreated create(long userId, CreateCommand cmd) {
        if (cmd == null || !"SESSION".equals(cmd.objectType())) {
            throw new BadRequestException("objectType 仅支持 SESSION");
        }
        if (cmd.objectId() == null) {
            throw new BadRequestException("objectId 不能为空");
        }
        SessionEntity session = sessions.ownedSession(userId, cmd.objectId());

        if (cmd.nodeIds() == null || cmd.nodeIds().isEmpty()) {
            throw new BadRequestException("请至少选择一个节点");
        }
        List<PathNodeEntity> treeNodes = pathNodes.selectTree(cmd.objectId());
        Set<Long> sessionNodeIds = treeNodes.stream().map(PathNodeEntity::getId).collect(Collectors.toSet());
        // LinkedHashSet 去重保序；任一不属于本会话（他人会话/不存在）→ 400，不区分泄露（P7-29 钉②）
        Set<Long> selected = new LinkedHashSet<>();
        for (Long nodeId : cmd.nodeIds()) {
            if (nodeId == null || !sessionNodeIds.contains(nodeId)) {
                throw new BadRequestException("节点不属于该会话");
            }
            selected.add(nodeId);
        }

        Map<Long, CardVersionView> versionViews = buildVersionViews(treeNodes, selected, userId);
        SessionTreeInput tree = new SessionTreeInput(session.getId(), session.getGoal(), session.getGoal(),
                treeNodes.stream().map(ShareService::toNodeInput).toList());
        SnapshotJson snapshot = SnapshotFilter.build(tree, selected, versionViews, userId,
                blankToNull(cmd.title()), blankToNull(cmd.summary()), OffsetDateTime.now().toString());
        String json;
        try {
            json = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分享快照序列化失败", e);
        }

        ShareCreated created = insertShareAtomically(userId, cmd.objectId(), json);
        // 埋点 share_create（FR-O05，Task 33 落 analytics_event，替代 Phase 7 的仅日志）
        analytics.track(userId, AnalyticsEvents.SHARE_CREATE,
                AnalyticsService.params("sessionId", cmd.objectId(), "nodeCount", selected.size()));
        log.info("share_create userId={} sessionId={} shareTokenHash={} nodeCount={} snapshotBytes={}",
                userId, cmd.objectId(), created.token().hashCode(), selected.size(), json.length());
        return created;
    }

    // ---------- 撤销 ----------

    /** 撤销：属主校验（404/403 三分）→ revoked=true；已 revoked 幂等（不重复写） */
    @Transactional
    public void revoke(long userId, String token) {
        ShareEntity share = requireShare(token);
        if (!share.getUserId().equals(userId)) {
            throw new AccessDeniedException("无权操作该分享");
        }
        if (Boolean.TRUE.equals(share.getRevoked())) {
            return; // 幂等：重复撤销不出错
        }
        ShareEntity patch = new ShareEntity();
        patch.setId(share.getId());
        patch.setRevoked(true);
        shares.updateById(patch);
        log.info("share_revoke userId={} shareId={} (埋点 Phase 8 Task 33 落表)", userId, share.getId());
    }

    // ---------- 免登录浏览 ----------

    /**
     * 匿名浏览：revoked / 不存在 / 快照缺失 → 统一 404「分享不存在」（不泄露存在性，FR-H06）。
     * 不再包 readOnly 事务（Task 33）：本方法兼做 share_view 埋点写入，readOnly 连接会拒绝 INSERT
     * （异常被 AnalyticsService 吞掉 → 指标③分母恒缺）；方法体仅两条独立读 + 反序列化，无需事务包裹。
     */
    public ShareView viewPublic(String token) {
        ShareEntity share = requireShare(token);
        if (Boolean.TRUE.equals(share.getRevoked())) {
            throw missing(); // 与「不存在」同形：撤销即匿名不可达
        }
        ShareSnapshotEntity row = snapshots.selectOne(new LambdaQueryWrapper<ShareSnapshotEntity>()
                .eq(ShareSnapshotEntity::getShareId, share.getId())
                .orderByDesc(ShareSnapshotEntity::getId)
                .last("LIMIT 1"));
        if (row == null) {
            throw missing(); // 快照缺失属数据异常，同样 404 不外泄半截分享
        }
        SnapshotJson snapshot;
        try {
            snapshot = objectMapper.readValue(row.getSnapshotJson(), SnapshotJson.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分享快照数据损坏", e);
        }
        // 埋点 share_view（FR-O05，Task 33）：匿名浏览 user_id 为 null（指标③分母）
        analytics.track(null, AnalyticsEvents.SHARE_VIEW, AnalyticsService.params("shareId", share.getId()));
        log.info("share_view shareId={} (Task 33 已落 analytics_event)", share.getId());
        return new ShareView(share.getToken(), snapshot.title(), snapshot.summary(), snapshot,
                share.getCreatedAt(), CONTINUE_NOTICE);
    }

    // ---------- 接续副本（Task 31，FR-H05，A5） ----------

    /**
     * 接续：登录态（SecurityConfig 仅 GET /s/* permitAll，POST 走 anyRequest().authenticated() 兜底 401）
     * 按快照复制独立会话副本。revoked/不存在/快照缺失 → 与匿名浏览同形 404「分享不存在」。
     *
     * <p>复制规则（快照自足——分享可撤销、原会话可变，不读原树）：
     * <ul>
     *   <li>新会话：user=接收者、theme 取原会话行（theme 建后无更新路径，行不物理删）、
     *       goal=「接续自分享:{快照 title}」截 100、origin_share_id=shareId、status=ACTIVE、
     *       explain_level=SIMPLE；</li>
     *   <li>节点：按 snapshot.nodes 顺序（P4-16 树序列化序）复制；removed 占位跳过；
     *       新 parentId=parentNodeRef→新 id 映射（根/父不可映射——removed 或未勾选的父——挂根）；
     *       cardVersionId 原样保留（版本不可变，全局引用）；entryId 快照未含 → null；
     *       isNewKnowledge 按副本内该 cardVersionId 首现规则重算（与 SessionService.addNode 同则）；</li>
     *   <li>visited_at 统一取同一 now：树序（visited_at,id）退化为插入序=快照序，确定性成立；</li>
     *   <li>**原会话零写入**（A5）：只 INSERT 新行，不 UPDATE 任何既有行。</li>
     * </ul>
     */
    public ContinueResult continueFrom(String token, long userId) {
        ShareEntity share = requireShare(token);
        if (Boolean.TRUE.equals(share.getRevoked())) {
            throw missing(); // 与「不存在」同形：撤销即不可接续
        }
        ShareSnapshotEntity row = snapshots.selectOne(new LambdaQueryWrapper<ShareSnapshotEntity>()
                .eq(ShareSnapshotEntity::getShareId, share.getId())
                .orderByDesc(ShareSnapshotEntity::getId)
                .last("LIMIT 1"));
        if (row == null) {
            throw missing(); // 快照缺失属数据异常，同形 404
        }
        SnapshotJson snapshot;
        try {
            snapshot = objectMapper.readValue(row.getSnapshotJson(), SnapshotJson.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分享快照数据损坏", e);
        }
        // theme 取原会话（SnapshotJson 不含 theme；会话行无物理删除路径，join 稳定）
        SessionEntity origin = share.getObjectId() == null ? null : sessionRows.selectById(share.getObjectId());
        String theme = origin == null ? null : origin.getTheme();
        String goal = truncateGoal(CONTINUE_GOAL_PREFIX + (snapshot.title() == null ? "" : snapshot.title()));

        ContinueResult result = writeTx.execute(tx -> copySnapshot(share.getId(), userId, theme, goal, snapshot));
        long newSessionId = result.sessionId();
        // 埋点 share_continue（FR-O05，Task 33）：指标③分子
        analytics.track(userId, AnalyticsEvents.SHARE_CONTINUE,
                AnalyticsService.params("originShareId", share.getId(), "newSessionId", newSessionId,
                        "nodeCount", result.nodeCount()));
        log.info("share_continue userId={} originShareId={} newSessionId={} nodeCount={} (Task 33 已落 analytics_event)",
                userId, share.getId(), newSessionId, result.nodeCount());
        return result;
    }

    /** 事务体：INSERT 新会话 + 按 snapshot.nodes 顺序复制节点（零写入原会话，A5） */
    private ContinueResult copySnapshot(long shareId, long userId, String theme, String goal,
                                        SnapshotJson snapshot) {
        OffsetDateTime now = OffsetDateTime.now();
        SessionEntity copy = new SessionEntity();
        copy.setUserId(userId);
        copy.setTheme(theme);
        copy.setGoal(goal);
        copy.setExplainLevel("SIMPLE");
        copy.setStatus("ACTIVE");
        copy.setOriginShareId(shareId);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        sessionRows.insert(copy);

        // nodeRef(原节点 id) → 新节点 id；快照节点按序即树序，逐点建映射供子节点挂父
        Map<Long, Long> newNodeIdByRef = new HashMap<>();
        Set<Long> seenVersions = new java.util.HashSet<>();
        long copied = 0;
        if (snapshot.nodes() != null) {
            for (SnapshotJson.SnapshotNode node : snapshot.nodes()) {
                if (node == null || node.removed()) {
                    continue; // removed 占位（卡版本不可用）不入副本
                }
                Long parentNodeId = node.parentNodeRef() == null ? null
                        : newNodeIdByRef.get(node.parentNodeRef()); // 父被剥离/未入快照 → 挂根
                // isNewKnowledge=副本内该 card_version_id 首现（01 A1 语义，与 addNode 同则）
                boolean isNewKnowledge = node.cardVersionId() != null && seenVersions.add(node.cardVersionId());
                PathNodeEntity nodeRow = new PathNodeEntity();
                nodeRow.setSessionId(copy.getId());
                nodeRow.setParentNodeId(parentNodeId);
                nodeRow.setCardVersionId(node.cardVersionId());
                nodeRow.setEntryId(null); // 快照不含入口引用（裁决钉①：快照=节点+标题+来源）
                nodeRow.setQuestionText(node.question());
                nodeRow.setIsNewKnowledge(isNewKnowledge);
                nodeRow.setVisitedAt(now); // 同钟：树序 (visited_at,id) 退化为插入序=快照序
                pathNodes.insert(nodeRow);
                if (node.nodeRef() != null) {
                    newNodeIdByRef.put(node.nodeRef(), nodeRow.getId());
                }
                copied++;
            }
        }
        return new ContinueResult(copy.getId(), copied);
    }

    /** goal 截断到 100 字符（brief Task 31） */
    private static String truncateGoal(String goal) {
        return goal.length() <= GOAL_MAX_LENGTH ? goal : goal.substring(0, GOAL_MAX_LENGTH);
    }

    // ---------- 内部 ----------

    private ShareEntity requireShare(String token) {
        if (token == null || token.isBlank()) {
            throw missing();
        }
        ShareEntity share = shares.selectOne(new LambdaQueryWrapper<ShareEntity>()
                .eq(ShareEntity::getToken, token));
        if (share == null) {
            throw missing();
        }
        return share;
    }

    private static NotFoundException missing() {
        return new NotFoundException("分享不存在");
    }

    /** 会话树节点输入（扁平列表按 visited_at,id 稳定序；纯追问节点 cardVersionId=null） */
    private static SessionTreeInput.SessionNodeInput toNodeInput(PathNodeEntity node) {
        return new SessionTreeInput.SessionNodeInput(node.getId(), node.getParentNodeId(), node.getCardVersionId(),
                node.getQuestionText(), node.getVisitedAt() == null ? null : node.getVisitedAt().toString());
    }

    /**
     * 卡版本视图批量组装（仅勾选节点涉及的版本；会话节点 ≤ 数十，3 次批量查询可接受，防 N+1）：
     * version → 卡（title/status，PUBLISHED 判定用卡状态）+ 该卡 ACTIVE 入口
     * （scope/作者可见性由 SnapshotFilter 按分享者判定——Task 29 单一实现，此处不预过滤）。
     * 版本在而卡缺失（理论 FK 不可达）→ 不放视图 → 过滤器按占位剥离（fail-closed）。
     */
    private Map<Long, CardVersionView> buildVersionViews(List<PathNodeEntity> treeNodes, Set<Long> selected,
                                                         long sharerUserId) {
        Set<Long> versionIds = treeNodes.stream()
                .filter(n -> selected.contains(n.getId()))
                .map(PathNodeEntity::getCardVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CardVersionEntity> versionById = cardVersions.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(CardVersionEntity::getId, Function.identity()));
        Set<Long> cardIds = versionById.values().stream()
                .map(CardVersionEntity::getCardId).collect(Collectors.toSet());
        Map<Long, CardEntity> cardById = cardIds.isEmpty() ? Map.of()
                : cards.selectBatchIds(cardIds).stream()
                        .collect(Collectors.toMap(CardEntity::getId, Function.identity()));
        Map<Long, List<CardVersionView.EntryView>> entriesByCard = cardIds.isEmpty() ? Map.of()
                : entries.selectList(new LambdaQueryWrapper<EntryEntity>()
                                .in(EntryEntity::getCardId, cardIds)
                                .eq(EntryEntity::getStatus, "ACTIVE")
                                .orderByAsc(EntryEntity::getId))
                        .stream()
                        .collect(Collectors.groupingBy(EntryEntity::getCardId,
                                Collectors.mapping(ShareService::toEntryView, Collectors.toList())));
        Map<Long, CardVersionView> views = new HashMap<>();
        for (CardVersionEntity version : versionById.values()) {
            CardEntity card = cardById.get(version.getCardId());
            if (card == null) {
                continue;
            }
            views.put(version.getId(), new CardVersionView(card.getTitle(), card.getStatus(),
                    entriesByCard.getOrDefault(version.getCardId(), List.of())));
        }
        return views;
    }

    private static CardVersionView.EntryView toEntryView(EntryEntity entry) {
        return new CardVersionView.EntryView(entry.getName(), entry.getRelationLabel(),
                entry.getStatus(), entry.getScope(), entry.getAuthorId());
    }

    /**
     * share + share_snapshot 一次事务 INSERT（快照不可变）；token 撞 UNIQUE 换号重试 ≤3 次。
     * 独立事务是重试的前提：PG 里约束冲突会中止当前事务，同事务内换号再插必失败
     * （「current transaction is aborted」），故用 TransactionTemplate 每次尝试新事务。
     */
    private ShareCreated insertShareAtomically(long userId, long sessionId, String snapshotJson) {
        for (int attempt = 1; attempt <= TOKEN_ATTEMPTS; attempt++) {
            String token = ShareTokens.next();
            try {
                writeTx.executeWithoutResult(tx -> {
                    ShareEntity share = new ShareEntity();
                    share.setToken(token);
                    share.setObjectType("SESSION");
                    share.setObjectId(sessionId);
                    share.setUserId(userId);
                    share.setVisibility("PUBLIC");
                    share.setRevoked(false);
                    shares.insert(share);
                    ShareSnapshotEntity snap = new ShareSnapshotEntity();
                    snap.setShareId(share.getId());
                    snap.setSnapshotJson(snapshotJson);
                    snapshots.insert(snap);
                });
                return new ShareCreated(token, "/s/" + token);
            } catch (DuplicateKeyException conflict) {
                log.debug("share token conflict, attempt {}/{} userId={}", attempt, TOKEN_ATTEMPTS, userId);
            }
        }
        throw new IllegalStateException("分享创建失败：token 分配冲突，请重试");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
