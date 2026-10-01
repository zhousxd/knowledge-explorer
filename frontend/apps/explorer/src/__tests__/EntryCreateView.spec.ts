import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { getCard } from '../api/cards';
import { changeEntryScope, createEntry, fetchRun, nlDraft, testEntry } from '../api/entries';
import type { CardDetail } from '../api/cards';
import type { EntryDraftResult, RunState } from '../api/entries';
import EntryCreateView from '../views/EntryCreateView.vue';

vi.mock('vant', () => ({ showToast: vi.fn(), showSuccessToast: vi.fn() }));
vi.mock('../api/cards', () => ({ getCard: vi.fn() }));
vi.mock('../api/entries', () => ({
  nlDraft: vi.fn(),
  createEntry: vi.fn(),
  testEntry: vi.fn(),
  changeEntryScope: vi.fn(),
  fetchRun: vi.fn(),
  // 工厂清掉了原模块,统一钉冻结语义(与 RunView.spec 同则)
  isTerminal: (status: string) => ['DONE', 'FAILED', 'TIMEOUT'].includes(status)
}));

const mockedGetCard = vi.mocked(getCard);
const mockedDraft = vi.mocked(nlDraft);
const mockedCreate = vi.mocked(createEntry);
const mockedTest = vi.mocked(testEntry);
const mockedScope = vi.mocked(changeEntryScope);
const mockedFetchRun = vi.mocked(fetchRun);

const CARD: CardDetail = {
  id: 7,
  theme: 'academy',
  templateType: 'TEXT',
  title: '岳麓书院',
  cardVersionId: 11,
  versionNo: 1,
  updatedAt: '2026-09-30T10:00:00Z',
  favorited: false,
  content: {},
  sources: [
    { assetId: 11, title: '《岳麓志》', locator: '第1页' },
    { assetId: 12, title: '《书院志》', locator: '第2页' }
  ]
};

const EXPLAIN_DRAFT: EntryDraftResult = {
  intent: 'EXPLAIN',
  config: {
    name: '讲讲岳麓书院',
    type: 'AGENT_SERVICE',
    goal: '讲清讲会制度',
    serviceType: 'EXPLAIN',
    assetScope: [11],
    outputSpec: '摘要+分节正文'
  },
  violations: [],
  advice: null
};

function runState(patch: Partial<RunState>): RunState {
  return {
    runId: 5, status: 'RUNNING', serviceType: null, model: null, latencyMs: null, error: null,
    artifact: null, submitContext: null, ...patch
  };
}

/** 直挂 memory 路由(受保护路由本地直开,不走登录守卫);CardView 接线经真实跳转由 card 路由承接 */
async function mountCreate(): Promise<ReturnType<typeof mount>> {
  const local = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/cards/:id(\\d+)/entry/new', component: EntryCreateView },
      { path: '/cards/:id(\\d+)', component: { render: () => null } }
    ]
  });
  await local.push('/cards/7/entry/new');
  await local.isReady();
  const wrapper = mount(EntryCreateView, { global: { plugins: [local] } });
  await flushPromises();
  return wrapper;
}

async function gotoStep2(wrapper: ReturnType<typeof mount>, draft: EntryDraftResult = EXPLAIN_DRAFT): Promise<void> {
  mockedDraft.mockResolvedValue(draft);
  await wrapper.find('.ta').setValue('深入讲讲岳麓书院的讲会制度');
  await wrapper.find('[data-test="generate"]').trigger('click');
  await flushPromises();
}

describe('EntryCreateView(用一句话新增入口四步流,FR-N01–N05)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockedGetCard.mockResolvedValue(CARD);
    mockedCreate.mockResolvedValue({ entryId: 201, scope: 'PRIVATE', status: 'ACTIVE' });
    mockedTest.mockResolvedValue({ runId: 5, testTotal: 1 });
    mockedScope.mockResolvedValue({ entryId: 201, scope: 'PUBLIC', status: 'ACTIVE' });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('主链:一句话→草稿表单可编辑→保存私人入口→试运行 DONE 合格→双通道(私人完成回卡页)', async () => {
    const wrapper = await mountCreate();
    expect(wrapper.text()).toContain('用一句话新增入口');
    expect(wrapper.findAll('.sample')).toHaveLength(3);

    // 示例点击填入
    await wrapper.findAll('.sample')[0]!.trigger('click');
    expect((wrapper.find('.ta').element as HTMLTextAreaElement).value).toContain('深入讲讲');

    mockedDraft.mockResolvedValue(EXPLAIN_DRAFT);
    await wrapper.find('[data-test="generate"]').trigger('click');
    await flushPromises();
    expect(mockedDraft).toHaveBeenCalledWith(7, '深入讲讲岳麓书院的讲会制度');

    // Step2:草稿字段回填且可编辑,授权资料复选带标题
    const name = wrapper.find('input.fi');
    expect((name.element as HTMLInputElement).value).toBe('讲讲岳麓书院');
    await name.setValue('讲会制度入口');
    const boxes = wrapper.findAll('.asset input[type="checkbox"]');
    expect(boxes).toHaveLength(2);
    expect((boxes[0]!.element as HTMLInputElement).checked).toBe(true); // 草稿 assetScope=[11]
    expect(wrapper.find('.assets').text()).toContain('《岳麓志》');

    vi.useFakeTimers();
    mockedFetchRun.mockResolvedValue(runState({}));
    await wrapper.find('[data-test="save-and-test"]').trigger('click');
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledWith(7, expect.objectContaining({
      name: '讲会制度入口', type: 'AGENT_SERVICE', serviceType: 'EXPLAIN', assetScope: [11]
    }), 'PRIVATE');
    expect(mockedTest).toHaveBeenCalledWith(201);

    // 轮询至终态:第一拍 RUNNING,第二拍 DONE → 合格
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    mockedFetchRun.mockResolvedValue(runState({ status: 'DONE' }));
    await vi.advanceTimersByTimeAsync(2000);
    await flushPromises();
    expect(mockedFetchRun).toHaveBeenCalledWith(5);
    expect(wrapper.find('[data-test="test-panel"]').text()).toContain('试运行通过');

    await wrapper.find('[data-test="test-panel"] .primary').trigger('click');
    expect(wrapper.find('[data-test="channel-panel"]').exists()).toBe(true);
    expect(wrapper.find('[data-channel="private"]').text()).toContain('保存到个人空间');
    expect(wrapper.find('[data-channel="public"]').text()).toContain('提交至公共区审核');

    await wrapper.find('[data-channel="private"]').trigger('click');
    await flushPromises();
    expect(wrapper.vm.$route.path).toBe('/cards/7');
  });

  it('OUT_OF_SCOPE:就地给替代建议,不进配置步', async () => {
    const wrapper = await mountCreate();
    mockedDraft.mockResolvedValue({
      intent: 'OUT_OF_SCOPE', config: null, violations: [], advice: '暂不支持该类入口。可以试试:深入了解某个问题'
    });
    await wrapper.find('.ta').setValue('帮我订机票');
    await wrapper.find('[data-test="generate"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('.advice').text()).toContain('暂不支持该类入口');
    expect(wrapper.find('.advice .ghost').text()).toContain('返回修改');
    // 留在第一步:没有配置表单
    expect(wrapper.find('input.fi').exists()).toBe(false);
  });

  it('violations 红字渲染:草稿违规带进 Step2,保存端 400 清单逐条显示', async () => {
    const wrapper = await mountCreate();
    mockedDraft.mockResolvedValue({
      intent: 'EXPLAIN', config: null,
      violations: ['服务类型不在白名单: MAP'], advice: null
    });
    await wrapper.find('.ta').setValue('讲讲岳麓书院');
    await wrapper.find('[data-test="generate"]').trigger('click');
    await flushPromises();

    const list = wrapper.find('[data-test="violations"]');
    expect(list.exists()).toBe(true);
    expect(list.text()).toContain('服务类型不在白名单: MAP');

    // 客户端守卫:未选资料范围先拦,不打后端
    await wrapper.find('[data-test="save-and-test"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-test="violations"]').text()).toContain('服务入口必须选择资料范围');
    expect(mockedCreate).not.toHaveBeenCalled();

    // 勾选资料后提交,后端 400 清单按「; 」拆条显示
    await wrapper.findAll('.asset input[type="checkbox"]')[0]!.setValue(true);
    mockedCreate.mockRejectedValueOnce(new ApiError(400, '资料范围越权:资产 9 不在授权集;服务类型不在白名单: MAP', 't'));
    await wrapper.find('[data-test="save-and-test"]').trigger('click');
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledTimes(1);
    const text = wrapper.find('[data-test="violations"]').text();
    expect(text).toContain('资料范围越权:资产 9 不在授权集');
    expect(text).toContain('服务类型不在白名单: MAP');
  });

  it('LINK_CARD 分支:目标卡只读+关系三要件采集;试运行说明「无需」,公共通道走 scope 切换', async () => {
    const wrapper = await mountCreate();
    await gotoStep2(wrapper, {
      intent: 'LINK_CARD',
      config: { name: '关于把相关的书院学规卡片连接为入口'.slice(0, 12), type: 'LINK_CARD', targetCardId: 9 },
      violations: [],
      advice: null
    });
    expect(wrapper.find('[data-test="target-card"]').text()).toContain('id:9');
    // 服务块隐藏,关系词四词下拉在
    expect(wrapper.find('.assets').exists()).toBe(false);
    expect(wrapper.text()).toContain('深入了解');

    await wrapper.find('[data-test="save-and-test"]').trigger('click');
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledWith(7, expect.objectContaining({
      type: 'LINK_CARD', targetCardId: 9, relationLabel: null, serviceType: null
    }), 'PRIVATE');
    // 链接类入口无需试运行:不打 test
    expect(mockedTest).not.toHaveBeenCalled();
    expect(wrapper.find('[data-test="test-panel"]').text()).toContain('链接类入口无需试运行');

    await wrapper.find('[data-test="test-panel"] .primary').trigger('click');
    await wrapper.find('[data-channel="public"]').trigger('click');
    await flushPromises();
    // 已存私人入口 → 公共通道调 PUT scope 挂审核
    expect(mockedScope).toHaveBeenCalledWith(201, 'PUBLIC');
    expect(wrapper.find('[data-test="channel-panel"]').text()).toContain('已提交审核');
  });

  it('LINK_CARD 跨主题 violations 态:config 回填目标卡只读位,补齐三要件保存不再死路', async () => {
    const wrapper = await mountCreate();
    // 跨主题命中(草稿不代填 why/source):violations 三要件缺失,但 config 仍携带目标卡
    await gotoStep2(wrapper, {
      intent: 'LINK_CARD',
      config: { name: '关于对比两院学规', type: 'LINK_CARD', targetCardId: 9 },
      violations: ['跨主题入口必须说明关系原因(why)', '跨主题入口必须注明出处(source)'],
      advice: null
    });
    // 目标卡回填只读位(id:9),violations 红字随行
    expect(wrapper.find('[data-test="target-card"]').text()).toContain('id:9');
    expect(wrapper.find('[data-test="violations"]').text()).toContain('跨主题入口必须说明关系原因(why)');

    // 补齐三要件后保存:载荷携带 targetCardId,不再触发「链接入口必须指定目标卡片」400
    await wrapper.find('select.fi').setValue('相关联');
    const tas = wrapper.findAll('textarea.fi');
    await tas[0]!.setValue('两地学规同源,对照可读');
    await tas[1]!.setValue('{"assetId":11,"quote":"学规原文"}');
    await wrapper.find('[data-test="save-and-test"]').trigger('click');
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledWith(7, expect.objectContaining({
      type: 'LINK_CARD', targetCardId: 9, relationLabel: '相关联'
    }), 'PRIVATE');
    expect(mockedTest).not.toHaveBeenCalled();
  });
});
