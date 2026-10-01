package com.ke.service.share;

import com.ke.service.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分享端点族（认证，FR-H01/H03/H05）：POST /api/shares 创建 201 {token,url}；
 * DELETE /api/shares/{token} 属主撤销 200（幂等）；POST /s/{token}/continue 接续副本 200（Task 31）。
 * 匿名一律 401（SecurityConfig 仅 GET /s/* permitAll，其余 anyRequest().authenticated() 兜底）。
 * 免登录浏览 GET /s/{token} 在 {@link SharePublicController}（permitAll，与认证面分离）。
 */
@RestController
public class ShareController {

    private final ShareService shares;

    public ShareController(ShareService shares) {
        this.shares = shares;
    }

    /** 创建分享请求：objectType MVP 仅 'SESSION'；nodeIds=勾选入快照的会话节点；title/summary 可选 */
    public record CreateShareRequest(String objectType, Long objectId, List<Long> nodeIds,
                                     String title, String summary) {
    }

    /** 创建：201 {token, url:/s/{token}}（校验失败 400 / 非属主 403 / 会话不存在 404，见 ShareService） */
    @PostMapping("/api/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateShareRequest req) {
        ShareService.ShareCreated created = shares.create(currentUserId(),
                new ShareService.CreateCommand(req.objectType(), req.objectId(), req.nodeIds(),
                        req.title(), req.summary()));
        return ApiResponse.ok(Map.of("token", created.token(), "url", created.url()));
    }

    /** 撤销：属主 200 {revoked:true}（已撤销再撤幂等 200）；他人 403；不存在 404 */
    @DeleteMapping("/api/shares/{token}")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable String token) {
        shares.revoke(currentUserId(), token);
        return ApiResponse.ok(Map.of("revoked", true));
    }

    /**
     * 接续副本（Task 31，FR-H05/A5，登录态）：按快照复制独立会话，返回 {sessionId, nodeCount}；
     * 200（动作语义，副本即普通会话，后续服务运行走既有 runs 端点）；匿名 401 / 撤销与不存在 404。
     * 差异提示（FR-H07 一期简化）由 GET /s/{token} 的 continueNotice 静态下发，本端点不重复计算。
     */
    @PostMapping("/s/{token}/continue")
    public ApiResponse<Map<String, Object>> continueShare(@PathVariable String token) {
        ShareService.ContinueResult result = shares.continueFrom(token, currentUserId());
        return ApiResponse.ok(Map.of("sessionId", result.sessionId(), "nodeCount", result.nodeCount()));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
