package com.ke.service.entry;

import com.ke.domain.entry.EntryConfig;
import com.ke.service.common.ApiResponse;
import com.ke.service.common.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 入口写端点族（FR-N02–N05/N07，Task 28），认证即可（作者语义在服务层判 403，匿名 401 由
 * SecurityConfig 兜底）：
 * <ul>
 *   <li>POST /api/entries {cardId, config, scope} → 201 {entryId, scope, status}
 *       （PRIVATE 即 ACTIVE；PUBLIC 前置审核：status=PENDING + 挂审核队列，approve → ACTIVE
 *       才对他人可见，reject → DISABLED；违规 400 清单 envelope）；</li>
 *   <li>POST /api/entries/{id}/test → 202 {runId, testTotal}（真实执行一次，配额内；
 *       LINK_CARD 400；非作者 403；轮询走 GET /api/agent/runs/{runId}）；</li>
 *   <li>GET /api/entries/mine → 我的入口列表（状态/所属卡题/试运行次数）；</li>
 *   <li>PUT /api/entries/{id}/scope {scope} → 200 {entryId, scope, status}
 *       （PRIVATE→PUBLIC 挂审核，PUBLIC→PRIVATE 直接，仅作者）。</li>
 * </ul>
 */
@RestController
public class EntryMutationController {

    private final EntryMutationService mutations;

    public EntryMutationController(EntryMutationService mutations) {
        this.mutations = mutations;
    }

    /** config 为 EntryConfig 形状 JSON（type 枚举 name、assetScope 为资产 id 数组） */
    public record CreateRequest(Long cardId, EntryConfig config, String scope) {
    }

    public record ScopeRequest(String scope) {
    }

    @PostMapping("/api/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EntryMutationService.EntryWritten> create(@RequestBody CreateRequest request) {
        if (request == null) {
            throw new BadRequestException("请提供入口配置");
        }
        return ApiResponse.ok(mutations.create(currentUserId(), request.cardId(), request.config(),
                request.scope()));
    }

    @PostMapping("/api/entries/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> test(@PathVariable long id) {
        EntryMutationService.TestReceipt receipt = mutations.test(currentUserId(), id);
        return ApiResponse.ok(Map.of("runId", receipt.runId(), "testTotal", receipt.testTotal()));
    }

    @GetMapping("/api/entries/mine")
    public ApiResponse<List<EntryMutationService.MineItem>> mine() {
        return ApiResponse.ok(mutations.mine(currentUserId()));
    }

    @PutMapping("/api/entries/{id}/scope")
    public ApiResponse<EntryMutationService.EntryWritten> changeScope(@PathVariable long id,
                                                                      @RequestBody ScopeRequest request) {
        if (request == null) {
            throw new BadRequestException("请提供入口范围");
        }
        return ApiResponse.ok(mutations.changeScope(currentUserId(), id, request.scope()));
    }

    /** P0-4 模式：JwtAuthFilter 把 userId 字符串存为 principal name */
    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
