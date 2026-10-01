/**
 * 首页静态演示数据 —— Phase 4/5 接真数据:
 * 专题计数 → GET /cards 按专题聚合;推荐入口 → 推荐服务;继续探索卡 → GET /api/sessions/latest(Phase 4 Task 16)。
 */
import type { ResumeSession } from '../api/sessions';

/** 主题专题(key 用作 /cards?theme= 的过滤值,Phase 4 与后端专题字典对齐) */
export interface HomeTheme {
  key: string;
  name: string;
  icon: string;
  count: number;
}

export const HOME_THEMES: HomeTheme[] = [
  { key: 'academy', name: '书院地标', icon: 'temple', count: 12 },
  { key: 'cuisine', name: '湘菜风物', icon: 'bowl', count: 9 },
  { key: 'sound', name: '声音科学', icon: 'wave', count: 7 }
];

/** 今日推荐入口(演示数据,界面统一标注「演示」chip) */
export interface HomeEntry {
  icon: string;
  name: string;
  sub: string;
}

export const HOME_ENTRIES: HomeEntry[] = [
  { icon: 'mountain', name: '为什么建在这里', sub: '深入了解 · 历史 × 地理' },
  { icon: 'scale', name: '剁椒鱼头为什么是“辣”的', sub: '相比较 · 饮食 × 科学跨主题' }
];

/**
 * 继续探索卡数据位:端点 Phase 4 Task 16 交付前恒为 undefined(整卡隐藏,不伪造会话数据);
 * 届时由 HomeView 调 fetchLatestSession() 填充。
 */
export const HOME_RESUME: ResumeSession | undefined = undefined;
