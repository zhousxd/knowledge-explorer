package com.ke.service.image;

import com.ke.infra.entity.CardImageEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 卡片配图读取（探索端，匿名可看）：GET /api/images/{id}。
 * 与公开卡片详情同语义（01 文档：浏览无登录要求），放行规则在 SecurityConfig。
 * 输出原始图片字节（非 API envelope），Content-Type 取 DB 留档值，immutable 长缓存
 * （id 不可变：同名同 id 的内容永不改写，编辑换图即新 id）。
 */
@RestController
public class ImagePublicController {

    private final ImageService images;

    public ImagePublicController(ImageService images) {
        this.images = images;
    }

    @GetMapping("/api/images/{id:\\d+}")
    public ResponseEntity<FileSystemResource> serve(@PathVariable long id) {
        CardImageEntity entity = images.requireExisting(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(entity.getContentType()))
                .contentLength(entity.getSizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(new FileSystemResource(images.fileOf(entity)));
    }
}
