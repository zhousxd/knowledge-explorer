package com.ke.service.review;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ke.service.common.ApiResponse;
import com.ke.service.review.ReviewService.ReviewItem;
import com.ke.service.review.ReviewService.ReviewPage;

/**
 * 审核队列（FR-O03）：仅编辑/运营可见可裁决（类级 @PreAuthorize，越权 → 403 envelope）。
 * GET /api/wb/reviews?status=PENDING&objectType=&page=&size= 队列查询（offset 分页，
 * 返回 {items,total,page,size}，size ≤100 默认 20）；
 * POST /{id}/approve（notes 可选）、POST /{id}/reject（notes 必填，@Valid 兜底 400）。
 */
@RestController
@RequestMapping("/api/wb/reviews")
@PreAuthorize("hasAnyRole('EDITOR','OPERATOR')")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) { this.reviews = reviews; }

    public record ApproveReq(String notes) {
    }

    public record RejectReq(@NotBlank String notes) {
    }

    @GetMapping
    public ApiResponse<ReviewPage> list(@RequestParam(defaultValue = "PENDING") String status,
                                        @RequestParam(required = false) String objectType,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(reviews.page(status, objectType, page, size));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<ReviewItem> approve(@PathVariable long id,
                                           @RequestBody(required = false) ApproveReq req) {
        return ApiResponse.ok(reviews.approve(id, currentUserId(), req == null ? null : req.notes()));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<ReviewItem> reject(@PathVariable long id, @Valid @RequestBody RejectReq req) {
        return ApiResponse.ok(reviews.reject(id, currentUserId(), req.notes()));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
