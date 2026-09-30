import { describe, expect, it } from 'vitest';
import { SPRITE_HTML, SPRITE_IDS, installSprite } from '../icons/sprite';

describe('sprite', () => {
  it('SPRITE_IDS 恰为 styleguide.html 的 23 个 symbol id', () => {
    expect(SPRITE_IDS).toHaveLength(23);
    expect(SPRITE_IDS).toEqual([
      'i-back', 'i-chev', 'i-compass', 'i-search', 'i-book', 'i-scale', 'i-layers',
      'i-plus', 'i-lock', 'i-check', 'i-alert', 'i-share', 'i-star', 'i-clock',
      'i-users', 'i-pen', 'i-mountain', 'i-temple', 'i-bowl', 'i-wave', 'i-home',
      'i-path', 'i-me'
    ]);
  });
  it('SPRITE_HTML 逐个包含全部 symbol id，防止漏拷', () => {
    for (const id of SPRITE_IDS) {
      expect(SPRITE_HTML).toContain(`id="${id}"`);
    }
  });
  it('installSprite 注入 body 且幂等', () => {
    installSprite();
    installSprite();
    const node = document.getElementById('ke-icon-sprite');
    expect(node).not.toBeNull();
    expect(document.querySelectorAll('#ke-icon-sprite')).toHaveLength(1);
    expect(node?.querySelectorAll('symbol')).toHaveLength(23);
  });
});
