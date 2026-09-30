package com.ke.domain.card.content;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用索引校验（sources 契约）：content 内的 citations[n] 是 1-based 索引，
 * 指向 card_version.sources 数组（渲染为出处清单 [n]《题名》·定位（授权））。
 * 与 {@link CardContentValidator} 的分工：后者管模板结构（含编号 ≥ 0 的形状约束），
 * 本类管「编号 vs 来源数」的跨字段约束，须在拿到 sources 数量后于写路径调用
 * （CardService.create / saveContent）。TaskCardContent 结构不含引用，无需检查。
 */
public final class CitationIndexValidator {

    private CitationIndexValidator() {
    }

    /**
     * 校验 content 各处 citations 均落在 [1, sourceCount]。
     *
     * @throws InvalidCardContentException 存在越界（或 null）编号时；sources 为空而
     *                                      content 出现非空 citations 同样视为越界
     */
    public static void check(CardContent content, int sourceCount) {
        List<String> errors = new ArrayList<>();
        if (content instanceof TextCardContent t && t.sections() != null) {
            for (int i = 0; i < t.sections().size(); i++) {
                TextCardContent.Section section = t.sections().get(i);
                checkOne("sections[" + i + "].citations", section == null ? null : section.citations(),
                        sourceCount, errors);
            }
        } else if (content instanceof CompareCardContent c) {
            checkOne("citations", c.citations(), sourceCount, errors);
        } else if (content instanceof TimelineCardContent tl && tl.events() != null) {
            for (int i = 0; i < tl.events().size(); i++) {
                TimelineCardContent.Event event = tl.events().get(i);
                checkOne("events[" + i + "].citations", event == null ? null : event.citations(),
                        sourceCount, errors);
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidCardContentException(String.join("; ", errors));
        }
    }

    private static void checkOne(String path, List<Integer> citations, int sourceCount, List<String> errors) {
        if (citations == null || citations.isEmpty()) {
            return;
        }
        for (Integer no : citations) {
            if (no == null || no < 1 || no > sourceCount) {
                errors.add(path + ": 引用 [" + no + "] 超出来源范围");
            }
        }
    }
}
