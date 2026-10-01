package com.ke.service.agent.dto;

import java.util.List;

/**
 * 比较服务结构化输出（FR-S06 一期 / Task 23）：LLM 返回 JSON 的绑定目标，也是
 * COMPARE_CARD artifact content_json 里 data 字段的形状——与 ke-domain CompareCardContent
 * 同构（camelCase objects/dimensions/cells），前端 CompareCard 免改渲染；
 * 区别在 citations 语义：此处直接引知识单元 assetId（Long，与 {@link ExplainOutput} 同语义），
 * 而卡片对比卡的 citations 是 sources 的序号（Integer）——故不复用卡片 record 与
 * CardContentValidator（卡片向：Integer 序号 + 独立 Jackson 配置），结构校验在
 * ExplainService 落库前手写断言（cells 行数=维度数、行宽=对象数）。
 */
public record CompareOutput(List<String> objects,
                            List<String> dimensions,
                            List<List<String>> cells,
                            List<Long> citations) {
}
