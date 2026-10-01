package com.ke.domain.entry;

import com.ke.domain.enums.EntryType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 入口配置硬约束校验（FR-N07 白名单代码层落点，Task 27）：纯函数，返回中文违规清单
 * （空 = 通过），不抛异常——调用方（草稿抽取/入口保存）自行决定 violations 的呈现方式。
 * 逐条规则（与 EntryConfigValidatorTest 穷举一一对应）：
 * <ul>
 *   <li>name 非空且 ≤60 字；type 非空（缺失时只报此一条，后续类型分支短路防误报叠加）。</li>
 *   <li>serviceType：AGENT_SERVICE/COMPARE 必填且 ∈ allowedServiceTypes（越白名单拒）；
 *       LINK_CARD 必须为 null（「LINK_CARD 入口不应携带服务」）。</li>
 *   <li>assetScope：AGENT_SERVICE/COMPARE 非空且 ⊆ allowedAssetScope（越权逐个列出：
 *       「资料范围越权:资产 N 不在授权集」）；LINK_CARD 须空。</li>
 *   <li>targetCardId：LINK_CARD 必填；其他类型须 null。</li>
 *   <li>relationLabel/why/source 仅约束 LINK_CARD，跨主题与否由调用方传入 {@code crossTheme}
 *       （入口所属卡与目标卡的 theme 对比，同 {@code RelationGuard} 判定）：
 *       跨主题时 relationLabel ∈ {@link RelationType} 四词、why 非空、source 非空白且非 "{}"
 *       （键约定沿用 RelationGuard：非空白描述串或非空对象 JSON 文本，解析交给消费方）；
 *       同主题时三者须空（同主题跳转无需关系解释，带了几余上下文反而污染渲染）。</li>
 * </ul>
 * FR-N02/FR-N06 语义：草稿抽取产出先过本校验，违规自动收窄重试一次，仍违即如实报告，
 * 绝不放宽放行——白名单是硬边界，不是提示。
 */
public final class EntryConfigValidator {

    /** FR-N02：入口名称上限（与草稿抽取的 30 字默认命名同一量纲，保存端共用） */
    public static final int MAX_NAME_LENGTH = 60;

    private EntryConfigValidator() {
    }

    /**
     * @param allowedServiceTypes 服务类型白名单（AGENT_SERVICE/COMPARE 的 serviceType 取值域，
     *                            如 {EXPLAIN, COMPARE}）
     * @param allowedAssetScope   授权资料 id 集（当前卡的 sources 挂接且授权有效的资产）
     * @param crossTheme          入口所属卡与目标卡是否跨主题（仅对 LINK_CARD 生效，调用方判定）
     */
    public static List<String> validate(EntryConfig config, Set<String> allowedServiceTypes,
                                        Set<Long> allowedAssetScope, boolean crossTheme) {
        if (config == null) {
            return List.of("入口配置不能为空");
        }
        List<String> violations = new ArrayList<>();
        if (config.name() == null || config.name().isBlank()) {
            violations.add("入口名称不能为空");
        } else if (config.name().length() > MAX_NAME_LENGTH) {
            violations.add("入口名称不能超过 " + MAX_NAME_LENGTH + " 字(当前 " + config.name().length() + " 字)");
        }
        EntryType type = config.type();
        if (type == null) {
            violations.add("入口类型不能为空");
            return violations;
        }
        boolean link = type == EntryType.LINK_CARD;
        boolean service = type == EntryType.AGENT_SERVICE || type == EntryType.COMPARE;

        if (service) {
            if (config.serviceType() == null || config.serviceType().isBlank()) {
                violations.add("服务入口必须指定服务类型(EXPLAIN/COMPARE 之一)");
            } else if (allowedServiceTypes == null || !allowedServiceTypes.contains(config.serviceType())) {
                violations.add("服务类型不在白名单: " + config.serviceType());
            }
        } else if (link && config.serviceType() != null) {
            violations.add("LINK_CARD 入口不应携带服务");
        }

        if (service) {
            if (config.assetScope() == null || config.assetScope().isEmpty()) {
                violations.add("服务入口必须指定资料范围(assetScope)");
            } else {
                // 越权逐个列出（TreeSet 排序保证消息顺序确定，便于断言与人工核对）
                for (Long assetId : new TreeSet<>(config.assetScope())) {
                    if (allowedAssetScope == null || !allowedAssetScope.contains(assetId)) {
                        violations.add("资料范围越权:资产 " + assetId + " 不在授权集");
                    }
                }
            }
        } else if (link && config.assetScope() != null && !config.assetScope().isEmpty()) {
            violations.add("LINK_CARD 入口不应携带资料范围");
        }

        if (link) {
            if (config.targetCardId() == null) {
                violations.add("链接入口必须指定目标卡片");
            }
        } else if (config.targetCardId() != null) {
            violations.add("服务入口不应指定目标卡片");
        }

        if (link) {
            validateRelationContext(config, violations, crossTheme);
        }
        return violations;
    }

    /** relationLabel/why/source 三要件：跨主题齐备、同主题须空（类注释规则第 5 条） */
    private static void validateRelationContext(EntryConfig config, List<String> violations, boolean crossTheme) {
        if (crossTheme) {
            if (!RelationType.isRelationLabel(config.relationLabel())) {
                violations.add("跨主题入口必须携带四类关系词之一(深入了解/相关联/相比较/去实践)");
            }
            if (config.why() == null || config.why().isBlank()) {
                violations.add("跨主题入口必须说明关系原因(why)");
            }
            if (!hasSource(config.source())) {
                violations.add("跨主题入口必须注明出处(source)");
            }
        } else {
            if (config.relationLabel() != null && !config.relationLabel().isBlank()) {
                violations.add("同主题链接入口不应携带关系词(relationLabel)");
            }
            if (config.why() != null && !config.why().isBlank()) {
                violations.add("同主题链接入口不应携带关系说明(why)");
            }
            if (config.source() != null && !config.source().isBlank()) {
                violations.add("同主题链接入口不应携带出处(source)");
            }
        }
    }

    /** source 有效判断（RelationGuard 键约定的字符串形态）：非空白，且不是 "{}" 空对象占位 */
    private static boolean hasSource(String source) {
        if (source == null) {
            return false;
        }
        String trimmed = source.trim();
        return !trimmed.isEmpty() && !"{}".equals(trimmed);
    }
}
