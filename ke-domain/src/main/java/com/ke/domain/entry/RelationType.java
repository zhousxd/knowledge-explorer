package com.ke.domain.entry;

import java.util.Arrays;
import java.util.List;

/**
 * 卡片入口的四类关系词（FR-C09）：入口副标题必须以其开头（渲染前缀由前端执行），
 * 后端保证 relation_label 只能取这四个中文标签——labels() 即唯一合法取值来源（FR-N07 白名单的一部分）。
 */
public enum RelationType {
    DEEPEN("深入了解"), RELATED("相关联"), COMPARE("相比较"), PRACTICE("去实践");

    private final String label;

    RelationType(String label) { this.label = label; }

    public String label() { return label; }

    /** 四类关系词的中文标签（库内 relation_label 与响应 relationLabel 均存中文） */
    public static List<String> labels() {
        return Arrays.stream(values()).map(RelationType::label).toList();
    }

    /** 关系词白名单判断：null / 空白 / 四词之外一律 false */
    public static boolean isRelationLabel(String value) {
        return value != null && Arrays.stream(values()).anyMatch(r -> r.label.equals(value));
    }
}
