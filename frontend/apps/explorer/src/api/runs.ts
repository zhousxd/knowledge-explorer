/**
 * 智能体运行 API 客户端 —— Phase 5 冻结契约(以 RunController/ExplainService 为准)。
 * POST /api/agent/runs → 202 {runId}(429 配额超限 envelope 由 http 层归一为 ApiError,
 * message 原样透传给调用方展示,04 §8.6);GET /api/agent/runs/{id} → 轮询详情。
 * 后端 jackson non_null 序列化把 null 键整键省略 —— model/latencyMs/error/artifact/submitContext 都可能
 * 缺键,fetchRun 统一归一为 null 家族(Phase 4 终审 seam 钉子,参照 sessions.ts parentNodeId 先例),
 * 消费方(RunView/结果页)可放心按 null 分支。
 */
import { http } from './http';
import type { CompareContent } from './cards';
import type { ExplainLevel } from './sessions';

/** 运行状态(后端 AgentRunStatus;TIMEOUT=60s 护栏,02 §5.1) */
export type RunStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'TIMEOUT';

/** 服务类型(Task 23/24:EXPLAIN=讲解,COMPARE=帮我比较,SUMMARIZE=成果整理——后端白名单,缺省 EXPLAIN) */
export type RunServiceType = 'EXPLAIN' | 'COMPARE' | 'SUMMARIZE';

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
  /** 服务类型(Task 23):「帮我比较」传 COMPARE(产出对比卡 artifact);缺省 EXPLAIN。
   *  追问(parentRunId)恒走讲解,不传本字段。 */
  serviceType?: RunServiceType;
}

/** POST /api/agent/summarize 请求体(Task 24 冻结契约):nodeIds 非空且 ≤50,后端校验 */
export interface SummarizePayload {
  sessionId: number;
  /** 勾选的路径节点(FR-E07 跨分支);后端去重保序 */
  nodeIds: number[];
}

/** artifact.output 形状(= ExplainOutput:结构化讲解输出) */
export interface RunArtifactOutput {
  summary?: string;
  /** 段落档位三档可信(FACT/SYNTHESIS/GEN);citations 直接引知识单元 assetId */
  sections?: Array<{ body: string; claimType: string; citations?: number[] }>;
  openQuestions?: string[];
  evidenceGaps?: string[];
}

/** REPORT 的关键发现行(= SummaryOutput.KeyFinding:citations 直接引知识单元 assetId) */
export interface RunKeyFinding {
  body: string;
  /** FACT/SYNTHESIS/GEN 三档可信(与讲解段同语义) */
  claimType: string;
  citations?: number[];
}

/** REPORT 的分支视图行(非 LLM,由 pathNode 树生成):rootNodeTitle=选中根标题,nodeTitles=子树内节点题 */
export interface RunBranchView {
  rootNodeTitle: string;
  nodeTitles: string[];
}

/**
 * artifact 形状(DONE 分流,Task 23/24):type==='COMPARE_CARD' 时为比较结果
 * {type, data: CompareContent, sources, disclaimer, audit};type==='REPORT' 时为探索报告
 * (Task 24,content_json 扁平键:{type, keyFindings, openQuestions, branchView, sources,
 * disclaimer, audit}——keyFindings/openQuestions/branchView 与 sources 同级,不嵌套);
 * 否则(无 type 键)为讲解形状(= ExplainResult content_json:output 嵌套)。
 * sources 为检索快照(assetId→检索文本,字符串键),disclaimer 为 AI 生成标识(R8),audit 为审计旁注。
 * 全字段 optional:non_null 下空值整键省略(如无会话 run 不落 artifact 的空对象分支)。
 */
export interface RunArtifact {
  /** 结果类型:COMPARE_CARD=比较(配 data);REPORT=探索报告(Task 24,配 keyFindings 等);缺省=讲解(配 output) */
  type?: 'COMPARE_CARD' | 'REPORT';
  /** 比较输出(= CompareOutput,与卡片 CompareContent 同构,CompareCard props 直传) */
  data?: CompareContent;
  output?: RunArtifactOutput;
  /** 探索报告(Task 24):关键发现(citations 已过校验器,只剩材料资产集合内的 assetId) */
  keyFindings?: RunKeyFinding[];
  /** 探索报告:整理输出的未决疑问(LLM 对材料遗留疑问去重合并) */
  openQuestions?: string[];
  /** 探索报告:分支视图(每个选中根一行,非 LLM) */
  branchView?: RunBranchView[];
  sources?: Record<string, string>;
  disclaimer?: string;
  audit?: { stripped?: number; filtered?: number };
}

/** 提交上下文投影(后端 GET /runs/{id} 的 submitContext 键,review P5-FIX):input_json 白名单字段,
 *  仅属主可见。jackson non_null 省略 null 键 → sessionId/nodeId/parentRunId/question 均可缺
 *  (旧行/无会话 legacy run),消费方(RunView 重建 launch / SummaryView 去追问)须自行判空。 */
export interface RunSubmitContext {
  cardVersionId: number;
  sessionId?: number;
  nodeId?: number;
  serviceType?: string;
  parentRunId?: number;
  question?: string;
}

/** GET /api/agent/runs/{id} 归一后形态(null 家族,消费方免判 undefined) */
export interface RunState {
  runId: number;
  status: RunStatus;
  /** 服务类型(Task 23:DONE 态渲染分流),缺键为 null(旧行) */
  serviceType: string | null;
  /** 生成模型(FR-S13 计量口径),非终态/缺键为 null */
  model: string | null;
  /** 服务端耗时 ms,缺键为 null */
  latencyMs: number | null;
  /** 失败原因(DONE 但剥离过越界引用时也可能带审计文案),缺键为 null */
  error: string | null;
  /** DONE 且有 artifact 时存在(无会话 run 不落 artifact,P5-18 决策);其余 null */
  artifact: RunArtifact | null;
  /** 提交上下文投影(review P5-FIX):刷新丢路由 state 后重建追问/重试用;解析失败/旧行为 null */
  submitContext: RunSubmitContext | null;
}

/** 提交讲解运行 → 202 {runId};429/400 等错误信封由 http 层抛 ApiError(code+message) */
export function submitRun(payload: RunSubmitPayload): Promise<{ runId: number }> {
  return http.post<{ runId: number }>('/agent/runs', payload);
}

/** 提交成果整理运行(FR-S03/E07)→ 202 {runId};空选/越权 400/403、429 配额同由 http 层抛 ApiError */
export function summarize(payload: SummarizePayload): Promise<{ runId: number }> {
  return http.post<{ runId: number }>('/agent/summarize', payload);
}

/** wire 层归一(non_null 缺键 → null):RunController.RunView 的单一消费面 */
function normalizeRun(run: Omit<RunState, 'model' | 'latencyMs' | 'error' | 'artifact' | 'serviceType' | 'submitContext'> &
  Partial<Pick<RunState, 'model' | 'latencyMs' | 'error' | 'artifact' | 'serviceType' | 'submitContext'>>): RunState {
  return {
    runId: run.runId,
    status: run.status,
    serviceType: run.serviceType ?? null,
    model: run.model ?? null,
    latencyMs: run.latencyMs ?? null,
    error: run.error ?? null,
    artifact: run.artifact ?? null,
    submitContext: run.submitContext ?? null
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
