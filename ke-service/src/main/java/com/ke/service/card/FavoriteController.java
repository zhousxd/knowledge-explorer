package com.ke.service.card;

import com.ke.service.common.ApiResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 收藏（FR-C10）：认证即可（EXPLORER 角色即可，无写路径角色要求）。
 * - POST /api/cards/{id}/favorite：仅 PUBLISHED 卡可收藏（否则 404），幂等；
 * - DELETE /api/cards/{id}/favorite：取消收藏，幂等；
 * - GET /api/me/favorites：我的收藏（created_at DESC，page 从 1 起、size ≤ 50 默认 20）。
 */
@RestController
public class FavoriteController {

    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) { this.favorites = favorites; }

    @PostMapping("/api/cards/{id}/favorite")
    public ApiResponse<Map<String, Object>> favorite(@PathVariable long id) {
        favorites.favorite(id, currentUserId());
        return ApiResponse.ok(Map.of("favorited", true));
    }

    @DeleteMapping("/api/cards/{id}/favorite")
    public ApiResponse<Map<String, Object>> unfavorite(@PathVariable long id) {
        favorites.unfavorite(id, currentUserId());
        return ApiResponse.ok(Map.of("favorited", false));
    }

    @GetMapping("/api/me/favorites")
    public ApiResponse<FavoriteService.FavoritePage> list(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(favorites.page(currentUserId(), page, size));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
