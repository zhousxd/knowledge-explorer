package com.ke.service.card;

import com.fasterxml.jackson.databind.JsonNode;
import com.ke.service.card.dto.SourceRef;
import com.ke.service.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作台卡片写操作（FR-C07/C08）：建卡+首版、存新版本、送审、发布、下架、版本历史。
 * 写操作要求 CREATOR/EDITOR/OPERATOR（类级 @PreAuthorize）；发布仅 EDITOR/OPERATOR
 * （方法级覆盖，越权由 GlobalExceptionHandler 转 403 envelope）。
 * 请求形态（sources 契约）：content 为内嵌 JSON 对象，sources 为来源数组（citations[n]
 * 为指向 sources 的 1-based 索引）。
 */
@RestController
@RequestMapping("/api/wb/cards")
@PreAuthorize("hasAnyRole('CREATOR','EDITOR','OPERATOR')")
public class CardAdminController {

    private final CardService cards;

    public CardAdminController(CardService cards) { this.cards = cards; }

    public record CreateCardReq(@NotBlank String theme,
                                @NotBlank String templateType,
                                @NotBlank @Size(max = 120) String title,
                                @NotNull JsonNode content,
                                @Valid List<SourceRef> sources) {
    }

    public record SaveContentReq(@NotNull JsonNode content,
                                 @Valid List<SourceRef> sources) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateCardReq req) {
        Long cardId = cards.create(req.theme(), req.templateType(), req.title(),
                req.content(), req.sources(), currentUserId());
        return ApiResponse.ok(Map.of("cardId", cardId));
    }

    @PutMapping("/{id}/content")
    public ApiResponse<Map<String, Object>> saveContent(@PathVariable long id, @Valid @RequestBody SaveContentReq req) {
        int versionNo = cards.saveContent(id, req.content(), req.sources(), currentUserId());
        return ApiResponse.ok(Map.of("versionNo", versionNo));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable long id) {
        var card = cards.submit(id);
        return ApiResponse.ok(Map.of("cardId", card.getId(), "status", card.getStatus()));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('EDITOR','OPERATOR')")
    public ApiResponse<Map<String, Object>> publish(@PathVariable long id) {
        var card = cards.publish(id);
        return ApiResponse.ok(Map.of("cardId", card.getId(), "status", card.getStatus()));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable long id) {
        var card = cards.disable(id);
        return ApiResponse.ok(Map.of("cardId", card.getId(), "status", card.getStatus()));
    }

    /** 版本历史：versionNo 倒序，含创建人昵称与时间 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<CardService.VersionItem>> versions(@PathVariable long id) {
        return ApiResponse.ok(cards.versionsOf(id));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
