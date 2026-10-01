/**
 * 专题 key 为卡片 theme 字段权威取值;后端自由字符串为向前兼容,UI 一律从本字典取值。
 */

export const THEMES = [
  { key: 'academy', label: '书院地标' },
  { key: 'cuisine', label: '湘菜风物' },
  { key: 'sound', label: '声音科学' }
] as const;

/** 专题 key(卡片 theme 字段的合法取值) */
export type ThemeKey = (typeof THEMES)[number]['key'];

/** 专题 key → 中文专题名;未知 key(历史数据/后端自由串)回落原样显示 */
export function themeLabel(key: string): string {
  return THEMES.find((t) => t.key === key)?.label ?? key;
}
