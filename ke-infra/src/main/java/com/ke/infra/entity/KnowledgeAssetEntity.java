package com.ke.infra.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * knowledge_asset 表映射（V1__core_schema.sql，FR-O01）：图书/文章/音视频知识单元。
 * source_meta / locator 为 JSONB 列，此处以 JSON 字符串读写（数据源 URL 已带
 * stringtype=unspecified，由 PG 推断目标类型）；license_expire 为 DATE（可空）。
 * created_at 有 DB 默认值 now()，插入时留 null 即可。
 */
@TableName("knowledge_asset")
public class KnowledgeAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String kind;            // book / article / audio / video（小写）
    private String title;
    private String sourceMeta;      // JSONB 字符串，可空
    private String locator;         // JSONB 字符串（章节/页码/时间片段）
    private String license;
    private LocalDate licenseExpire;
    private String contentExtract;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceMeta() { return sourceMeta; }
    public void setSourceMeta(String sourceMeta) { this.sourceMeta = sourceMeta; }
    public String getLocator() { return locator; }
    public void setLocator(String locator) { this.locator = locator; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public LocalDate getLicenseExpire() { return licenseExpire; }
    public void setLicenseExpire(LocalDate licenseExpire) { this.licenseExpire = licenseExpire; }
    public String getContentExtract() { return contentExtract; }
    public void setContentExtract(String contentExtract) { this.contentExtract = contentExtract; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
