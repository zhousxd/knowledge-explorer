package com.ke.service.entry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ke.domain.entry.RelationType;
import com.ke.domain.enums.CardStatus;
import com.ke.domain.enums.EntryType;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.EntryEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.EntryMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 卡片入口（FR-E02/C09/N07）：
 * - 读路径（探索端）：仅 PUBLISHED 卡可见（否则 404 不泄露）；仅 ACTIVE 入口；
 *   PUBLIC 全可见，PRIVATE 仅创建者本人可见（FR-N04 私人入口仅个人空间生效）；
 *   按 (sort,id) 升序，前 {@value #DEFAULT_VISIBLE} 个进 defaultEntries，其余进 folded；
 *   每项带 mine（author_id==当前用户）；config_json 不返回（Phase 6 试运行按需提供）。
 * - 写路径白名单守卫 {@link #checkInsertable}：type ∈ EntryType、scope/status 枚举白名单、
 *   LINK_CARD 必填 relation_label 且取值 ∈ RelationType 四词。本任务无写端点，
 *   Phase 6 的 CRUD/试运行/自然语言草稿（Task 26-31）写入前必须先过此守卫。
 */
@Service
public class EntryService {

    /** FR-E02：入口默认最多展示 5 个，其余折叠 */
    static final int DEFAULT_VISIBLE = 5;

    private static final Set<String> ENTRY_TYPES = Arrays.stream(EntryType.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());
    private static final Set<String> SCOPES = Set.of("PRIVATE", "PUBLIC");     // V2 注释：entry.scope 'PRIVATE/PUBLIC'
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");  // V1 默认 ACTIVE

    private final CardMapper cards;
    private final EntryMapper entries;

    public EntryService(CardMapper cards, EntryMapper entries) {
        this.cards = cards;
        this.entries = entries;
    }

    public record EntryItem(Long id, String name, String type, String relationLabel, Long targetCardId,
                            String serviceType, String scope, String status, boolean mine) {
    }

    public record EntryGroup(Long cardId, List<EntryItem> defaultEntries, List<EntryItem> folded) {
    }

    /** 探索端卡片入口列表（认证即可，无角色要求）；卡不存在或未发布一律 404 */
    @Transactional(readOnly = true)
    public EntryGroup entriesOf(long cardId, long viewerId) {
        CardEntity card = cards.selectById(cardId);
        if (card == null || !CardStatus.PUBLISHED.name().equals(card.getStatus())) {
            throw new NotFoundException("卡片不存在");
        }
        List<EntryEntity> rows = entries.selectList(new LambdaQueryWrapper<EntryEntity>()
                .eq(EntryEntity::getCardId, cardId)
                .eq(EntryEntity::getStatus, "ACTIVE")
                .and(w -> w.eq(EntryEntity::getScope, "PUBLIC").or().eq(EntryEntity::getAuthorId, viewerId))
                .orderByAsc(EntryEntity::getSort, EntryEntity::getId));
        List<EntryItem> items = rows.stream()
                .map(e -> new EntryItem(e.getId(), e.getName(), e.getType(), e.getRelationLabel(),
                        e.getTargetCardId(), e.getServiceType(), e.getScope(), e.getStatus(),
                        e.getAuthorId() != null && e.getAuthorId() == viewerId))
                .toList();
        int cut = Math.min(DEFAULT_VISIBLE, items.size());
        return new EntryGroup(cardId, items.subList(0, cut), items.subList(cut, items.size()));
    }

    /**
     * 写入白名单守卫（FR-N07/C09）：type 必须 ∈ {@link EntryType}；scope ∈ PRIVATE/PUBLIC；
     * status ∈ ACTIVE/DISABLED；LINK_CARD 必填 relation_label；任何非空 relation_label 都必须
     * ∈ {@link RelationType} 四词。非法即 400（{@link BadRequestException}）。
     */
    public static void checkInsertable(EntryEntity entry) {
        if (entry.getType() == null || !ENTRY_TYPES.contains(entry.getType())) {
            throw new BadRequestException("非法入口类型: " + entry.getType());
        }
        if (entry.getScope() == null || !SCOPES.contains(entry.getScope())) {
            throw new BadRequestException("非法入口范围: " + entry.getScope());
        }
        if (entry.getStatus() == null || !STATUSES.contains(entry.getStatus())) {
            throw new BadRequestException("非法入口状态: " + entry.getStatus());
        }
        if (EntryType.LINK_CARD.name().equals(entry.getType())
                && (entry.getRelationLabel() == null || entry.getRelationLabel().isBlank())) {
            throw new BadRequestException("链接入口必须携带关系标签");
        }
        if (entry.getRelationLabel() != null && !RelationType.isRelationLabel(entry.getRelationLabel())) {
            throw new BadRequestException("非法关系标签: " + entry.getRelationLabel());
        }
    }
}
