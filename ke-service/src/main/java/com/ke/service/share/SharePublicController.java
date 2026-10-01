package com.ke.service.share;

import com.ke.service.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免登录分享页数据端点（FR-H04/H06）：GET /s/{token} 匿名可读（SecurityConfig 对 GET /s/* permitAll），
 * 返回分享快照（A4 数据面：接收方所见=生成时快照，撤销/不存在统一 404「分享不存在」不泄露存在性）。
 * 注意与认证面分离：POST /s/{token}/continue（Task 31 接续）不在本控制器、不受 permitAll。
 */
@RestController
public class SharePublicController {

    private final ShareService shares;

    public SharePublicController(ShareService shares) {
        this.shares = shares;
    }

    /** 匿名浏览：200 ApiResponse&lt;ShareView&gt;（token/title/summary/snapshot/createdAt） */
    @GetMapping("/s/{token}")
    public ApiResponse<ShareService.ShareView> view(@PathVariable String token) {
        return ApiResponse.ok(shares.viewPublic(token));
    }
}
