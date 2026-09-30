package com.ke.domain.card.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 四模板 content_json 写前校验（FR-C03/C04/C05/C06）。
 * 「工作台保存」「卡片详情 API」在写入/外发前一律经 {@link #parseAndValidate} 把关：
 * Jackson 反序列化（容忍未知字段，向前兼容）→ Jakarta Bean Validation → 额外结构规则。
 */
public final class CardContentValidator {

    /** 未知字段忽略而非报错，保证旧数据读入与新字段加入的向前兼容。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private CardContentValidator() {
    }

    /**
     * 解析并校验 content_json。
     *
     * @param templateType 大小写不敏感的 "TEXT" | "COMPARE" | "TIMELINE" | "TASK"
     * @return 对应模板的 record（实现 {@link CardContent}）
     * @throws InvalidCardContentException JSON 不可解析、模板类型未知或存在约束违反时
     */
    public static CardContent parseAndValidate(String templateType, String json) {
        Class<? extends CardContent> targetType = resolveTarget(templateType);
        CardContent content = readValue(json, targetType);

        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<? extends CardContent> violation : VALIDATOR.validate(content)) {
            errors.add(violation.getPropertyPath() + ": " + violation.getMessage());
        }
        checkExtraRules(content, errors);
        if (!errors.isEmpty()) {
            throw new InvalidCardContentException(String.join("; ", errors));
        }
        return content;
    }

    private static Class<? extends CardContent> resolveTarget(String templateType) {
        if (templateType == null || templateType.isBlank()) {
            throw new InvalidCardContentException("模板类型缺失");
        }
        return switch (templateType.trim().toUpperCase(Locale.ROOT)) {
            case "TEXT" -> TextCardContent.class;
            case "COMPARE" -> CompareCardContent.class;
            case "TIMELINE" -> TimelineCardContent.class;
            case "TASK" -> TaskCardContent.class;
            default -> throw new InvalidCardContentException("未知模板类型: " + templateType);
        };
    }

    private static CardContent readValue(String json, Class<? extends CardContent> targetType) {
        try {
            return MAPPER.readValue(json, targetType);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new InvalidCardContentException("content_json 解析失败: " + firstLine(e.getMessage()));
        }
    }

    /** 结构对齐 02 §4.3 之外的派生规则：对比卡矩阵形状、引用编号非负。 */
    private static void checkExtraRules(CardContent content, List<String> errors) {
        if (content instanceof CompareCardContent c) {
            checkCompareCells(c, errors);
            checkCitations("citations", c.citations(), errors);
        } else if (content instanceof TextCardContent t) {
            if (t.sections() != null) {
                for (int i = 0; i < t.sections().size(); i++) {
                    checkCitations("sections[" + i + "].citations", t.sections().get(i).citations(), errors);
                }
            }
        } else if (content instanceof TimelineCardContent tl) {
            if (tl.events() != null) {
                for (int i = 0; i < tl.events().size(); i++) {
                    checkCitations("events[" + i + "].citations", tl.events().get(i).citations(), errors);
                }
            }
        }
        // TaskCardContent 的 record 结构（02 §4.3）不含引用列表，无需检查
    }

    private static void checkCompareCells(CompareCardContent c, List<String> errors) {
        int expectedRows = c.dimensions() == null ? 0 : c.dimensions().size();
        int expectedCols = c.objects() == null ? 0 : c.objects().size();
        if (c.cells() == null) {
            return; // 空值已由 @NotEmpty 报告
        }
        if (c.cells().size() != expectedRows) {
            errors.add("cells: 行数(" + c.cells().size() + ") 必须等于维度数(" + expectedRows + ")");
            return;
        }
        for (int i = 0; i < c.cells().size(); i++) {
            List<String> row = c.cells().get(i);
            if (row == null || row.size() != expectedCols) {
                errors.add("cells[" + i + "]: 行内列数(" + (row == null ? 0 : row.size())
                    + ") 必须等于对象数(" + expectedCols + ")");
            }
        }
    }

    private static void checkCitations(String path, List<Integer> citations, List<String> errors) {
        if (citations == null) {
            return;
        }
        for (int i = 0; i < citations.size(); i++) {
            Integer no = citations.get(i);
            if (no != null && no < 0) {
                errors.add(path + ": 引用编号必须 ≥ 0, 实际为 " + no);
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }
}
