<script setup lang="ts">
import { ref, watch } from 'vue';
import { getCardVersions } from '../api/cards';
import type { CardListItem, VersionItem } from '../api/types';
import { STATUS_META, TEMPLATE_LABELS } from '../cardMeta';
import { formatDateTime } from '../format';

const props = defineProps<{ card: CardListItem | null; open: boolean }>();
const emit = defineEmits<{ (e: 'update:open', value: boolean): void }>();

const versions = ref<VersionItem[]>([]);
const loading = ref(false);
const errorMsg = ref('');

// 打开时拉取版本历史(versionNo 倒序);时间线纯只读展示,任何版本都不挂操作
watch(
  () => props.open,
  async (open) => {
    if (!open || !props.card) {
      return;
    }
    loading.value = true;
    errorMsg.value = '';
    versions.value = [];
    try {
      versions.value = await getCardVersions(props.card.id);
    } catch (e) {
      errorMsg.value = e instanceof Error ? e.message : '版本历史加载失败';
    } finally {
      loading.value = false;
    }
  }
);
</script>

<template>
  <el-drawer
    class="card-drawer"
    :model-value="open"
    size="460px"
    @update:model-value="emit('update:open', $event)"
  >
    <template #header>
      <span class="drawer-heading">卡片详情</span>
    </template>
    <div
      v-if="card"
      class="drawer-body"
    >
      <p class="drawer-theme">
        {{ card.theme }}
      </p>
      <h3 class="drawer-title">
        {{ card.title }}
      </h3>
      <div class="drawer-chips">
        <span class="tpl-chip">{{ TEMPLATE_LABELS[card.templateType] ?? card.templateType }}</span>
        <el-tag
          :type="STATUS_META[card.status].tagType"
          size="small"
          disable-transitions
        >
          {{ STATUS_META[card.status].label }}
        </el-tag>
      </div>
      <p class="drawer-meta">
        当前版本 <span class="num">v{{ card.currentVersionNo ?? '—' }}</span>
        <span class="dot">·</span>维护人 {{ card.maintainerNickname ?? '—' }}
      </p>

      <h4 class="section-title">
        版本历史
      </h4>
      <p
        v-if="errorMsg"
        class="drawer-error"
        role="alert"
      >
        {{ errorMsg }}
      </p>
      <p
        v-else-if="loading"
        class="drawer-hint"
      >
        加载中…
      </p>
      <p
        v-else-if="versions.length === 0"
        class="drawer-hint"
      >
        暂无版本记录
      </p>
      <el-timeline
        v-else
        class="version-timeline"
      >
        <el-timeline-item
          v-for="(version, index) in versions"
          :key="version.versionNo"
          :timestamp="formatDateTime(version.createdAt)"
        >
          <div class="ver-row">
            <span class="ver-no num">v{{ version.versionNo }}</span>
            <el-tag
              v-if="index === 0"
              size="small"
              type="success"
              disable-transitions
            >
              当前
            </el-tag>
            <span class="ver-author">{{ version.createdByNickname ?? '未知' }}</span>
          </div>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-heading { color: var(--ke-ink); font-size: 15px; font-weight: 600; }
.drawer-body { display: flex; flex-direction: column; gap: 10px; }
.drawer-theme { margin: 0; color: var(--ke-sub); font-size: 12px; }

/* 宋体仅用于卡标题(04 文档字体分层) */
.drawer-title { margin: 0; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 20px; font-weight: 700; }
.drawer-chips { display: flex; align-items: center; gap: 8px; }
.tpl-chip { padding: 2px 8px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 11px; font-weight: 600; }
.drawer-meta { margin: 0; color: var(--ke-sub); font-size: 12px; }
.dot { margin: 0 4px; }
.section-title { margin: 12px 0 0; padding-top: 12px; border-top: 1px solid var(--ke-line); color: var(--ke-ink-2); font-size: 13px; font-weight: 600; }
.drawer-error { margin: 0; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 12px; }
.drawer-hint { margin: 4px 0 0; color: var(--ke-sub-2); font-size: 12px; }
.version-timeline { margin: 8px 0 0; padding-left: 4px; }
.ver-row { display: flex; align-items: center; gap: 8px; }
.ver-no { color: var(--ke-ink); font-size: 13px; font-weight: 600; }
.ver-author { color: var(--ke-sub); font-size: 12px; }
.num { font-variant-numeric: tabular-nums; }
</style>
