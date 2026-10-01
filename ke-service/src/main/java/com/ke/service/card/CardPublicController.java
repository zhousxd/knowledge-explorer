package com.ke.service.card;

import com.ke.service.common.ApiResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 探索端卡片读取（FR-C01/C02）：仅 PUBLISHED 可见；列表按 (sort,id) keyset 游标分页，
 * q 对 title + summary_text ILIKE（pg_trgm GIN 索引已就位）。认证即可，无角色要求。
 * 详情端点已匿名放行（SecurityConfig）：身份可选——认证带 token 时补 favorited
 * （FR-C10），匿名（SecurityContext 无认证或 AnonymousAuthenticationToken）恒 false。
 */
@RestController
public class CardPublicController {

    private final CardService cards;
    private final FavoriteService favorites;

    public CardPublicController(CardService cards, FavoriteService favorites) {
        this.cards = cards;
        this.favorites = favorites;
    }

    @GetMapping("/api/cards")
    public ApiResponse<CardService.CardPage> list(@RequestParam(required = false) String theme,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(cards.list(theme, q, cursor));
    }

    @GetMapping("/api/cards/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        CardService.CardDetail d = cards.detail(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", d.id());
        body.put("theme", d.theme());
        body.put("templateType", d.templateType());
        body.put("title", d.title());
        body.put("versionNo", d.versionNo());
        body.put("content", d.content());
        body.put("sources", d.sources());
        body.put("updatedAt", d.updatedAt());
        body.put("favorited", favorites.isFavorited(currentUserIdOrNull(), d.id()));
        return ApiResponse.ok(body);
    }

    /** 匿名放行端点的可选身份：无认证或 AnonymousAuthenticationToken → null（favorited=false） */
    private static Long currentUserIdOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return Long.parseLong(auth.getName());
    }
}
