<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showSuccessToast, showToast } from 'vant';
import { KeIcon } from '@ke/shared';
import { ApiError } from '../api/http';
import { getCard } from '../api/cards';
import type { CardDetail } from '../api/cards';
import { changeEntryScope, createEntry, fetchRun, isTerminal, nlDraft, testEntry } from '../api/entries';
import type { EntryConfig, EntryDraftResult, EntryWritten, RunState } from '../api/entries';
import TopBar from '../components/TopBar.vue';

/**
 * 用一句话新增入口（FR-N01–N05/N06/N07 界面，Task 28；04 §7.3 四步 Flow）：
 * ①一句话（≤200 计数 + 3 条官方示例点击填入）→「生成配置」调 nl-draft；OUT_OF_SCOPE 就地给
 * 替代建议 + 返回改写。②草稿表单全字段可编辑（FR-N02）：名称/goal/serviceType 下拉/资料范围
 * 复选（该卡挂接资产清单）/outputSpec；LINK_CARD 为目标卡只读 + 关系词四词下拉 + why + source
 * 文本框（{assetId,quote} JSON 或文字描述，RelationGuard 键约定）——草稿 violations 与保存
 * 400 清单都以红字列在表单顶部。③试运行：保存为私人入口后 POST test 真实执行一次（配额内），
 * 页内简版轮询（2s/终态停/卸载清），DONE=合格、FAILED/TIMEOUT=不合格带原因；链接入口显示
 * 「链接类入口无需试运行」直接下一步。④保存确认双通道：「保存到个人空间」（PRIVATE 已存，
 * 完成回卡页）+「提交至公共区审核」（PUBLIC：PUT scope 挂审核队列——前置审核语义，
 * PENDING 待审态对他人不可见，通过后 ACTIVE 才发布可见，驳回 DISABLED）。
 */
const POLL_MS = 2000;

/** 一句话上限与官方示例（01 §5 示例文案） */
const MAX_TEXT_CHARS = 200;
const SAMPLES = [
  '深入讲讲岳麓书院的讲会制度',
  '比较朱张会讲与鹅湖之会的异同',
  '把相关的书院学规卡片连接为入口'
];

const RELATION_LABELS = ['深入了解', '相关联', '相比较', '去实践'];
const SERVICE_TYPES = [
  { value: 'EXPLAIN', label: '讲解服务（EXPLAIN）' },
  { value: 'COMPARE', label: '比较服务（COMPARE）' }
];

const route = useRoute();
const router = useRouter();

const step = ref(1);
const card = ref<CardDetail | null>(null);
const notFound = ref(false);
const loading = ref(true);

/** 该卡可勾选的挂接资产（sources 里带 assetId 的行；授权过滤以后端 400 清单兜底） */
const assetOptions = ref<Array<{ assetId: number; title: string }>>([]);

// —— Step1 一句话 ——
const text = ref('');
const drafting = ref(false);
/** OUT_OF_SCOPE 替代建议（留在 Step1 展示） */
const advice = ref('');

// —— Step2 草稿表单（全字段可编辑，FR-N02） ——
const violations = ref<string[]>([]);
const form = ref<EntryConfig>({});
const saving = ref(false);
const saveErrors = ref<string[]>([]);

// —— Step3 试运行 ——
const saved = ref<EntryWritten | null>(null);
const run = ref<RunState | null>(null);
const testing = ref(false);
const testError = ref('');
let timer: number | undefined;

// —— Step4 双通道 ——
const submittingPublic = ref(false);
const publicSubmitted = ref(false);

const isLink = computed(() => form.value.type === 'LINK_CARD');
const steps = ['一句话', '配置', '试运行', '保存'];

async function load(): Promise<void> {
  const id = Number(route.params.id);
  if (!Number.isInteger(id) || id <= 0) {
    notFound.value = true;
    loading.value = false;
    return;
  }
  try {
    const detail = await getCard(id);
    card.value = detail;
    notFound.value = detail === null;
    assetOptions.value = (detail?.sources ?? [])
      .filter((s) => s.assetId != null)
      .map((s) => ({ assetId: s.assetId as number, title: s.title }));
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '加载失败,请稍后重试');
    notFound.value = true;
  } finally {
    loading.value = false;
  }
}
void load();

function fillSample(sample: string): void {
  text.value = sample;
  advice.value = '';
}

/** Step1 → 生成配置：调 nl-draft；OUT_OF_SCOPE 就地建议，其余进 Step2（violations 随行展示） */
async function generate(): Promise<void> {
  if (drafting.value || !card.value) return;
  const input = text.value.trim();
  if (!input) {
    showToast('请先用一句话描述想要的入口');
    return;
  }
  drafting.value = true;
  advice.value = '';
  violations.value = [];
  saveErrors.value = [];
  try {
    const result = await nlDraft(card.value.id, input);
    if (result.intent === 'OUT_OF_SCOPE') {
      advice.value = result.advice ?? '暂不支持该类入口';
      return;
    }
    seedForm(result);
    violations.value = result.violations ?? [];
    step.value = 2;
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '生成失败,请稍后重试');
  } finally {
    drafting.value = false;
  }
}

/** 草稿 → 可编辑表单；config=null（跨主题缺三要件/收窄失败）按意图给默认骨架引导补齐 */
function seedForm(result: EntryDraftResult): void {
  const c = result.config;
  const intentType = result.intent === 'LINK_CARD' ? 'LINK_CARD' : 'AGENT_SERVICE';
  const name = c?.name ?? `关于${text.value.trim()}`.slice(0, 30);
  form.value = {
    name,
    type: c?.type ?? intentType,
    goal: c?.goal ?? (intentType === 'AGENT_SERVICE' ? text.value.trim() : ''),
    serviceType: c?.serviceType ?? (result.intent === 'COMPARE' ? 'COMPARE' : 'EXPLAIN'),
    assetScope: c?.assetScope ? [...c.assetScope] : [],
    outputSpec: c?.outputSpec ?? '',
    targetCardId: c?.targetCardId ?? null,
    relationLabel: c?.relationLabel ?? '',
    why: c?.why ?? '',
    source: c?.source ?? ''
  };
}

function toggleAsset(assetId: number, checked: boolean): void {
  const scope = new Set(form.value.assetScope ?? []);
  if (checked) scope.add(assetId);
  else scope.delete(assetId);
  form.value.assetScope = [...scope];
}

/** 表单 → 保存载荷：链接三要件空白归 null（同主题必须为空，后端 Validator 兜底） */
function payload(): EntryConfig {
  const f = form.value;
  const blankToNull = (v?: string | null): string | null => (v && v.trim() ? v.trim() : null);
  return {
    name: (f.name ?? '').trim(),
    type: f.type,
    goal: f.type === 'LINK_CARD' ? null : blankToNull(f.goal),
    serviceType: f.type === 'LINK_CARD' ? null : f.serviceType || null,
    assetScope: f.type === 'LINK_CARD' ? null : (f.assetScope ?? []),
    outputSpec: f.type === 'LINK_CARD' ? null : blankToNull(f.outputSpec),
    targetCardId: f.type === 'LINK_CARD' ? f.targetCardId ?? null : null,
    relationLabel: f.type === 'LINK_CARD' ? blankToNull(f.relationLabel) : null,
    why: f.type === 'LINK_CARD' ? blankToNull(f.why) : null,
    source: f.type === 'LINK_CARD' ? blankToNull(f.source) : null
  };
}

/** Step2 →「保存并试运行」：先存私人入口（试运行需 entryId），LINK_CARD 不发试运行直接下一步 */
async function saveAndTest(): Promise<void> {
  if (saving.value || !card.value) return;
  saveErrors.value = [];
  const p = payload();
  const client: string[] = [];
  if (!p.name) client.push('入口名称不能为空');
  if (p.type === 'AGENT_SERVICE') {
    if (!p.goal) client.push('服务入口必须说明想解决什么(goal)');
    if (!p.assetScope || p.assetScope.length === 0) client.push('服务入口必须选择资料范围(assetScope)');
  }
  if (client.length > 0) {
    saveErrors.value = client;
    return;
  }
  saving.value = true;
  try {
    saved.value = await createEntry(card.value.id, p, 'PRIVATE');
    step.value = 3;
    if (p.type === 'LINK_CARD') {
      run.value = null; // 链接类入口无需试运行：Step3 只显示说明
    } else {
      await startTest();
    }
  } catch (e) {
    const message = e instanceof ApiError ? e.message : '保存失败,请稍后重试';
    saveErrors.value = message.split('; ');
  } finally {
    saving.value = false;
  }
}

// —— Step3 试运行（页内简版轮询，2s/终态停/卸载清） ——

const testPhase = computed<'idle' | 'running' | 'done' | 'failed' | 'error'>(() => {
  if (testError.value) return 'error';
  if (isLink.value) return 'idle';
  if (run.value === null) return 'running';
  if (run.value.status === 'DONE') return 'done';
  if (isTerminal(run.value.status)) return 'failed';
  return 'running';
});

const testReason = computed(() => {
  const r = run.value;
  if (r?.error) return r.error;
  return r?.status === 'TIMEOUT' ? '任务超时,请稍后重试' : '服务繁忙,请稍后重试';
});

/** 试运行 run id（startTest 受理后记录；null=尚未受理） */
const pollRunId = ref<number | null>(null);

async function startTest(): Promise<void> {
  if (testing.value || saved.value == null) return;
  testing.value = true;
  testError.value = '';
  run.value = null;
  try {
    const { runId } = await testEntry(saved.value.entryId);
    pollRunId.value = runId;
    stopPolling();
    timer = window.setInterval(() => {
      void poll();
    }, POLL_MS);
  } catch (e) {
    testError.value = e instanceof ApiError ? e.message : '试运行提交失败,请稍后重试';
  } finally {
    testing.value = false;
  }
}

async function poll(): Promise<void> {
  const id = pollRunId.value;
  if (id == null) return;
  try {
    const state = await fetchRun(id);
    run.value = state;
    if (isTerminal(state.status)) stopPolling();
  } catch {
    // 单次轮询失败静默（下个周期重试），与 SummaryView 同则
  }
}

function stopPolling(): void {
  if (timer !== undefined) {
    window.clearInterval(timer);
    timer = undefined;
  }
}

// —— Step4 双通道 ——

function finish(): void {
  void router.replace(card.value ? `/cards/${card.value.id}` : '/home');
}

async function submitPublic(): Promise<void> {
  if (submittingPublic.value || saved.value == null) return;
  submittingPublic.value = true;
  try {
    // Step2 已存为私人入口 → 此处仅 PUT scope 挂审核队列（前置审核：PENDING 待审态对他人不可见）
    await changeEntryScope(saved.value.entryId, 'PUBLIC');
    saved.value = { ...saved.value, scope: 'PUBLIC' };
    publicSubmitted.value = true;
    showSuccessToast('已提交审核');
  } catch (e) {
    showToast(e instanceof ApiError ? e.message : '提交失败,请稍后重试');
  } finally {
    submittingPublic.value = false;
  }
}

onBeforeUnmount(() => {
  stopPolling();
});
</script>

<template>
  <div class="page">
    <TopBar
      section="新增入口"
      :current="card?.title ?? ''"
    />

    <p
      v-if="loading"
      class="state"
    >
      加载中…
    </p>

    <div
      v-else-if="notFound"
      class="empty"
    >
      <KeIcon
        class="empty-ic"
        name="alert"
      />
      <b class="empty-t">
        卡片不存在或已下架
      </b>
      <span class="empty-s">
        只有已发布的卡片才能创建入口
      </span>
      <button
        type="button"
        class="empty-btn"
        @click="finish"
      >
        返回
      </button>
    </div>

    <main
      v-else-if="card"
      class="wrap"
    >
      <!-- 四步条(04 §7.3 Flow 形态) -->
      <ol
        class="steps"
        aria-label="创建入口四步"
      >
        <li
          v-for="(label, i) in steps"
          :key="label"
          class="step-item"
          :class="{ on: step === i + 1, done: step > i + 1 }"
          :data-step="label"
        >
          <span class="dot">{{ step > i + 1 ? '✓' : i + 1 }}</span>
          <span class="lb">{{ label }}</span>
        </li>
      </ol>

      <!-- Step1 一句话 -->
      <section
        v-if="step === 1"
        class="panel"
      >
        <h1 class="h1">
          用一句话新增入口
        </h1>
        <p class="hint">
          说说你想在这张卡上做什么，我会帮你生成入口配置
        </p>
        <div class="samples">
          <button
            v-for="s in SAMPLES"
            :key="s"
            type="button"
            class="sample"
            @click="fillSample(s)"
          >
            {{ s }}
          </button>
        </div>
        <div class="ta-wrap">
          <textarea
            v-model="text"
            class="ta"
            rows="4"
            :maxlength="MAX_TEXT_CHARS"
            placeholder="例如：深入讲讲岳麓书院的讲会制度"
            aria-label="一句话描述"
          />
          <span class="count">{{ text.length }}/{{ MAX_TEXT_CHARS }}</span>
        </div>
        <button
          type="button"
          class="primary"
          :disabled="drafting || !text.trim()"
          data-test="generate"
          @click="generate"
        >
          {{ drafting ? '生成中…' : '生成配置' }}
        </button>
        <div
          v-if="advice"
          class="advice"
          role="status"
        >
          {{ advice }}
          <button
            type="button"
            class="ghost"
            @click="advice = ''"
          >
            返回修改
          </button>
        </div>
      </section>

      <!-- Step2 配置 -->
      <section
        v-else-if="step === 2"
        class="panel"
      >
        <h1 class="h1">
          确认入口配置
        </h1>
        <p class="hint">
          所有字段都可以修改，确认后先保存为私人入口再试运行
        </p>

        <ul
          v-if="violations.length || saveErrors.length"
          class="violations"
          role="alert"
          data-test="violations"
        >
          <li
            v-for="v in [...violations, ...saveErrors]"
            :key="v"
          >
            {{ v }}
          </li>
        </ul>

        <label class="fld">
          <span class="fl">入口名称</span>
          <input
            v-model="form.name"
            class="fi"
            maxlength="60"
            placeholder="给入口起个名字"
          >
        </label>

        <template v-if="!isLink">
          <label class="fld">
            <span class="fl">想解决什么(goal)</span>
            <textarea
              v-model="form.goal"
              class="fi ta-s"
              rows="2"
              maxlength="200"
              placeholder="例如：讲清讲会制度"
            />
          </label>
          <label class="fld">
            <span class="fl">服务类型</span>
            <select
              v-model="form.serviceType"
              class="fi"
            >
              <option
                v-for="t in SERVICE_TYPES"
                :key="t.value"
                :value="t.value"
              >
                {{ t.label }}
              </option>
            </select>
          </label>
          <div class="fld">
            <span class="fl">资料范围(仅限已挂接的知识单元)</span>
            <div
              v-if="assetOptions.length"
              class="assets"
            >
              <label
                v-for="a in assetOptions"
                :key="a.assetId"
                class="asset"
              >
                <input
                  type="checkbox"
                  :checked="(form.assetScope ?? []).includes(a.assetId)"
                  @change="toggleAsset(a.assetId, ($event.target as HTMLInputElement).checked)"
                >
                <span>{{ a.title }}</span>
              </label>
            </div>
            <p
              v-else
              class="none-note"
            >
              该卡暂无挂接知识单元,请先在知识资源挂接后再创建服务入口
            </p>
          </div>
          <label class="fld">
            <span class="fl">输出要求(outputSpec,可选)</span>
            <input
              v-model="form.outputSpec"
              class="fi"
              maxlength="120"
              placeholder="例如：摘要+分节正文"
            >
          </label>
        </template>

        <template v-else>
          <div class="fld">
            <span class="fl">目标卡片</span>
            <p
              class="ro"
              data-test="target-card"
            >
              草稿识别的目标卡（id:{{ form.targetCardId ?? '—' }}）
            </p>
          </div>
          <label class="fld">
            <span class="fl">关系词(仅跨主题需要)</span>
            <select
              v-model="form.relationLabel"
              class="fi"
            >
              <option
                value=""
              >
                不填(同主题链接)
              </option>
              <option
                v-for="r in RELATION_LABELS"
                :key="r"
                :value="r"
              >
                {{ r }}
              </option>
            </select>
          </label>
          <label class="fld">
            <span class="fl">为什么相关(why,仅跨主题需要)</span>
            <textarea
              v-model="form.why"
              class="fi ta-s"
              rows="2"
              maxlength="200"
              placeholder="说明两个主题为什么相关"
            />
          </label>
          <label class="fld">
            <span class="fl">出处(source,仅跨主题需要)</span>
            <textarea
              v-model="form.source"
              class="fi ta-s"
              rows="2"
              maxlength="300"
              placeholder="格式:{&quot;assetId&quot;:11,&quot;quote&quot;:&quot;原文摘录&quot;} 或一段文字说明"
            />
          </label>
        </template>

        <button
          type="button"
          class="primary"
          :disabled="saving"
          data-test="save-and-test"
          @click="saveAndTest"
        >
          {{ saving ? '保存中…' : '保存并试运行' }}
        </button>
        <button
          type="button"
          class="ghost back-draft"
          @click="step = 1"
        >
          返回改一句话
        </button>
      </section>

      <!-- Step3 试运行 -->
      <section
        v-else-if="step === 3"
        class="panel"
        data-test="test-panel"
      >
        <h1 class="h1">
          试运行
        </h1>
        <template v-if="isLink">
          <p class="notice">
            链接类入口无需试运行
          </p>
          <button
            type="button"
            class="primary"
            @click="step = 4"
          >
            下一步
          </button>
        </template>
        <template v-else>
          <p
            v-if="testPhase === 'error'"
            class="violations"
            role="alert"
          >
            {{ testError }}
          </p>
          <div
            v-else-if="testPhase === 'running'"
            class="running"
          >
            <span
              class="spin"
              aria-hidden="true"
            />
            正在试运行…
          </div>
          <div
            v-else-if="testPhase === 'done'"
            class="result ok"
          >
            <b>试运行通过</b>
            <span>入口可以按配置真实执行，已计入试运行次数</span>
          </div>
          <div
            v-else
            class="result bad"
          >
            <b>试运行未通过</b>
            <span>{{ testReason }}</span>
            <button
              type="button"
              class="ghost"
              :disabled="testing"
              @click="startTest"
            >
              重试试运行
            </button>
          </div>
          <p class="hint">
            试运行在每日智能服务限额内真实执行一次
          </p>
          <button
            type="button"
            class="primary"
            :disabled="testPhase === 'running'"
            @click="step = 4"
          >
            下一步
          </button>
        </template>
      </section>

      <!-- Step4 保存确认（双通道） -->
      <section
        v-else
        class="panel"
        data-test="channel-panel"
      >
        <h1 class="h1">
          完成创建
        </h1>
        <p
          v-if="publicSubmitted"
          class="pub-ok"
          role="status"
        >
          已提交审核，通过后将在公共区对所有人可见
        </p>
        <p
          v-else
          class="hint"
        >
          入口已保存。可以留在个人空间，或提交到公共区供所有探索者使用
        </p>
        <button
          v-if="!publicSubmitted"
          type="button"
          class="primary"
          data-channel="private"
          @click="finish"
        >
          保存到个人空间
        </button>
        <button
          v-if="!publicSubmitted"
          type="button"
          class="ghost"
          data-channel="public"
          :disabled="submittingPublic"
          @click="submitPublic"
        >
          {{ submittingPublic ? '提交中…' : '提交至公共区审核' }}
        </button>
        <button
          v-else
          type="button"
          class="primary"
          data-channel="done"
          @click="finish"
        >
          完成
        </button>
      </section>
    </main>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52px 16px 60px; background: var(--ke-bg); padding-top: 80px; padding-right: 24px; padding-left: 24px; }
.wrap { margin: 14px auto 0; max-width: 480px; display: flex; flex-direction: column; gap: 14px; }
.state { margin: 40px 0 0; text-align: center; font-size: 12px; color: var(--ke-sub); }
.empty { margin: 60px auto 0; max-width: 480px; display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center; }
.empty-ic { width: 34px; height: 34px; color: var(--ke-sub-2); }
.empty-t { color: var(--ke-ink); font-size: 15px; }
.empty-s { color: var(--ke-sub); font-size: 12px; }
.empty-btn { margin-top: 8px; padding: 9px 22px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-primary); font-size: 13px; font-weight: 600; cursor: pointer; }

.steps { display: flex; gap: 4px; margin: 0; padding: 10px 12px; list-style: none; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.step-item { flex: 1; display: flex; align-items: center; justify-content: center; gap: 5px; color: var(--ke-sub-2); font-size: 11px; }
.step-item .dot { display: flex; width: 18px; height: 18px; align-items: center; justify-content: center; border-radius: var(--ke-radius-full); background: var(--ke-surface-2); font-size: 10px; font-weight: 700; }
.step-item.on { color: var(--ke-primary); font-weight: 700; }
.step-item.on .dot { background: var(--ke-primary); color: var(--ke-white); }
.step-item.done { color: var(--ke-primary); }
.step-item.done .dot { background: var(--ke-primary-soft); color: var(--ke-primary); }

.panel { display: flex; flex-direction: column; gap: 12px; padding: 18px 16px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); }
.h1 { margin: 0; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 19px; font-weight: 700; }
.hint { margin: 0; color: var(--ke-sub); font-size: 12px; line-height: 1.6; }
.samples { display: flex; flex-direction: column; gap: 6px; }
.sample { padding: 9px 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); color: var(--ke-ink-2); font-size: 12px; text-align: left; cursor: pointer; }
.sample:active { border-color: var(--ke-primary); color: var(--ke-primary); }
.ta-wrap { position: relative; }
.ta { width: 100%; box-sizing: border-box; padding: 10px 12px 24px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-m); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; font-family: var(--ke-font); line-height: 1.6; resize: vertical; }
.count { position: absolute; right: 10px; bottom: 8px; color: var(--ke-sub-2); font-size: 10px; }
.primary { padding: 12px; border: none; border-radius: var(--ke-radius-full); background: var(--ke-primary); color: var(--ke-white); font-size: 14px; font-weight: 700; font-family: var(--ke-font); cursor: pointer; }
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.ghost { padding: 10px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-sub); font-size: 12px; cursor: pointer; }
.advice { display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid var(--ke-warn-line); border-radius: var(--ke-radius-m); background: var(--ke-warn-soft); color: var(--ke-warn); font-size: 12px; line-height: 1.6; }
.violations { margin: 0; padding: 10px 12px 10px 26px; border-radius: var(--ke-radius-m); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 12px; line-height: 1.7; }
.fld { display: flex; flex-direction: column; gap: 5px; }
.fl { color: var(--ke-ink-2); font-size: 12px; font-weight: 600; }
.fi { width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-m); background: var(--ke-surface); color: var(--ke-ink); font-size: 13px; font-family: var(--ke-font); }
.ta-s { resize: vertical; line-height: 1.6; }
.assets { display: flex; flex-direction: column; gap: 6px; padding: 10px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); }
.asset { display: flex; align-items: center; gap: 8px; color: var(--ke-ink-2); font-size: 12px; }
.none-note { margin: 0; padding: 10px 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-m); background: var(--ke-warn-soft); color: var(--ke-warn); font-size: 12px; }
.ro { margin: 0; padding: 9px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface-2); color: var(--ke-sub); font-size: 12px; }
.notice { margin: 0; padding: 12px; border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 13px; }
.running { display: flex; align-items: center; gap: 10px; color: var(--ke-sub); font-size: 13px; }
.spin { width: 16px; height: 16px; border: 2px solid var(--ke-line-strong); border-top-color: var(--ke-primary); border-radius: var(--ke-radius-full); animation: ke-spin 0.9s linear infinite; }

@keyframes ke-spin { to { transform: rotate(360deg); } }
.result { display: flex; flex-direction: column; gap: 6px; padding: 14px; border-radius: var(--ke-radius-m); font-size: 13px; }
.result.ok { background: var(--ke-success-soft); color: var(--ke-success); }
.result.bad { background: var(--ke-danger-soft); color: var(--ke-danger); }
.result span { font-size: 12px; opacity: 0.85; }
.pub-ok { margin: 0; padding: 12px; border-radius: var(--ke-radius-m); background: var(--ke-success-soft); color: var(--ke-success); font-size: 13px; }
.back-draft { align-self: flex-start; }
</style>
