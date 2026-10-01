import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MetricsView from '../views/MetricsView.vue';
import { ApiError } from '../api/http';
import type { MetricsResp } from '../api/metrics';

const { fetchMetricsMock } = vi.hoisted(() => ({ fetchMetricsMock: vi.fn() }));
vi.mock('../api/metrics', () => ({ fetchMetrics: fetchMetricsMock }));

/** 有数样本(与 MetricsIT @Order(2) 造数口径一致;avgCost 网关二期恒 null) */
const METRICS_FULL: MetricsResp = {
  deepenRate: 0.75,
  artifactSaveRate: 1.0,
  shareContinueRate: 2 / 3,
  entrySuccessRate: 0.5,
  sourceCompleteRate: 0.25,
  avgLatencyMs: 200,
  avgCost: null
};

/** 空窗口样本:P8-33 契约——七键齐全且全为 null */
const METRICS_EMPTY: MetricsResp = {
  deepenRate: null,
  artifactSaveRate: null,
  shareContinueRate: null,
  entrySuccessRate: null,
  sourceCompleteRate: null,
  avgLatencyMs: null,
  avgCost: null
};

async function mountView() {
  const wrapper = mount(MetricsView, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe('MetricsView(数据看板,FR-O05)', () => {
  beforeEach(() => {
    fetchMetricsMock.mockReset().mockResolvedValue(METRICS_FULL);
  });

  it('渲染指标卡矩阵:五率卡百分比(tabular-nums)+ 时延/成本卡,只拉一次 /wb/metrics', async () => {
    const wrapper = await mountView();

    expect(fetchMetricsMock).toHaveBeenCalledTimes(1);
    const cards = wrapper.findAll('.stat-card');
    // 六键指标卡(五率 + 时延) + 成本卡 = 7 张;专题速览/周柱状图无端点,注明二期不渲染
    expect(cards).toHaveLength(7);
    const labels = cards.map((c) => c.find('.stat-label').text());
    expect(labels).toEqual([
      '有效深入率',
      '成果保存率',
      '分享接续率',
      '入口成功率',
      '来源完整率',
      '服务时延',
      '服务成本'
    ]);

    // 五率:rate*100 保留 1 位小数 + %
    expect(cards[0]?.find('.stat-value').text()).toBe('75.0%');
    expect(cards[1]?.find('.stat-value').text()).toBe('100.0%');
    expect(cards[2]?.find('.stat-value').text()).toBe('66.7%');
    expect(cards[3]?.find('.stat-value').text()).toBe('50.0%');
    expect(cards[4]?.find('.stat-value').text()).toBe('25.0%');
    // 时延直出 ms;成本 ¥/次(avgCost null → 灰态,见下一用例)
    expect(cards[5]?.find('.stat-value').text()).toBe('200ms');
    expect(cards[5]?.find('.stat-value').classes()).toContain('tabular');
    // 窗口说明与二期注记(专题速览/周柱状图无对应端点)
    expect(wrapper.text()).toContain('最近 7 天');
    expect(wrapper.text()).toContain('二期');
  });

  it('null 指标(空窗口/成本未采集)渲染「暂无数据」灰态,不显示百分比', async () => {
    fetchMetricsMock.mockResolvedValue(METRICS_EMPTY);
    const wrapper = await mountView();

    const cards = wrapper.findAll('.stat-card');
    expect(cards).toHaveLength(7);
    for (const card of cards) {
      expect(card.find('.stat-value.is-null').exists()).toBe(true);
      expect(card.find('.stat-value').text()).toBe('暂无数据');
    }
  });

  it('加载中:渲染 loading 遮罩,数据到达后消失', async () => {
    let resolveMetrics: (v: MetricsResp) => void = () => {};
    fetchMetricsMock.mockImplementation(() => new Promise((resolve) => { resolveMetrics = resolve; }));
    const wrapper = mount(MetricsView, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.find('.el-loading-mask').exists()).toBe(true);

    resolveMetrics(METRICS_FULL);
    await flushPromises();
    // el-loading 遮罩节点保留在 DOM 但置 display:none(组件库行为),以可见性断言
    expect(wrapper.find('.el-loading-mask').isVisible()).toBe(false);
    expect(wrapper.findAll('.stat-card')).toHaveLength(7);
  });

  it('403 无看板权限:渲染共用权限空态(WbDenied),不渲染指标卡', async () => {
    fetchMetricsMock.mockRejectedValue(new ApiError(403, '禁止访问', 't-403'));
    const wrapper = await mountView();
    expect(wrapper.find('[data-testid="wb-denied"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('无访问权限');
    expect(wrapper.text()).toContain('数据看板仅对编辑/运营角色开放');
    expect(wrapper.findAll('.stat-card')).toHaveLength(0);
  });

  it('加载失败(非 403)显示行内错误提示,不出权限空态', async () => {
    fetchMetricsMock.mockRejectedValue(new ApiError(500, '服务异常', 't-5'));
    const wrapper = await mountView();
    expect(wrapper.find('.wb-denied').exists()).toBe(false);
    expect(wrapper.find('.load-error').text()).toContain('服务异常');
  });
});
