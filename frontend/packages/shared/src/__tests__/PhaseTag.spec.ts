import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import PhaseTag from '../components/PhaseTag.vue';

describe('PhaseTag', () => {
  it('渲染分期文案', () => {
    expect(mount(PhaseTag, { props: { phase: 'P2' } }).text()).toBe('二期');
    expect(mount(PhaseTag, { props: { phase: 'P3' } }).text()).toBe('三期');
  });
  it('可覆盖标签文案', () => {
    expect(mount(PhaseTag, { props: { phase: 'P2', label: '二期 · 地图卡' } }).text()).toBe('二期 · 地图卡');
  });
});
