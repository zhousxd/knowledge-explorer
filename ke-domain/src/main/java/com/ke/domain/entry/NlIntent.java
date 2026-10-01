package com.ke.domain.entry;

import java.util.Locale;
import java.util.Optional;

/**
 * 自然语言新增入口的四类白名单意图（FR-N01 / 02 §5.3）：用户一句话先经路由档小模型
 * 分类，结果只允许落在这四类之一——非自由编排；预订/购买/新工具/越权数据等不支持的
 * 请求一律 {@link #OUT_OF_SCOPE}（由调用方礼貌拒绝并给替代建议）。
 */
public enum NlIntent {
    LINK_CARD, EXPLAIN, COMPARE, OUT_OF_SCOPE;

    /**
     * 模型输出的意图名解析：trim + upper 后精确匹配枚举名。
     * null / 空白 / 白名单之外的任何值（含自然语言应答、非法枚举）→ {@link Optional#empty()}，
     * 由调用方兜底 {@link #OUT_OF_SCOPE}——安全侧错报优于漏报：不确定的请求宁可礼貌拒绝，
     * 不可误入讲解/比较通道产生误导性产出。
     */
    public static Optional<NlIntent> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
