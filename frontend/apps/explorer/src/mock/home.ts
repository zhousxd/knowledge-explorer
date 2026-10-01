/**
 * 首页静态演示数据 —— Phase 4/5 接真数据:
 * 专题计数 → GET /cards 按专题聚合;推荐入口 → 推荐服务;
 * 继续探索卡已接真数据(P4-17,GET /api/sessions/latest),数据位不复存在。
 */
import { THEMES } from '@ke/shared';

/** 主题专题格(key 即 @ke/shared THEMES 权威取值,亦为 /cards?theme= 的过滤值) */
export interface HomeTheme {
  key: string;
  name: string;
  icon: string;
  count: number;
}

/** 演示字段:icon 对应首页 SVG 图标语言;专题张数为演示数据(界面占位,未接 GET /cards 真数聚合) */
const THEME_ICONS = { academy: 'temple', cuisine: 'bowl', sound: 'wave' } as const;
const THEME_DEMO_COUNT = { academy: 12, cuisine: 9, sound: 7 } as const;

/** 名称取自 shared 专题字典(与工作台新建卡片同源对齐) */
export const HOME_THEMES: HomeTheme[] = THEMES.map((t) => ({
  key: t.key,
  name: t.label,
  icon: THEME_ICONS[t.key],
  count: THEME_DEMO_COUNT[t.key]
}));

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
