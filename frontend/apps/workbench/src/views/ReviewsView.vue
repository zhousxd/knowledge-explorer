<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { approveReview, listReviews, rejectReview } from '../api/reviews';
import { ApiError } from '../api/http';
import type { ReviewItem, ReviewObjectType } from '../api/types';
import { TEMPLATE_LABELS } from '../cardMeta';
import { formatDateTime } from '../format';
import { useReviewStore } from '../stores/review';
import WbDenied from '../components/WbDenied.vue';

/**
 * 审核中心(FR-O03):卡片/入口两队列,每项一张审核卡(对象 + 机器预检标签(仅卡片) +
 * 内容预览(仅卡片) + 提交人/时间),通过一击、驳回三步(点开 + 填意见 + 确认,意见必填)。
 * 裁决成功后重拉队列并联动侧栏待审计数徽标(reviewStore)。
 * 入口队列(Task 28):公共入口送审进同一队列——ENTRY 无卡片内容语义,precheck/contentPreview
 * 恒 null(渲染需容错),摘要「入口名 · 所属卡题 · 提交人」;前置审核:通过=发布生效(PENDING→ACTIVE),驳回=下架。
 */
const reviewStore = useReviewStore();

/** 与后端 offset 契约一致:size ≤ 100,默认 20 */
const PAGE_SIZE = 20;

/** 对象类型 → chip 文案 */
const OBJECT_LABELS: Record<ReviewObjectType, string> = {
  CARD: '卡片',
  ENTRY: '入口'
};

const activeQueue = ref<ReviewObjectType>('CARD');
const items = ref<ReviewItem[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const errorMsg = ref('');
/** 后端 403(CREATOR/EXPLORER)→ 权限空态 */
const denied = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  errorMsg.value = '';
  try {
    const data = await listReviews({
      status: 'PENDING',
      objectType: activeQueue.value,
      page: page.value,
      size: PAGE_SIZE
    });
    items.value = data.items;
    total.value = data.total;
  } catch (e) {
    if (e instanceof ApiError && e.code === 403) {
      denied.value = true;
      items.value = [];
    } else {
      errorMsg.value = e instanceof Error ? e.message : '加载失败,请稍后重试';
    }
  } finally {
    loading.value = false;
  }
}

/** 队列 tab 切换(卡片/入口两队列均开放;入口队列 Task 28 解禁) */
function switchQueue(queue: ReviewObjectType): void {
  if (activeQueue.value === queue) {
    return;
  }
  activeQueue.value = queue;
  page.value = 1;
  items.value = [];
  void load();
}

/** summary 形如「标题 · 模板 · 提交人」,拆三段展示;缺段以 — 兜底 */
function parseSummary(item: ReviewItem): { title: string; template: string; submitter: string } {
  const parts = (item.summary ?? '').split(' · ');
  return {
    title: parts[0] ?? '',
    template: parts[1] ?? '',
    submitter: parts[2] ?? ''
  };
}

const templateLabel = (item: ReviewItem): string => TEMPLATE_LABELS[parseSummary(item).template] ?? parseSummary(item).template;

// ---------- 裁决 ----------

/** 裁决进行中的任务 id:期间两钮禁用、同/异任务重复触发直接忽略;队列重拉完成才释放,
 *  防止「成功 toast 刚出、双击又吃一个『该审核任务已被处理』错误 toast」 */
const busyId = ref<number | null>(null);

/** 通过并发布:一击,不填意见(通过不允许意见,保持单步) */
async function onApprove(item: ReviewItem): Promise<void> {
  if (busyId.value !== null) {
    return;
  }
  busyId.value = item.id;
  try {
    try {
      await approveReview(item.id);
      // 卡片通过即发布;入口前置审核:通过=发布生效(PENDING→ACTIVE 对他人可见,Task 28)
      const verb = item.objectType === 'ENTRY' ? '已通过' : '已通过并发布';
      ElMessage.success(`${verb}「${parseSummary(item).title}」`);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
      return;
    }
    await afterAction();
  } finally {
    busyId.value = null;
  }
}

/** 驳回:点开意见框 → 填意见 → 确认,共 3 击;意见必填(空/空白时确认禁用) */
const rejectingId = ref<number | null>(null);
const notes = ref('');

function openReject(item: ReviewItem): void {
  rejectingId.value = item.id;
  notes.value = '';
}

function closeReject(): void {
  rejectingId.value = null;
  notes.value = '';
}

const notesFilled = computed(() => notes.value.trim().length > 0);

async function onConfirmReject(item: ReviewItem): Promise<void> {
  if (busyId.value !== null) {
    return;
  }
  busyId.value = item.id;
  try {
    try {
      await rejectReview(item.id, notes.value.trim());
      const tail = item.objectType === 'ENTRY' ? ',已下架' : ',退回草稿';
      ElMessage.success(`已驳回「${parseSummary(item).title}」${tail}`);
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
      return;
    }
    closeReject();
    await afterAction();
  } finally {
    busyId.value = null;
  }
}

/** 裁决成功后:重拉当前队列 + 刷新侧栏待审计数徽标 */
async function afterAction(): Promise<void> {
  await load();
  await reviewStore.refreshPendingTotal();
}

function onPageChange(value: number): void {
  page.value = value;
  void load();
}

onMounted(() => {
  void load();
  void reviewStore.refreshPendingTotal();
});
</script>

<template>
  <section class="page">
    <h2 class="page-title">
      审核中心
    </h2>

    <div class="queue-tabs">
      <div
        class="tabs"
        role="tablist"
        aria-label="审核队列"
      >
        <button
          class="queue-tab"
          :class="{ 'is-active': activeQueue === 'CARD' }"
          type="button"
          role="tab"
          :aria-selected="activeQueue === 'CARD'"
          data-queue="CARD"
          @click="switchQueue('CARD')"
        >
          卡片审核
        </button>
        <button
          class="queue-tab"
          :class="{ 'is-active': activeQueue === 'ENTRY' }"
          type="button"
          role="tab"
          :aria-selected="activeQueue === 'ENTRY'"
          data-queue="ENTRY"
          @click="switchQueue('ENTRY')"
        >
          入口审核
        </button>
      </div>
    </div>

    <WbDenied v-if="denied">
      审核中心仅对编辑/运营角色开放,如需权限请联系运营开通
    </WbDenied>

    <template v-else>
      <p
        v-if="errorMsg"
        class="load-error"
        role="alert"
      >
        {{ errorMsg }}
      </p>

      <div
        v-loading="loading"
        class="card-list"
      >
        <article
          v-for="item in items"
          :key="item.id"
          class="review-card"
        >
          <header class="card-head">
            <span class="obj-chip">{{ OBJECT_LABELS[item.objectType] ?? item.objectType }}</span>
            <h3 class="obj-name">
              {{ parseSummary(item).title || '未命名对象' }}
            </h3>
            <span
              v-if="item.objectType === 'CARD' && parseSummary(item).template"
              class="tpl-chip"
            >{{ templateLabel(item) }}</span>
          </header>

          <!-- 机器预检仅卡片有(ENTRY 无卡片内容语义,precheck 恒 null——渲染容错) -->
          <div
            v-if="item.objectType === 'CARD'"
            class="precheck"
          >
            <el-tag
              :type="item.precheck?.contentValid ? 'success' : 'info'"
              size="small"
              disable-transitions
            >
              内容可解析
            </el-tag>
            <el-tag
              :type="item.precheck?.hasSources ? 'success' : 'warning'"
              size="small"
              disable-transitions
            >
              来源齐备
            </el-tag>
            <el-tag
              type="info"
              size="small"
              disable-transitions
            >
              机器预检
            </el-tag>
          </div>

          <p
            v-if="item.objectType === 'CARD'"
            class="preview"
          >
            {{ item.contentPreview ?? '内容暂不可预览' }}
          </p>

          <div class="kv">
            <span>提交人:{{ parseSummary(item).submitter || '—' }}</span>
            <span v-if="item.objectType === 'ENTRY'">所属卡片:{{ parseSummary(item).template || '—' }}</span>
            <span>提交时间:{{ formatDateTime(item.createdAt) }}</span>
          </div>

          <footer class="actions">
            <el-button
              class="act-approve"
              type="success"
              :disabled="busyId !== null"
              @click="onApprove(item)"
            >
              {{ item.objectType === 'ENTRY' ? '通过' : '通过并发布' }}
            </el-button>
            <el-button
              class="act-reject"
              type="danger"
              plain
              :disabled="busyId !== null"
              @click="openReject(item)"
            >
              驳回
            </el-button>
          </footer>

          <div
            v-if="rejectingId === item.id"
            class="reject-box"
          >
            <el-input
              v-model="notes"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="请填写驳回意见(必填),将退回给提交人"
            />
            <div class="reject-actions">
              <el-button
                class="confirm-reject"
                type="danger"
                :disabled="!notesFilled || busyId !== null"
                @click="onConfirmReject(item)"
              >
                确认驳回
              </el-button>
              <el-button
                class="cancel-reject"
                @click="closeReject"
              >
                取消
              </el-button>
            </div>
          </div>
        </article>

        <p
          v-if="!loading && items.length === 0"
          class="empty"
        >
          暂无待审内容
        </p>
      </div>

      <footer class="page-foot">
        <el-pagination
          layout="total, prev, pager, next"
          :total="total"
          :page-size="PAGE_SIZE"
          :current-page="page"
          @current-change="onPageChange"
        />
      </footer>
    </template>
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; }
.page-title { margin: 0; color: var(--ke-ink); font-size: 18px; }
.queue-tabs { display: flex; align-items: center; gap: 12px; }
.tabs { display: flex; gap: 8px; }
.queue-tab { height: 28px; padding: 0 14px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-sub); font-size: 12px; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease), color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.queue-tab:hover { border-color: var(--ke-primary); color: var(--ke-primary); }
.queue-tab.is-active { border-color: var(--ke-primary); background: var(--ke-primary); color: var(--ke-white); }

.load-error { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.card-list { display: flex; flex-direction: column; gap: 12px; min-height: 120px; }
.review-card { display: flex; flex-direction: column; gap: 10px; padding: 16px 18px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface); }
.card-head { display: flex; align-items: center; gap: 10px; }
.obj-chip { flex-shrink: 0; padding: 2px 10px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; font-weight: 600; }
.obj-name { margin: 0; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 16px; font-weight: 700; }
.tpl-chip { flex-shrink: 0; padding: 2px 8px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 600; }
.precheck { display: flex; align-items: center; gap: 8px; }
.preview { margin: 0; color: var(--ke-sub); font-size: 13px; line-height: 1.6; }
.kv { display: flex; gap: 18px; color: var(--ke-sub); font-size: 12px; }
.actions { display: flex; gap: 10px; }
.reject-box { display: flex; flex-direction: column; gap: 10px; padding: 12px; border: 1px dashed var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-bg); }
.reject-actions { display: flex; justify-content: flex-end; }
.empty { margin: 0; padding: 40px 0; text-align: center; color: var(--ke-sub); font-size: 13px; }
.page-foot { display: flex; justify-content: flex-end; }
</style>
