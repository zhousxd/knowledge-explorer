import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ReviewsView from '../views/ReviewsView.vue';
import { ApiError } from '../api/http';
import type { ReviewItem } from '../api/types';
import { useReviewStore } from '../stores/review';

const { listReviewsMock, approveMock, rejectMock } = vi.hoisted(() => ({
  listReviewsMock: vi.fn(),
  approveMock: vi.fn(),
  rejectMock: vi.fn()
}));

vi.mock('../api/reviews', () => ({
  listReviews: listReviewsMock,
  approveReview: approveMock,
  rejectReview: rejectMock
}));

const ITEM_OK: ReviewItem = {
  id: 101,
  objectType: 'CARD',
  objectId: 1,
  action: 'SUBMIT',
  status: 'PENDING',
  createdAt: '2026-09-30T10:00:00+08:00',
  summary: '岳麓书院 · TEXT · 创作者甲',
  precheck: { contentValid: true, hasSources: true },
  contentPreview: '千年学府简介'
};

const ITEM_BAD_CONTENT: ReviewItem = {
  id: 102,
  objectType: 'CARD',
  objectId: 2,
  action: 'SUBMIT',
  status: 'PENDING',
  createdAt: '2026-09-29T09:00:00+08:00',
  summary: '书院对比 · COMPARE · 创作者乙',
  precheck: { contentValid: false, hasSources: true },
  contentPreview: '岳麓书院 vs 石鼓书院 · 始建年代'
};

/** 队列(size=20)返回 2 条;徽标查询(size=1)只取 total */
function mockQueue(total = 2) {
  listReviewsMock.mockImplementation((params: { size?: number }) => {
    if (params.size === 1) {
      return Promise.resolve({ items: [], total, page: 1, size: 1 });
    }
    return Promise.resolve({ items: [ITEM_OK, ITEM_BAD_CONTENT], total, page: 1, size: 20 });
  });
}

async function mountView() {
  const pinia = createPinia();
  setActivePinia(pinia);
  const wrapper = mount(ReviewsView, { global: { plugins: [pinia, ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe('ReviewsView', () => {
  beforeEach(() => {
    listReviewsMock.mockReset();
    approveMock.mockReset().mockResolvedValue({});
    rejectMock.mockReset().mockResolvedValue({});
    mockQueue();
  });

  it('渲染 2 张审核卡:对象 chip/标题/预检标签/内容预览/提交人时间', async () => {
    const wrapper = await mountView();
    const cards = wrapper.findAll('.review-card');
    expect(cards).toHaveLength(2);

    // 队列加载与徽标计数各拉一次(PENDING,卡片队列)
    expect(listReviewsMock).toHaveBeenCalledWith({ status: 'PENDING', objectType: 'CARD', page: 1, size: 20 });
    expect(listReviewsMock).toHaveBeenCalledWith({ status: 'PENDING', page: 1, size: 1 });

    const first = cards[0];
    expect(first.find('.obj-name').text()).toBe('岳麓书院');
    expect(first.find('.obj-chip').text()).toBe('卡片');
    expect(first.text()).toContain('图文');
    // 预检全过 → 两枚绿色 chip + 「机器预检」灰 chip
    const okTags = first.findAll('.precheck .el-tag');
    expect(okTags.map((t) => t.text())).toEqual(['内容可解析', '来源齐备', '机器预检']);
    expect(okTags[0]?.classes()).toContain('el-tag--success');
    expect(okTags[1]?.classes()).toContain('el-tag--success');
    expect(okTags[2]?.classes()).toContain('el-tag--info');
    expect(first.find('.preview').text()).toBe('千年学府简介');
    expect(first.find('.kv').text()).toContain('创作者甲');
    expect(first.find('.kv').text()).toContain('2026-09-30 10:00');

    // contentValid=false → 「内容可解析」灰态(info),hasSources=true 仍绿
    const secondTags = cards[1].findAll('.precheck .el-tag');
    expect(secondTags[0]?.classes()).toContain('el-tag--info');
    expect(secondTags[0]?.text()).toBe('内容可解析');
    expect(secondTags[1]?.classes()).toContain('el-tag--success');
  });

  it('两队列 tabs:入口 tab 禁用并注明后续版本开放,点击不切换', async () => {
    const wrapper = await mountView();
    listReviewsMock.mockClear();
    const entryTab = wrapper.find('[data-queue="ENTRY"]');
    expect(entryTab.classes()).toContain('is-disabled');
    expect(wrapper.find('.entry-hint').text()).toContain('入口审核将于后续版本开放');
    await entryTab.trigger('click');
    await flushPromises();
    expect(listReviewsMock).not.toHaveBeenCalled();
    expect(wrapper.find('[data-queue="CARD"]').classes()).toContain('is-active');
  });

  it('通过一击完成:调 approve 后刷新队列并联动待审徽标', async () => {
    const wrapper = await mountView();
    const store = useReviewStore();
    expect(store.total).toBe(2);
    listReviewsMock.mockClear();
    await wrapper.findAll('.review-card')[0]?.find('.act-approve').trigger('click');
    await flushPromises();
    expect(approveMock).toHaveBeenCalledTimes(1);
    expect(approveMock).toHaveBeenCalledWith(101);
    // 队列刷新 + 徽标计数刷新(size=1)
    expect(listReviewsMock).toHaveBeenCalledWith({ status: 'PENDING', objectType: 'CARD', page: 1, size: 20 });
    expect(listReviewsMock).toHaveBeenCalledWith({ status: 'PENDING', page: 1, size: 1 });
  });

  it('驳回三步:意见为空确认禁用,填写后确认调 reject 带 notes 并刷新', async () => {
    const wrapper = await mountView();
    listReviewsMock.mockClear();
    const card = wrapper.findAll('.review-card')[0];
    await card?.find('.act-reject').trigger('click');
    await flushPromises();
    expect(card?.find('.reject-box').exists()).toBe(true);

    const confirm = card?.find('.confirm-reject');
    expect(confirm?.attributes('disabled')).toBeDefined();
    await card?.find('.reject-box textarea').setValue('   ');
    expect(card?.find('.confirm-reject').attributes('disabled')).toBeDefined();

    await card?.find('.reject-box textarea').setValue('来源不足,请补充出处');
    expect(card?.find('.confirm-reject').attributes('disabled')).toBeUndefined();
    await card?.find('.confirm-reject').trigger('click');
    await flushPromises();
    expect(rejectMock).toHaveBeenCalledWith(101, '来源不足,请补充出处');
    // 驳回成功后意见框收起、队列刷新
    expect(wrapper.findAll('.reject-box')).toHaveLength(0);
    expect(listReviewsMock).toHaveBeenCalledWith({ status: 'PENDING', objectType: 'CARD', page: 1, size: 20 });
  });

  it('403 无审核权限:渲染权限空态,不渲染审核卡', async () => {
    listReviewsMock.mockRejectedValue(new ApiError(403, '禁止访问', 't-403'));
    const wrapper = await mountView();
    expect(wrapper.find('.denied').exists()).toBe(true);
    expect(wrapper.text()).toContain('无审核权限');
    expect(wrapper.findAll('.review-card')).toHaveLength(0);
  });

  it('加载失败(非 403)显示错误行内提示', async () => {
    listReviewsMock.mockRejectedValue(new ApiError(500, '服务异常', 't-5'));
    const wrapper = await mountView();
    expect(wrapper.find('.denied').exists()).toBe(false);
    expect(wrapper.find('.load-error').text()).toContain('服务异常');
  });
});
