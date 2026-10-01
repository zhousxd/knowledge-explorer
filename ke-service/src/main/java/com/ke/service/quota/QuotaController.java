package com.ke.service.quota;

import com.ke.service.common.ApiResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 个人配额查询（FR-S04）：GET /api/me/quota → {used, limit, remaining, resetAt}。
 * resetAt 为当日 24:00（次日零点，本地时区，ISO-8601 序列化）；认证由 SecurityConfig 兜底。
 */
@RestController
public class QuotaController {

    private final QuotaService quota;

    /** resetAt 序列化为 ISO-8601（如 2026-10-01T00:00:00+08:00） */
    public record QuotaView(int used, int limit, int remaining, OffsetDateTime resetAt) {
    }

    public QuotaController(QuotaService quota) {
        this.quota = quota;
    }

    @GetMapping("/api/me/quota")
    public ApiResponse<QuotaView> me() {
        long userId = currentUserId();
        OffsetDateTime resetAt = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        return ApiResponse.ok(new QuotaView(quota.used(userId), quota.dailyLimit(),
                quota.remaining(userId), resetAt));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
