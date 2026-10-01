package com.ke.service.entry;

import com.ke.service.common.ApiResponse;
import com.ke.service.common.BadRequestException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然语言入口草稿（FR-N01/N02，Task 27）：POST /api/entries/nl-draft {cardId, text}。
 * 认证即可（任何角色含 EXPLORER 都可为自己起草私人入口，SecurityConfig
 * anyRequest().authenticated() 兜底 401）；草稿为无状态产物，不落库。
 * 400=text 超限/空白或卡未发布；404=卡不存在；网关异常由全局兜底 500 envelope。
 */
@RestController
public class DraftController {

    private final EntryDraftService drafts;

    public DraftController(EntryDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/api/entries/nl-draft")
    public ApiResponse<EntryDraftService.DraftResult> draft(@RequestBody DraftRequest request) {
        if (request == null || request.cardId() == null) {
            throw new BadRequestException("请提供卡片");
        }
        return ApiResponse.ok(drafts.draft(currentUserId(), request.cardId(), request.text()));
    }

    /** P0-4 模式：JwtAuthFilter 把 userId 字符串存为 principal name */
    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    record DraftRequest(Long cardId, String text) {
    }
}
