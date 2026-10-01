package com.ke.service.agent.post;

/**
 * 完成路径计量（FR-S13）：latency/model 由 ExplainService.finish 落 agent_run（已有）。
 *
 * <p>tokens_in/out 与 cost 本期不采集——LLM 响应 usage 在 Spring AI {@code content()} 处被丢弃，
 * 采集依赖 vLLM/网关透传 usage，随二期接入补齐（agent_run 三列留空，不占语义）。
 * 本期计量口径 = latency + model。
 *
 * <p>审计旁注（引用剥离 n / 敏感词命中 m）不写 agent_run.error 的新语义（error 列在 DONE
 * 语义为空，既有「引用校验:剥离」旁注保留不变），并入 artifact content_json.audit。
 */
public final class RunMetrics {

    private RunMetrics() {
    }

    /**
     * artifact.content_json.audit：stripped=越界引用剥离数（FR-S05 校验器留痕），
     * filtered=敏感词命中替换次数（FR-S10）。
     */
    public record Audit(int stripped, int filtered) {
    }
}
