package com.ke.domain.trust;

import com.ke.domain.enums.ClaimType;

import java.util.List;

/**
 * 校验后的讲解段（FR-S05）：body 原样保留，claimType 可能降级（FACT → SYNTHESIS），
 * citations 只含 allowedAssetIds 内的 assetId。与讲解服务 LLM 绑定段同形
 * （body/claimType/citations），ke-domain 不依赖 service 层 DTO，由调用方映射。
 */
public record SanitizedSection(String body, ClaimType claimType, List<Long> citations) {
}
