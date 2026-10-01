<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { fetchMetrics } from '../api/metrics';
import type { MetricsResp } from '../api/metrics';
import { ApiError } from '../api/http';
import WbDenied from '../components/WbDenied.vue';

/**
 * 数据看板(FR-O05 界面):GET /api/wb/metrics(P8-33 契约,最近 7 天窗口)一次返回六键,
 * 渲染为指标卡矩阵——五率卡(百分比,rate*100 保留 1 位) + 服务时延(ms)/成本(¥/次)卡;
 * 键值 null(空窗口分母为 0 / 成本网关二期未采集)→「暂无数据」灰态,不显示 0。
 * 专题速览与周趋势柱状图无对应聚合端点(按日/按专题),注明二期;403(EXPLORER)→ 权限空态。
 */

/** 指标卡定义:key 对应 MetricsResp 键;kind 决定取值格式化(率=百分比/时延=ms/成本=¥) */
interface MetricCard {
  key: keyof MetricsResp;
  label: string;
  kind: 'rate' | 'ms' | 'cost';
}

const CARDS: MetricCard[] = [
  { key: 'deepenRate', label: '有效深入率', kind: 'rate' },
  { key: 'artifactSaveRate', label: '成果保存率', kind: 'rate' },
  { key: 'shareContinueRate', label: '分享接续率', kind: 'rate' },
  { key: 'entrySuccessRate', label: '入口成功率', kind: 'rate' },
  { key: 'sourceCompleteRate', label: '来源完整率', kind: 'rate' },
  { key: 'avgLatencyMs', label: '服务时延', kind: 'ms' },
  { key: 'avgCost', label: '服务成本', kind: 'cost' }
];

const metrics = ref<MetricsResp | null>(null);
const loading = ref(false);
const errorMsg = ref('');
/** 后端 403(EXPLORER 越权)→ 权限空态 */
const denied = ref(false);

async function load(): Promise<void> {
  loading.value = true;
  errorMsg.value = '';
  try {
    metrics.value = await fetchMetrics();
  } catch (e) {
    if (e instanceof ApiError && e.code === 403) {
      denied.value = true;
      metrics.value = null;
    } else {
      errorMsg.value = e instanceof Error ? e.message : '加载失败,请稍后重试';
    }
  } finally {
    loading.value = false;
  }
}

/** 卡片取值文本:率=百分比(1 位小数);时延=ms 取整;成本=¥/次(2 位);null → 暂无数据 */
function valueOf(card: MetricCard): string {
  const v = metrics.value?.[card.key];
  if (v === null || v === undefined) return '暂无数据';
  if (card.kind === 'rate') return `${(v * 100).toFixed(1)}%`;
  if (card.kind === 'ms') return `${Math.round(v)}ms`;
  return `¥${v.toFixed(2)}/次`;
}

const isNull = (card: MetricCard): boolean => metrics.value?.[card.key] == null;

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="page">
    <h2 class="page-title">
      数据看板
    </h2>

    <WbDenied v-if="denied">
      数据看板仅对编辑/运营角色开放,如需权限请联系运营开通
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
        class="stat-grid"
      >
        <article
          v-for="card in CARDS"
          :key="card.key"
          class="stat-card"
          :data-metric="card.key"
        >
          <p class="stat-label">
            {{ card.label }}
          </p>
          <p
            class="stat-value tabular"
            :class="{ 'is-null': isNull(card) }"
          >
            {{ valueOf(card) }}
          </p>
          <p class="stat-window">
            最近 7 天
          </p>
        </article>
      </div>

      <p class="phase-note">
        专题速览与周趋势柱状图需按专题/按日聚合端点,规划于二期上线。
      </p>
    </template>
  </section>
</template>

<style scoped>
.page { display: flex; flex-direction: column; gap: 14px; }
.page-title { margin: 0; color: var(--ke-ink); font-size: 18px; }
.load-error { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; min-height: 120px; }
.stat-card { display: flex; flex-direction: column; gap: 6px; padding: 16px 18px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-surface); }
.stat-label { margin: 0; color: var(--ke-sub); font-size: 12px; }

/* 04 §7 统计卡:数值宋体 900 27px,tabular-nums 对齐;null 灰态同 8.2 空态语义 */
.stat-value { margin: 0; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 27px; font-weight: 900; line-height: 1.2; font-variant-numeric: tabular-nums; }
.stat-value.is-null { color: var(--ke-sub-2); font-size: 14px; font-weight: 600; font-family: var(--ke-font); }
.stat-window { margin: 0; color: var(--ke-sub-2); font-size: 11px; }
.phase-note { margin: 0; color: var(--ke-sub-2); font-size: 12px; }
</style>
