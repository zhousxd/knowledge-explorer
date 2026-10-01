package com.ke.service.entry;

import com.ke.service.common.BadRequestException;

import java.util.List;

/**
 * 入口配置硬约束违规（FR-N07，Task 28）：保存路径（POST /api/entries、PUT scope 后的复检等）
 * 必调 {@link EntryConfigValidator#validate}，violations 非空即抛本异常——保存端没有草稿期的
 * 「自动收窄」宽限，白名单是硬边界（钉子①：没有例外路径）。GlobalExceptionHandler 映射
 * 400 envelope，消息为逐条违规的清单拼接（「; 」分隔，顺序即校验器输出顺序）。
 */
public class EntryConfigViolationException extends BadRequestException {

    private final List<String> violations;

    public EntryConfigViolationException(List<String> violations) {
        super(violations == null || violations.isEmpty()
                ? "入口配置不合法"
                : String.join("; ", violations));
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** 逐条违规清单（供结构化消费方使用；envelope message 已拼接） */
    public List<String> violations() {
        return violations;
    }
}
