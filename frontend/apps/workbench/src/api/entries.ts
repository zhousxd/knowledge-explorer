/**
 * 工作台入口编排 API 客户端(FR-N04/N05 界面,Task 28):我的入口列表(名称/类型/所属卡/
 * scope/状态/试运行次数)。复用探索端同一后端契约(GET /api/entries/mine,按登录用户过滤);
 * 保存/试运行/送审入口在探索端四步流完成,这里只做查看与详情。
 */
import { http } from './http';

/** GET /api/entries/mine 行(= 后端 EntryMutationService.MineItem) */
export interface WorkbenchEntry {
  id: number;
  name: string;
  /** AGENT_SERVICE / COMPARE / LINK_CARD */
  type: string;
  /** EXPLAIN / COMPARE(仅服务入口) */
  serviceType: string | null;
  relationLabel: string | null;
  targetCardId: number | null;
  scope: string;
  status: string;
  /** 试运行次数(Task 28:每次真实执行 +1) */
  testTotal: number;
  cardId: number;
  cardTitle: string;
}

/** GET /api/entries/mine */
export function fetchMyEntries(): Promise<WorkbenchEntry[]> {
  return http.get<WorkbenchEntry[]>('/entries/mine');
}
