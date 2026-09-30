package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

/** 任务卡（FR-C06）。steps 非空且每步 minutes > 0。 */
public record TaskCardContent(
    @NotBlank String goal,
    @NotEmpty @Valid List<Step> steps,
    @NotEmpty List<String> recordSchema) implements CardContent {

  public record Step(String place, @NotBlank String observe, @Positive int minutes) {}
}
