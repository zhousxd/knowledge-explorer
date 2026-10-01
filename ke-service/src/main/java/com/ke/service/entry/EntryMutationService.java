package com.ke.service.entry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.entry.EntryConfig;
import com.ke.domain.entry.EntryConfigValidator;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.enums.EntryType;
import com.ke.domain.enums.ReviewStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.entity.ReviewTaskEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.EntryMapper;
import com.ke.infra.mapper.ReviewTaskMapper;
import com.ke.service.agent.ExplainService;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 入口写路径（FR-N02–N05/N07，Task 28）：保存 / 试运行 / 我的入口 / scope 双通道切换。
 * <ul>
 *   <li><b>保存</b>（POST /api/entries）：卡存在（404）且 PUBLISHED（400）；配置必过
 *       {@link EntryConfigValidator#validate}——allowedServiceTypes={EXPLAIN,COMPARE}、
 *       allowedAssetScope=该卡挂接且授权有效的资产集（{@link EntryAuthorizedAssets}）、
 *       crossTheme 从实体算（所属卡 theme vs 目标卡 theme，theme 缺失按 {@link RelationGuard}
 *       同则不视为跨主题）——violations 非空抛 {@link EntryConfigViolationException} → 400 清单
 *       （保存端没有草稿期的自动收窄，白名单是硬边界，没有例外路径）；落库后再过
 *       {@link RelationGuard} 复检 config_json 形状（双保险）。scope=PRIVATE 直接 ACTIVE；
 *       PUBLIC 同样创建即 ACTIVE 但挂 review_task(object_type=ENTRY, action=SUBMIT)——
 *       复用 P1-4 审核队列（决策：公共入口创建即可用，审核通过维持，驳回 DISABLED）。</li>
 *   <li><b>试运行</b>（POST /api/entries/{id}/test）：仅作者（403）；LINK_CARD 400「链接类入口
 *       无需试运行」；DISABLED 400。服务入口=调 {@link ExplainService#explain} 真实执行一次
 *       （serviceType=入口.serviceType、question=config.goal、cardVersionId=所属卡当前版本、
 *       sessionId=null——P5-18 无会话 run 不落 artifact 只回状态），配额在 explain 内前置消耗；
 *       成功落 run 后 entry.test_total+1（202 {runId}），轮询终态由前端做。</li>
 *   <li><b>我的入口</b>（GET /api/entries/mine）：author_id=本人，含状态/所属卡题/试运行次数。</li>
 *   <li><b>scope 切换</b>（PUT /api/entries/{id}/scope）：仅作者；PRIVATE→PUBLIC 挂审核
 *       （入口即 ACTIVE）；PUBLIC→PRIVATE 直接；同值幂等不重复挂队列。</li>
 * </ul>
 */
@Service
public class EntryMutationService {

    /** 服务入口的 serviceType 白名单（02 §5 两个已上线服务，与草稿抽取共用同一取值域） */
    static final Set<String> SERVICE_WHITELIST = Set.of("EXPLAIN", "COMPARE");
    private static final Set<String> SCOPES = Set.of("PRIVATE", "PUBLIC");

    private final CardMapper cards;
    private final EntryMapper entries;
    private final ReviewTaskMapper reviewTasks;
    private final EntryAuthorizedAssets authorizedAssets;
    private final RelationGuard relationGuard;
    private final ExplainService explain;
    private final ObjectMapper objectMapper;

    public EntryMutationService(CardMapper cards, EntryMapper entries, ReviewTaskMapper reviewTasks,
                                EntryAuthorizedAssets authorizedAssets, RelationGuard relationGuard,
                                ExplainService explain, ObjectMapper objectMapper) {
        this.cards = cards;
        this.entries = entries;
        this.reviewTasks = reviewTasks;
        this.authorizedAssets = authorizedAssets;
        this.relationGuard = relationGuard;
        this.explain = explain;
        this.objectMapper = objectMapper;
    }

    /** 创建/切换结果（201/200 data）：入口 id + 生效 scope + 状态 */
    public record EntryWritten(long entryId, String scope, String status) {
    }

    /** 试运行受理（202 data）：runId + 计数快照 */
    public record TestReceipt(long runId, int testTotal) {
    }

    /** 我的入口行：状态/类型/范围/所属卡题/试运行次数（工作台入口编排与探索端个人空间共用） */
    public record MineItem(long id, String name, String type, String serviceType, String relationLabel,
                           Long targetCardId, String scope, String status, int testTotal,
                           long cardId, String cardTitle) {
    }

    // ---------- 保存 ----------

    @Transactional
    public EntryWritten create(long userId, Long cardId, EntryConfig config, String scope) {
        if (cardId == null || cardId <= 0) {
            throw new BadRequestException("请提供卡片");
        }
        String targetScope = scope == null || scope.isBlank() ? "PRIVATE" : scope.trim();
        if (!SCOPES.contains(targetScope)) {
            throw new BadRequestException("非法入口范围: " + targetScope);
        }
        CardEntity card = cards.selectById(cardId);
        if (card == null) {
            throw new NotFoundException("卡片不存在");
        }
        if (!CardStatus.PUBLISHED.name().equals(card.getStatus())) {
            throw new BadRequestException("卡片未发布,暂不能创建入口");
        }
        boolean crossTheme = false;
        if (config != null && config.type() == EntryType.LINK_CARD && config.targetCardId() != null) {
            CardEntity target = cards.selectById(config.targetCardId());
            if (target == null) {
                throw new BadRequestException("目标卡片不存在");
            }
            if (!CardStatus.PUBLISHED.name().equals(target.getStatus())) {
                throw new BadRequestException("目标卡片未发布,暂不能链接");
            }
            // 精确 crossTheme（钉子①）：从实体算——任一 theme 缺失按 RelationGuard 同则不视为跨主题
            crossTheme = card.getTheme() != null && !card.getTheme().equals(target.getTheme());
        }

        // 钉子①：保存路径必调 EntryConfigValidator，越权/违规 → 400 清单，没有例外路径
        Set<Long> allowed = authorizedAssets.of(card.getCurrentVersionId()).keySet();
        List<String> violations = EntryConfigValidator.validate(config, SERVICE_WHITELIST, allowed, crossTheme);
        if (!violations.isEmpty()) {
            throw new EntryConfigViolationException(violations);
        }

        EntryEntity entry = new EntryEntity();
        entry.setCardId(cardId);
        entry.setName(config.name().trim());
        entry.setType(config.type().name());
        entry.setRelationLabel(config.relationLabel());
        entry.setTargetCardId(config.targetCardId());
        entry.setServiceType(config.serviceType());
        entry.setConfigJson(json(config));
        entry.setScope(targetScope);
        entry.setStatus("ACTIVE");
        entry.setVersion(1);
        entry.setAuthorId(userId);
        entry.setSort(0);
        entry.setTestTotal(0);
        entries.insert(entry);

        // RelationGuard 复检（双保险）：Guard 读 config_json 形状判定，非法映射 400 整体回滚
        try {
            relationGuard.validateCrossTheme(entry);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }

        if ("PUBLIC".equals(targetScope)) {
            queueReview(entry.getId());
        }
        return new EntryWritten(entry.getId(), targetScope, entry.getStatus());
    }

    // ---------- 试运行 ----------

    /**
     * 试运行 = 限额内真实执行一次。不入事务（explain 落 QUEUED 后立即异步执行，若包事务
     * 异步线程可能读不到未提交的 run 行）。
     */
    public TestReceipt test(long userId, long entryId) {
        EntryEntity entry = requireOwned(entryId, userId, "无权试运行该入口");
        if (EntryType.LINK_CARD.name().equals(entry.getType())) {
            throw new BadRequestException("链接类入口无需试运行");
        }
        if (!"ACTIVE".equals(entry.getStatus())) {
            throw new BadRequestException("入口已停用,不能试运行");
        }
        EntryConfig config = parseConfig(entry.getConfigJson());
        if (config == null || config.goal() == null || config.goal().isBlank()) {
            throw new BadRequestException("入口缺少试运行问题(goal)");
        }
        CardEntity card = cards.selectById(entry.getCardId());
        if (card == null || card.getCurrentVersionId() == null) {
            throw new BadRequestException("卡片没有可运行的版本");
        }
        // 无会话 run（P5-18）：不落 artifact 只回状态；serviceType 白名单/配额/卡已发布在 explain 内校验
        Long runId = explain.explain(userId, card.getCurrentVersionId(), config.goal(), "SIMPLE",
                null, null, null, entry.getServiceType());
        LambdaUpdateWrapper<EntryEntity> bump = new LambdaUpdateWrapper<EntryEntity>()
                .eq(EntryEntity::getId, entryId)
                .setSql("test_total = test_total + 1");
        entries.update(bump);
        Integer total = entries.selectById(entryId).getTestTotal();
        return new TestReceipt(runId, total == null ? 1 : total);
    }

    // ---------- 我的入口 ----------

    @Transactional(readOnly = true)
    public List<MineItem> mine(long userId) {
        List<EntryEntity> rows = entries.selectList(new LambdaQueryWrapper<EntryEntity>()
                .eq(EntryEntity::getAuthorId, userId)
                .orderByDesc(EntryEntity::getId));
        List<MineItem> items = new ArrayList<>(rows.size());
        for (EntryEntity entry : rows) {
            CardEntity card = entry.getCardId() == null ? null : cards.selectById(entry.getCardId());
            items.add(new MineItem(entry.getId(), entry.getName(), entry.getType(), entry.getServiceType(),
                    entry.getRelationLabel(), entry.getTargetCardId(), entry.getScope(), entry.getStatus(),
                    entry.getTestTotal() == null ? 0 : entry.getTestTotal(),
                    entry.getCardId() == null ? 0 : entry.getCardId(),
                    card == null ? "" : card.getTitle()));
        }
        return items;
    }

    // ---------- scope 双通道切换 ----------

    @Transactional
    public EntryWritten changeScope(long userId, long entryId, String scope) {
        String targetScope = scope == null || scope.isBlank() ? "" : scope.trim();
        if (!SCOPES.contains(targetScope)) {
            throw new BadRequestException("非法入口范围: " + scope);
        }
        EntryEntity entry = requireOwned(entryId, userId, "无权修改该入口");
        if (targetScope.equals(entry.getScope())) {
            return new EntryWritten(entryId, entry.getScope(), entry.getStatus()); // 同值幂等，不重复挂队列
        }
        entry.setScope(targetScope);
        entry.setUpdatedAt(OffsetDateTime.now());
        entries.updateById(entry);
        if ("PUBLIC".equals(targetScope)) {
            queueReview(entryId);
        }
        return new EntryWritten(entryId, targetScope, entry.getStatus());
    }

    // ---------- 内部 ----------

    private EntryEntity requireOwned(long entryId, long userId, String deniedMessage) {
        EntryEntity entry = entries.selectById(entryId);
        if (entry == null) {
            throw new NotFoundException("入口不存在");
        }
        if (entry.getAuthorId() == null || entry.getAuthorId() != userId) {
            throw new AccessDeniedException(deniedMessage);
        }
        return entry;
    }

    /** 公共入口挂审核队列（P1-4 ReviewService 同一消费面）：object_type=ENTRY, action=SUBMIT */
    private void queueReview(long entryId) {
        ReviewTaskEntity task = new ReviewTaskEntity();
        task.setObjectType("ENTRY");
        task.setObjectId(entryId);
        task.setAction("SUBMIT");
        task.setStatus(ReviewStatus.PENDING.name());
        reviewTasks.insert(task);
    }

    private EntryConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(configJson, EntryConfig.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("入口配置序列化失败", e);
        }
    }
}
