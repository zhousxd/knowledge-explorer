/**
 * 智能体运行 API 客户端 —— Phase 5 冻结契约(以 RunController/ExplainService 为准)。
 * POST /api/agent/runs → 202 {runId}(429 配额超限 envelope 由 http 层归一为 ApiError,
 * message 原样透传给调用方展示,04 §8.6);GET /api/agent/runs/{id} → 轮询详情。
 * 后端 jackson non_null 序列化把 null 键整键省略 —— model/latencyMs/error/artifact 都可能
 * 缺键,fetchRun 统一归一为 null 家族(Phase 4 终审 seam 钉子,参照 sessions.ts parentNodeId 先例),
 * 消费方(RunView/结果页)可放心按 null 分支。
 */
import { http } from './http';
import type { ExplainLevel } from './sessions';

/** 运行状态(后端 AgentRunStatus;TIMEOUT=60s 护栏,02 §5.1) */
export type RunStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'TIMEOUT';

/** POST /api/agent/runs 请求体(nodeId 必随 sessionId —— 后端 P5-18 冻结校验) */
export interface RunSubmitPayload {
  /** 卡片版本 id(card_version 表 PK) */
  cardVersionId: number;
  sessionId: number;
  /** 本次服务挂载的路径节点(提交前由 addNode 创建) */
  nodeId: number;
  question: string;
  level: ExplainLevel;
  /** 追问链(FR-E08,Task 22):父 run id,后端校验存在且属主否则 400;首次讲解不传 */
  parentRunId?: number;
}

/** artifact.output 形状(= ExplainOutput:结构化讲解输出) */
export interface RunArtifactOutput {
  summary?: string;
  /** 段落档位三档可信(FACT/SYNTHESIS/GEN);citations 直接引知识单元 assetId */
  sections?: Array<{ body: string; claimType: string; citations?: number[] }>;
  openQuestions?: string[];
  evidenceGaps?: string[];
}

/**
 * artifact 形状(= ExplainResult content_json):output 嵌套结构化输出,sources 为检索快照
 * (assetId→检索文本,序列化为字符串键),disclaimer 为 AI 生成标识(R8),audit 为审计旁注。
 * 全字段 optional:non_null 下空值整键省略(如无会话 run 不落 artifact 的空对象分支)。
 */
export interface RunArtifact {
  output?: RunArtifactOutput;
  sources?: Record<string, string>;
  disclaimer?: string;
  audit?: { stripped?: number; filtered?: number };
}

/** GET /api/agent/runs/{id} 归一后形态(null 家族,消费方免判 undefined) */
export interface RunState {
  runId: number;
  status: RunStatus;
  /** 生成模型(FR-S13 计量口径),非终态/缺键为 null */
  model: string | null;
  /** 服务端耗时 ms,缺键为 null */
  latencyMs: number | null;
  /** 失败原因(DONE 但剥离过越界引用时也可能带审计文案),缺键为 null */
  error: string | null;
  /** DONE 且有 artifact 时存在(无会话 run 不落 artifact,P5-18 决策);其余 null */
  artifact: RunArtifact | null;
}

/** 提交讲解运行 → 202 {runId};429/400 等错误信封由 http 层抛 ApiError(code+message) */
export function submitRun(payload: RunSubmitPayload): Promise<{ runId: number }> {
  return http.post<{ runId: number }>('/agent/runs', payload);
}

/** wire 层归一(non_null 缺键 → null):RunController.RunView 的单一消费面 */
function normalizeRun(run: Omit<RunState, 'model' | 'latencyMs' | 'error' | 'artifact'> &
  Partial<Pick<RunState, 'model' | 'latencyMs' | 'error' | 'artifact'>>): RunState {
  return {
    runId: run.runId,
    status: run.status,
    model: run.model ?? null,
    latencyMs: run.latencyMs ?? null,
    error: run.error ?? null,
    artifact: run.artifact ?? null
  };
}

/** 运行详情(轮询);403 非属主/404 不存在由调用方按 ApiError.code 处理 */
export async function fetchRun(id: number): Promise<RunState> {
  return normalizeRun(await http.get<Parameters<typeof normalizeRun>[0]>(`/agent/runs/${id}`));
}

const TERMINAL_STATUSES: ReadonlySet<string> = new Set(['DONE', 'FAILED', 'TIMEOUT']);

/** 终态判定:轮询到达即停止(QUEUED/RUNNING 继续) */
export function isTerminal(status: RunStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}
