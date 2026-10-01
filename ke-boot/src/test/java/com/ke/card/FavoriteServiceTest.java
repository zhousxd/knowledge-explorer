package com.ke.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.ke.infra.entity.CardEntity;
import com.ke.infra.entity.FavoriteEntity;
import com.ke.infra.mapper.CardMapper;
import com.ke.infra.mapper.FavoriteMapper;
import com.ke.service.card.FavoriteService;

/**
 * 收藏幂等的并发兜底（纯单元测试，Mockito mock mapper，不起 Spring/DB）：
 * check-then-insert 之间的并发竞争下，insert 撞 UNIQUE(user_id, card_id) 抛
 * DataIntegrityViolationException → 方法不抛、回落返回 favorited=true（幂等契约成立，不再 500）。
 * 并发时序本身难以集成直测，按约定以单测级覆盖该分支（IT 的幂等路径见 FavoriteIT）。
 */
class FavoriteServiceTest {

    private FavoriteMapper favorites;
    private CardMapper cards;
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        favorites = mock(FavoriteMapper.class);
        cards = mock(CardMapper.class);
        service = new FavoriteService(favorites, cards);
    }

    private CardEntity published(long id) {
        CardEntity card = new CardEntity();
        card.setId(id);
        card.setStatus("PUBLISHED");
        return card;
    }

    @Test
    void insertUniqueConflictFallsBackToIdempotentTrue() {
        when(cards.selectById(9L)).thenReturn(published(9L));
        when(favorites.selectCount(any())).thenReturn(0L);
        when(favorites.insert(any(FavoriteEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_user_card 冲突(并发重复收藏)"));

        // 单次调用同时断言「不抛」与「返回 favorited=true」
        boolean favorited = service.favorite(9L, 7L);
        assertThat(favorited).isTrue();
    }

    @Test
    void normalInsertStillReturnsTrue() {
        when(cards.selectById(9L)).thenReturn(published(9L));
        when(favorites.selectCount(any())).thenReturn(0L);
        when(favorites.insert(any(FavoriteEntity.class))).thenReturn(1);

        assertThat(service.favorite(9L, 7L)).isTrue();
    }

    @Test
    void alreadyFavoritedSkipsInsertAndReturnsTrue() {
        when(cards.selectById(9L)).thenReturn(published(9L));
        when(favorites.selectCount(any())).thenReturn(1L);

        assertThat(service.favorite(9L, 7L)).isTrue();
    }
}
