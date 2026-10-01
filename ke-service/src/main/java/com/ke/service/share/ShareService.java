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
import com.ke.infra.mapper.ShareMapper;
import com.ke.infra.mapper.ShareSnapshotMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
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
 * 分享创建/撤销/免登录浏览（FR-H01–H04/H06，02 §9「有权阅读≠有权转发」）：
 * - create：会话属主校验（403/404 三分，复用 {@link SessionService#ownedSession}）→ 勾选集校验
 *   （**P7-29 钉②：只接受属于该会话的节点 id**，外会话/不存在 → 400；空集 → 400。隐藏分支语义由此
 *   字面保障：SnapshotFilter 按选择集裁剪，未勾选的任何节点——含已勾选节点的子树——不入快照）→
 *   批量组装 SessionTreeInput + 卡版本视图（卡 title/status + ACTIVE 入口，scope/作者可见性交
 *   SnapshotFilter 按分享者判定——Task 29 单一实现；3 次批量查询防 N+1）→ SnapshotFilter.build →
 *   share + share_snapshot 一次事务 INSERT（快照不可变，只插不改）；token 冲突换号重试 ≤3 次。
 *   **裁决钉①**：快照=节点+标题+来源（SnapshotJson 形状天然不含 findings/artifacts——Task 29 裁决豁免）。
 * - revoke：属主校验 → revoked=true；已 revoked 幂等。
 * - viewPublic：revoked/不存在/快照缺失 → 统一 404「分享不存在」（不泄露存在性）。
 * - 埋点 share_create / share_view：**分析表由 Phase 8 Task 33 建（analytics_event），本任务仅打日志**。
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    /** token 唯一冲突换号重试上限（62^21 空间，实际不可达） */
    private static final int TOKEN_ATTEMPTS = 3;

    private final SessionService sessions;
    private final PathNodeMapper pathNodes;
    private final CardVersionMapper cardVersions;
    private final CardMapper cards;
    private final EntryMapper entries;
    private final ShareMapper shares;
    private final ShareSnapshotMapper snapshots;
    private final ObjectMapper objectMapper;
    /** share+snapshot 双写原子性 + token 冲突重试需独立事务：不走 @Transactional 自调用代理，显式模板 */
    private final TransactionTemplate writeTx;

    public ShareService(SessionService sessions, PathNodeMapper pathNodes,
                        CardVersionMapper cardVersions, CardMapper cards, EntryMapper entries,
                        ShareMapper shares, ShareSnapshotMapper snapshots, ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.pathNodes = pathNodes;
        this.cardVersions = cardVersions;
        this.cards = cards;
        this.entries = entries;
        this.shares = shares;
        this.snapshots = snapshots;
        this.objectMapper = objectMapper;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // ---------- DTO ----------

    /** 创建命令（objectType MVP 仅 'SESSION'；title/summary 可选，空白视同未提供） */
    public record CreateCommand(String objectType, Long objectId, List<Long> nodeIds, String title, String summary) {
    }

    /** 创建结果：token + 免登录页 url 路径片段（前端拼 origin） */
    public record ShareCreated(String token, String url) {
    }

    /** 匿名浏览视图：title/summary 与快照同源（share 表不存标题，撤销后即 404 不存在泄露面） */
    public record ShareView(String token, String title, String summary, SnapshotJson snapshot,
                            OffsetDateTime createdAt) {
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
        // 埋点 share_create：分析表 Phase 8 Task 33 落 analytics_event，本任务仅结构化日志（已与计划对齐）
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

    /** 匿名浏览：revoked / 不存在 / 快照缺失 → 统一 404「分享不存在」（不泄露存在性，FR-H06） */
    @Transactional(readOnly = true)
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
        // 埋点 share_view：分析表 Phase 8 Task 33 落 analytics_event，本任务仅结构化日志
        log.info("share_view shareId={} (埋点 Phase 8 Task 33 落表)", share.getId());
        return new ShareView(share.getToken(), snapshot.title(), snapshot.summary(), snapshot, share.getCreatedAt());
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
