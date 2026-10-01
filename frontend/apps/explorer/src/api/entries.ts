/**
 * 入口 API 客户端 —— Phase 6 Task 28 冻结契约(以 EntryMutationController/EntryMutationService/
 * DraftController 为准)。四步创建流的全部后端调用集中此处:nl-draft 草稿抽取(Task 27)、
 * 保存(201 {entryId,scope,status})、试运行(202 {runId,testTotal})、我的入口、scope 双通道。
 * 试运行的 run 轮询复用 runs.ts 的 fetchRun/isTerminal(无会话 run 不落 artifact,只回状态)。
 */
import { http } from './http';
import { fetchRun, isTerminal } from './runs';
import type { RunState } from './runs';

export { fetchRun, isTerminal };
export type { RunState };

/** 入口范围(FR-N04):PRIVATE 个人空间 / PUBLIC 公共区(送审) */
export type EntryScope = 'PRIVATE' | 'PUBLIC';

/** 入口配置载荷(= 后端 EntryConfig record,camelCase 同形;字段允许缺省/null,形状因 type 而异) */
export interface EntryConfig {
  name?: string;
  /** AGENT_SERVICE / COMPARE / LINK_CARD */
  type?: string;
  goal?: string | null;
  inputs?: string[];
  /** EXPLAIN / COMPARE(仅服务入口;LINK_CARD 须 null) */
  serviceType?: string | null;
  /** 仅服务入口;LINK_CARD 须 null */
  assetScope?: number[] | null;
  outputSpec?: string | null;
  relationLabel?: string | null;
  targetCardId?: number | null;
  why?: string | null;
  /** 出处(RelationGuard 键约定):非空描述串,或原样 JSON 文本如 {"assetId":11,"quote":"…"} */
  source?: string | null;
}

/** POST /api/entries/nl-draft 响应(= DraftResult):config/violations 二选一,advice 仅替代建议 */
export interface EntryDraftResult {
  intent: string;
  config: EntryConfig | null;
  violations: string[];
  advice: string | null;
}

/** 保存/切换范围响应 data:{entryId, scope, status} */
export interface EntryWritten {
  entryId: number;
  scope: string;
  status: string;
}

/** GET /api/entries/mine 行:状态/类型/范围/所属卡题/试运行次数 */
export interface MineEntryItem {
  id: number;
  name: string;
  type: string;
  serviceType: string | null;
  relationLabel: string | null;
  targetCardId: number | null;
  scope: string;
  status: string;
  testTotal: number;
  cardId: number;
  cardTitle: string;
}

/** POST /api/entries/{id}/test 响应 data */
export interface EntryTestReceipt {
  runId: number;
  testTotal: number;
}

/** 一句话草稿抽取(Task 27 契约):400=超限/卡未发布,404=卡不存在(http 层抛 ApiError) */
export function nlDraft(cardId: number, text: string): Promise<EntryDraftResult> {
  return http.post<EntryDraftResult>('/entries/nl-draft', { cardId, text });
}

/** 保存入口 → 201:PRIVATE 直接 ACTIVE;PUBLIC 创建即 ACTIVE 但挂审核(后端决策) */
export function createEntry(cardId: number, config: EntryConfig, scope: EntryScope): Promise<EntryWritten> {
  return http.post<EntryWritten>('/entries', { cardId, config, scope });
}

/** 试运行(真实执行一次,配额内)→ 202;LINK_CARD 400、非作者 403 由 http 层抛 ApiError */
export function testEntry(entryId: number): Promise<EntryTestReceipt> {
  return http.post<EntryTestReceipt>(`/entries/${entryId}/test`, {});
}

/** 我的入口列表(含状态/所属卡题/试运行次数) */
export function fetchMyEntries(): Promise<MineEntryItem[]> {
  return http.get<MineEntryItem[]>('/entries/mine');
}

/** scope 双通道切换:PRIVATE→PUBLIC 挂审核;PUBLIC→PRIVATE 直接(仅作者,403 由 http 层抛) */
export function changeEntryScope(entryId: number, scope: EntryScope): Promise<EntryWritten> {
  return http.put<EntryWritten>(`/entries/${entryId}/scope`, { scope });
}
