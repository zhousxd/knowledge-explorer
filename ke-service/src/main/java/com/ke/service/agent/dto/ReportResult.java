package com.ke.service.agent.dto;

import com.ke.service.agent.post.RunMetrics;

import java.util.List;
import java.util.Map;

/**
 * REPORT artifact 的 content_json 形状（FR-S03 / Task 24 决策）：{type:'REPORT', keyFindings,
 * openQuestions, branchView, sources, disclaimer, audit}——keyFindings/openQuestions 为整理输出
 * （citations 已过校验器，只剩材料资产集合内的 assetId）；branchView 由 pathNode 树结构生成
 * （非 LLM）：selected 每个根一个分支，nodeTitles 为该根子树内节点标题（访问顺序）；
 * sources 为材料资产快照（assetId→文本，复用讲解/比较结果页出处清单渲染）；disclaimer 为
 * AI 生成标识（R8）；audit 为审计旁注（stripped=越界引用剥离数，filtered=敏感词命中数）。
 * type 是前端 DONE 分流依据（与 COMPARE_CARD 同例；EXPLAIN 的 content_json 无 type 键）。
 */
public record ReportResult(String type, List<SummaryOutput.KeyFinding> keyFindings,
                           List<String> openQuestions, List<BranchView> branchView,
                           Map<Long, String> sources, String disclaimer, RunMetrics.Audit audit) {

    /** 分支视图行（非 LLM）：rootNodeTitle=选中根的标题，nodeTitles=子树内节点标题（按访问顺序） */
    public record BranchView(String rootNodeTitle, List<String> nodeTitles) {
    }
}
