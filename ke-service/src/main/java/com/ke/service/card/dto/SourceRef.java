package com.ke.service.card.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 卡片来源引用（sources 契约，02 §4.3）：card_version.sources 数组的元素，
 * 渲染为出处清单 [n]《题名》·定位（授权）。assetId 可空——空表示暂未挂接知识单元；
 * 非空表示该出处对应一个 knowledge_asset，发布时据此建 citation 行（FR-O02）。
 */
public record SourceRef(Long assetId,
                        @NotBlank String title,
                        @NotBlank String locator,
                        String license) {
}
