import { describe, expect, it } from 'vitest';
import { THEMES, themeLabel } from '../themes';

describe('themes(专题字典,卡片 theme 字段权威取值)', () => {
  it('恰为三键,顺序与 label 固定', () => {
    expect(THEMES.map((t) => t.key)).toEqual(['academy', 'cuisine', 'sound']);
    expect(THEMES.map((t) => t.label)).toEqual(['书院地标', '湘菜风物', '声音科学']);
  });

  it('themeLabel:key → 中文专题名', () => {
    expect(themeLabel('academy')).toBe('书院地标');
    expect(themeLabel('cuisine')).toBe('湘菜风物');
    expect(themeLabel('sound')).toBe('声音科学');
  });

  it('themeLabel:未知 key 回落原样(后端自由字符串向前兼容)', () => {
    expect(themeLabel('湖湘文化')).toBe('湖湘文化');
    expect(themeLabel('')).toBe('');
  });
});
