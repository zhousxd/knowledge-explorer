package com.ke.service.agent.dto;

import com.ke.service.agent.post.RunMetrics;

import java.util.Map;

/**
 * COMPARE artifact 的 content_json 形状（Task 23 决策）：{type:'COMPARE_CARD', data:比较输出,
 * sources, disclaimer, audit}——data 为 {@link CompareOutput}（前端 CompareCard props 直传），
 * sources 为检索快照（复用讲解结果页出处清单），disclaimer 为 AI 生成标识（R8，脚注复用），
 * audit 为审计旁注（stripped=越界引用剥离数；比较矩阵无段落档位，filtered 恒 0）。
 * type 是前端 DONE 分流依据（EXPLAIN 的 content_json 无 type 键）。
 */
public record CompareResult(String type, CompareOutput data, Map<Long, String> sources,
                            String disclaimer, RunMetrics.Audit audit) {
}
