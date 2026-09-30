package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/** 对比卡（FR-C04）。cells 为 rows=dimensions.size、cols=objects.size 的矩阵，写前校验保证形状。 */
public record CompareCardContent(
    @NotEmpty List<String> objects,
    @NotEmpty List<String> dimensions,
    @NotEmpty List<List<String>> cells,
    List<Integer> citations) implements CardContent {
}
