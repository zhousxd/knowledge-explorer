package com.ke.service.card;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ke.domain.enums.CardStatus;
import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.FavoriteEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.FavoriteMapper;
import com.ke.service.common.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏（FR-C10）：
 * - 仅 PUBLISHED 卡可收藏（草稿/不存在 → 404，与公开详情同则，不泄露存在性）；
 * - 幂等：重复收藏返回原收藏不出错（先查后插，UNIQUE(user_id, card_id) 兜底）；
 * - 收藏列表按 created_at DESC 分页（offset，page 从 1 起、size ≤ 50 默认 20），
 *   卡片元数据先页查 favorite 再 in 批量补齐 card（防 N+1）。
 */
@Service
public class FavoriteService {

    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 20;

    private final FavoriteMapper favorites;
    private final CardMapper cards;

    public FavoriteService(FavoriteMapper favorites, CardMapper cards) {
        this.favorites = favorites;
        this.cards = cards;
    }

    /** 收藏列表行：favoritedAt 即 favorite.created_at */
    public record FavoriteItem(long cardId, String title, String theme, String templateType,
                               String summaryText, OffsetDateTime favoritedAt) {
    }

    /** offset 分页契约（page 从 1 起；total 恒在），与工作台管理列表同形 */
    public record FavoritePage(List<FavoriteItem> items, long total, int page, int size) {
    }

    /**
     * 收藏：卡不存在或非 PUBLISHED → 404；重复收藏幂等（不出错）。恒返回 true。
     * 并发兜底（照 AuthService.autoRegister 模式）：check-then-insert 之间的竞争由
     * UNIQUE(user_id, card_id) 唯一约束兜底，插入冲突回落为「已有收藏」，幂等契约成立（不再 500）。
     */
    @Transactional
    public boolean favorite(long cardId, long userId) {
        requirePublished(cardId);
        Long existing = favorites.selectCount(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .eq(FavoriteEntity::getCardId, cardId));
        if (existing == null || existing == 0) {
            FavoriteEntity row = new FavoriteEntity();
            row.setUserId(userId);
            row.setCardId(cardId);
            try {
                favorites.insert(row);
            } catch (DataIntegrityViolationException e) {
                // 并发重复收藏：唯一约束兜底，回落返回已有收藏（幂等）
            }
        }
        return true;
    }

    /** 取消收藏：幂等（无收藏也返回 false，不出错） */
    @Transactional
    public boolean unfavorite(long cardId, long userId) {
        favorites.delete(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .eq(FavoriteEntity::getCardId, cardId));
        return false;
    }

    /** 详情 favorited 字段：userId 为 null（匿名）恒 false */
    @Transactional(readOnly = true)
    public boolean isFavorited(Long userId, long cardId) {
        if (userId == null) {
            return false;
        }
        Long count = favorites.selectCount(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .eq(FavoriteEntity::getCardId, cardId));
        return count != null && count > 0;
    }

    /** 我的收藏（created_at DESC）：offset 分页，size 夹取 [1,50]，默认 20 */
    @Transactional(readOnly = true)
    public FavoritePage page(long userId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        long total = favorites.selectCount(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId));
        List<FavoriteEntity> rows = favorites.selectList(new LambdaQueryWrapper<FavoriteEntity>()
                .eq(FavoriteEntity::getUserId, userId)
                .orderByDesc(FavoriteEntity::getCreatedAt)
                .last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize));

        Set<Long> cardIds = rows.stream().map(FavoriteEntity::getCardId).collect(Collectors.toSet());
        Map<Long, CardEntity> cardById = cardIds.isEmpty() ? Map.of()
                : cards.selectBatchIds(cardIds).stream()
                        .collect(Collectors.toMap(CardEntity::getId, Function.identity()));
        List<FavoriteItem> items = rows.stream()
                .map(f -> {
                    CardEntity c = cardById.get(f.getCardId());
                    // 卡随后被物理删除的兜底：收藏行残留时跳过，不让整页 500
                    if (c == null) {
                        return null;
                    }
                    return new FavoriteItem(c.getId(), c.getTitle(), c.getTheme(), c.getTemplateType(),
                            c.getSummaryText(), f.getCreatedAt());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return new FavoritePage(items, total, safePage, safeSize);
    }

    /** 与公开详情同则：不存在或非 PUBLISHED 一律 404（不泄露草稿/下架卡的存在性） */
    private void requirePublished(long cardId) {
        CardEntity card = cards.selectById(cardId);
        if (card == null || !CardStatus.PUBLISHED.name().equals(card.getStatus())) {
            throw new NotFoundException("卡片不存在");
        }
    }
}
