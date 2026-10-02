-- 卡片配图（二期图片功能·本地存储版）：文件落盘 ke.upload.dir（{yyyy}/{MM}/{uuid}.{ext}），
-- DB 只记元数据与存储相对路径；卡片 content_json.image 以 (id,url) 指向本表，url 形如 /api/images/{id}。
-- 不并入 knowledge_asset：该表语义是「引用来源」（书目/文章/音视频），配图是卡片媒体资源，生命周期互不相关。
CREATE TABLE card_image (
    id            BIGSERIAL PRIMARY KEY,
    storage_key   VARCHAR(120) NOT NULL UNIQUE,
    original_name VARCHAR(255),
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    created_by    BIGINT       REFERENCES ke_user (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
