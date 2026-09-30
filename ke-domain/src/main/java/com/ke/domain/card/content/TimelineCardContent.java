package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** 时间线卡（FR-C05）。events 非空、元素非 null 且逐项级联校验。 */
public record TimelineCardContent(@NotEmpty @Valid List<@NotNull Event> events) implements CardContent {

  public record Event(@NotBlank String year, @NotBlank String title, String body, Long cardId,
                      List<Integer> citations) {}
}
