package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** 时间线卡（FR-C05）。events 非空且逐项级联校验。 */
public record TimelineCardContent(@NotEmpty @Valid List<Event> events) implements CardContent {

  public record Event(@NotBlank String year, @NotBlank String title, String body, Long cardId,
                      List<Integer> citations) {}
}
