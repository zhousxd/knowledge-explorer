package com.ke.service.image;

import com.ke.infra.entity.CardImageEntity;
import com.ke.infra.mapper.CardImageMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 卡片配图（二期图片功能·本地存储版）：
 * - 上传：魔数嗅探（客户端 Content-Type 不可信）→ 白名单 JPG/PNG/GIF/WebP，≤5MB；
 *   文件按 {yyyy}/{MM}/{uuid}.{ext} 落盘 ke.upload.dir（无用户可控字符，杜绝路径穿越与重名覆盖），
 *   DB card_image 只记元数据。
 * - 内容把关：卡片 content_json.image 引用前须指向真实 card_image 行且 url 与 id 一致
 *   （{@link #requireUsable}），防止自造 URL/悬挂引用进版本库。
 * - 读取：按 id 回源磁盘（key 取自 DB，非 URL 拼接），静态不可变资源 → Cache-Control immutable。
 * - 不做物理删除：配图是内容资产，移除只发生在编辑层（content 去掉 image 字段）。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    /** 与 spring.servlet.multipart.max-file-size 同值的双保险（multipart 限额先行拦截） */
    static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;
    private static final String URL_PREFIX = "/api/images/";

    /** 魔数签名 → (扩展名, Content-Type)。客户端 Content-Type 不可信，一律以文件头为准。 */
    private static final List<Sniffed> TYPES = List.of(
            new Sniffed("jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            new Sniffed("png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
            new Sniffed("gif", "image/gif", new byte[] {'G', 'I', 'F', '8'}),
            // WebP 是 RIFF 容器：0-3 "RIFF" + 8-11 "WEBP"（4-7 为文件长度，签名中以 0 表示不关心）
            new Sniffed("webp", "image/webp",
                    new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}));

    private final CardImageMapper images;
    private final Path root;

    public ImageService(CardImageMapper images, @Value("${ke.upload.dir}") String uploadDir) {
        this.images = images;
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** 上传结果：url 即探索端 <img> 直接可用的规范地址 */
    public record UploadedImage(long id, String url, String originalName, String contentType, long sizeBytes) {
    }

    public UploadedImage upload(MultipartFile file, long userId) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("上传文件为空");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BadRequestException("图片不能超过 5MB");
        }
        byte[] head = headBytes(file);
        Sniffed sniffed = sniff(head);
        if (sniffed == null) {
            throw new BadRequestException("仅支持 JPG/PNG/GIF/WebP 图片");
        }
        String ext = sniffed.extension();
        String contentType = sniffed.contentType();
        LocalDate now = LocalDate.now();
        String storageKey = "%04d/%02d/%s.%s".formatted(now.getYear(), now.getMonthValue(),
                UUID.randomUUID().toString().replace("-", ""), ext);
        Path target = resolveSafely(storageKey);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                // 不带 REPLACE_EXISTING：目标已存在即抛 FileAlreadyExistsException，绝不静默覆盖
                Files.copy(in, target);
            }
        } catch (IOException e) {
            log.error("图片落盘失败 storageKey={}", storageKey, e);
            throw new BadRequestException("图片保存失败，请重试");
        }
        CardImageEntity entity = new CardImageEntity();
        entity.setStorageKey(storageKey);
        entity.setOriginalName(safeOriginalName(file.getOriginalFilename()));
        entity.setContentType(contentType);
        entity.setSizeBytes(file.getSize());
        entity.setCreatedBy(userId);
        images.insert(entity);
        return new UploadedImage(entity.getId(), URL_PREFIX + entity.getId(),
                entity.getOriginalName(), contentType, file.getSize());
    }

    /** 卡片写路径配图把关：id 真实存在且 url 与 id 一致（url 形状已由 domain Pattern 保证） */
    public void requireUsable(long id, String url) {
        if (images.selectById(id) == null || !url.equals(URL_PREFIX + id)) {
            throw new BadRequestException("配图不存在或已失效");
        }
    }

    /** 探索端读取：按 DB 存储键回源；返回磁盘路径供控制器流式输出 */
    public CardImageEntity requireExisting(long id) {
        CardImageEntity entity = images.selectById(id);
        if (entity == null) {
            throw new NotFoundException("图片不存在");
        }
        resolveSafely(entity.getStorageKey());
        return entity;
    }

    public Path fileOf(CardImageEntity entity) {
        return resolveSafely(entity.getStorageKey());
    }

    /** 存储键由服务端生成、DB 读取，理论不可控；resolve+startsWith 作为纵深防御 */
    private Path resolveSafely(String storageKey) {
        if (storageKey == null || storageKey.startsWith("/") || storageKey.contains("..")) {
            throw new NotFoundException("图片不存在");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new NotFoundException("图片不存在");
        }
        return resolved;
    }

    /** 文件头 12 字节足够覆盖全部白名单魔数（WebP 需要 RIFF…WEBP 的 0-8 与 8-12） */
    private static byte[] headBytes(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(12);
        } catch (IOException e) {
            throw new BadRequestException("文件读取失败");
        }
    }

    /** 命中返回签名，未命中返回 null */
    private static Sniffed sniff(byte[] head) {
        for (Sniffed candidate : TYPES) {
            if (candidate.matches(head)) {
                return candidate;
            }
        }
        return null;
    }

    /** 原始文件名仅留档：截断 + 只留文件名部分，不参与任何寻址 */
    private static String safeOriginalName(String original) {
        if (original == null || original.isBlank()) {
            return null;
        }
        String name = original.substring(original.lastIndexOf('/') + 1)
                .substring(original.lastIndexOf('\\') + 1);
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** 魔数签名值对象：prefix 中 0 字节表示「不关心」，其余逐位精确比较 */
    private record Sniffed(String extension, String contentType, byte[] prefix) {
        boolean matches(byte[] head) {
            if (head.length < prefix.length) {
                return false;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (prefix[i] != 0 && head[i] != prefix[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
