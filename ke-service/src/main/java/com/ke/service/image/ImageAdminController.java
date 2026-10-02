package com.ke.service.image;

import com.ke.service.common.ApiResponse;
import com.ke.service.common.BadRequestException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 卡片配图上传（工作台端，二期图片功能）：POST /api/wb/images。
 * 角色与资产导入一致（EDITOR/OPERATOR 可写，CREATOR 可看）——由类级 @PreAuthorize 表达：
 * CREATOR 上传会被拒（403），与「配图是运营素材」的 RBAC 语义对齐。
 */
@RestController
@RequestMapping("/api/wb/images")
@PreAuthorize("hasAnyRole('EDITOR','OPERATOR')")
public class ImageAdminController {

    private final ImageService images;

    public ImageAdminController(ImageService images) {
        this.images = images;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageService.UploadedImage> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("上传文件为空");
        }
        return ApiResponse.ok(images.upload(file, currentUserId()));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
