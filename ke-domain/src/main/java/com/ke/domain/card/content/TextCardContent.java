package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 图文卡（FR-C03）。summary ≤120 字，sections 非空，related.relation 非空。 */
public record TextCardContent(
    @NotBlank @Size(max = 120) String summary,
    @NotEmpty @Valid List<Section> sections,
    @Valid List<Related> related) implements CardContent {

  public record Section(@NotBlank String h, @NotBlank String body, List<Integer> citations) {}

  public record Related(@NotNull Long cardId, @NotBlank String relation, @NotBlank String why, Long source) {}
}
