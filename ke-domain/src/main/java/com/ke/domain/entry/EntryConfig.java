package com.ke.domain.entry;

import com.ke.domain.enums.EntryType;

import java.util.List;
import java.util.Set;

/**
 * 入口的配置载荷（FR-N01/N02/N07，Task 27）：自然语言草稿抽取与入口保存共用的纯数据形状，
 * 即 entry.config_json 的结构化契约。字段允许为 null——形状因 type 而异：
 * AGENT_SERVICE/COMPARE 携带 goal/serviceType/assetScope/outputSpec（inputs 为可选输入清单）；
 * LINK_CARD 携带 targetCardId，跨主题时再带 relationLabel/why/source 三要件
 * （RelationGuard 键约定：source 为非空白描述串，或原样 JSON 文本如 {@code {"assetId":11,"quote":"…"}}——
 * 本类型只做非空校验，解析交给消费方）。硬约束判定见 {@link EntryConfigValidator}。
 */
public record EntryConfig(String name, EntryType type, String goal, List<String> inputs,
                          String serviceType, Set<Long> assetScope, String outputSpec,
                          String relationLabel, Long targetCardId, String why, String source) {
}
