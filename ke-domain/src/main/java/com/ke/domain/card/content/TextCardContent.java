package com.ke.domain.card.content;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 图文卡（FR-C03）。summary ≤120 字，sections 非空且元素非 null，related.relation 非空；
 * image 可选配图（二期图片功能）：id/url 必填且须指向 card_image 真实行（存在性由 ke-service 写路径把关）。 */
public record TextCardContent(
    @NotBlank @Size(max = 120) String summary,
    @NotEmpty @Valid List<@NotNull Section> sections,
    @Valid List<Related> related,
    @Valid CardImage image) implements CardContent {

  public record Section(@NotBlank String h, @NotBlank String body, List<Integer> citations) {}

  public record Related(@NotNull Long cardId, @NotBlank String relation, @NotBlank String why, Long source) {}

  /** 配图引用：url 必须是 /api/images/{id} 规范形状（防外链/自造 URL），alt 展示用可空。 */
  public record CardImage(
      @NotNull Long id,
      @NotBlank @Pattern(regexp = "/api/images/\\d+", message = "必须是 /api/images/{id} 形状") String url,
      @Size(max = 60) String alt) {}
}
