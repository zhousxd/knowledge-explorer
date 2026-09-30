package com.ke.domain.card.content;

/** 四模板 content_json 的统一标记类型（FR-C03~C06）。 */
public sealed interface CardContent permits TextCardContent, CompareCardContent, TimelineCardContent, TaskCardContent {
}
