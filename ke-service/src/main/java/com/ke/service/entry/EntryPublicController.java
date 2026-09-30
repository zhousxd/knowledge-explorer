package com.ke.service.entry;

import com.ke.service.common.ApiResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探索端入口读取（FR-E02）：认证即可（任何已登录用户，无角色要求）。
 * 写接口（CRUD/试运行/自然语言草稿）由 Phase 6（Task 26-31）增量补齐。
 */
@RestController
public class EntryPublicController {

    private final EntryService entries;

    public EntryPublicController(EntryService entries) { this.entries = entries; }

    @GetMapping("/api/cards/{id}/entries")
    public ApiResponse<EntryService.EntryGroup> list(@PathVariable long id) {
        return ApiResponse.ok(entries.entriesOf(id, currentUserId()));
    }

    /** P0-4 模式：JwtAuthFilter 把 userId 字符串存为 principal name */
    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
