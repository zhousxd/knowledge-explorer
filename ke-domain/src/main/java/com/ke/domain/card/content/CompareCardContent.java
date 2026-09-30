package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 对比卡（FR-C04）。cells 为 rows=dimensions.size、cols=objects.size 的矩阵，写前校验保证形状；
 * objects/dimensions 元素非 null（null 元素会让矩阵形状与 join 语义失真）。
 */
public record CompareCardContent(
    @NotEmpty List<@NotNull String> objects,
    @NotEmpty List<@NotNull String> dimensions,
    @NotEmpty List<List<String>> cells,
    List<Integer> citations) implements CardContent {
}
