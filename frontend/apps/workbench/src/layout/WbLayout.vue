<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { KeIcon } from '@ke/shared';
import { roleLabel, useAuthStore } from '../stores/auth';
import { useReviewStore } from '../stores/review';

const NAV_ITEMS = [
  { path: '/cards', title: '卡片管理', icon: 'layers', badge: false },
  { path: '/entries', title: '入口编排', icon: 'plus', badge: false },
  { path: '/reviews', title: '审核中心', icon: 'check', badge: true },
  { path: '/assets', title: '知识资源', icon: 'book', badge: false },
  { path: '/metrics', title: '数据看板', icon: 'scale', badge: false }
] as const;

const route = useRoute();
const auth = useAuthStore();
const review = useReviewStore();
const pageTitle = computed(() => route.meta.title ?? '');
/** 待审计数徽标(>0 显示数字,99+ 封顶;=0 退化为预留圆点) */
const badgeText = computed(() => (review.total > 99 ? '99+' : review.total > 0 ? String(review.total) : ''));

onMounted(() => {
  // 布局挂载即拉一次待审计数;403(无审核角色)由 store 静默归零,不显示计数
  void review.refreshPendingTotal();
});
</script>

<template>
  <div class="wb">
    <aside class="side">
      <div class="logo">
        知识探索 · 工作台
      </div>
      <nav class="nav">
        <RouterLink
          v-for="item in NAV_ITEMS"
          :key="item.path"
          :to="item.path"
          class="nav-item"
        >
          <KeIcon :name="item.icon" />
          <span class="nav-text">{{ item.title }}</span>
          <span
            v-if="item.badge"
            class="badge"
            :class="{ 'is-active': review.total > 0 }"
            :data-total="review.total"
            aria-hidden="true"
          >{{ badgeText }}</span>
        </RouterLink>
      </nav>
    </aside>
    <div class="main-col">
      <header class="topbar">
        <div class="crumb">
          <span class="crumb-root">
            工作台
          </span>
          <span class="crumb-sep">/</span>
          <span class="crumb-cur">{{ pageTitle }}</span>
        </div>
        <div class="topbar-right">
          <span
            v-if="auth.user"
            class="role-chip"
          >{{ roleLabel(auth.user.role) }}</span>
          <button
            class="logout"
            type="button"
            @click="auth.logout()"
          >
            退出
          </button>
        </div>
      </header>
      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.wb { display: flex; height: 100vh; overflow: hidden; }
.side { width: 220px; flex-shrink: 0; display: flex; flex-direction: column; gap: 14px; padding: 18px 12px; background: var(--ke-side); color: var(--ke-side-text); }
.logo { padding: 0 10px 14px; border-bottom: 1px solid var(--ke-side-line); color: var(--ke-white); font-family: var(--ke-font-display); font-size: 17px; font-weight: 700; }
.nav { display: flex; flex-direction: column; gap: 4px; }
.nav-item { display: flex; align-items: center; gap: 10px; height: 40px; padding: 0 12px; border-radius: var(--ke-radius-s); color: var(--ke-side-text); font-size: 13px; text-decoration: none; transition: background var(--ke-dur-fast) var(--ke-ease), color var(--ke-dur-fast) var(--ke-ease); }
.nav-item:hover { background: var(--ke-side-2); color: var(--ke-white); }
.nav-item.router-link-active { background: var(--ke-primary); color: var(--ke-white); }
.nav-text { flex: 1; }
.badge { min-width: 8px; height: 8px; padding: 0; border-radius: var(--ke-radius-full); background: var(--ke-side-line); color: var(--ke-white); font-size: 10px; line-height: 8px; text-align: center; }
.badge.is-active { min-width: 16px; height: 16px; padding: 0 4px; background: var(--el-color-danger); line-height: 16px; }
.main-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar { height: 56px; flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; background: var(--ke-surface); border-bottom: 1px solid var(--ke-line); }
.crumb { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.crumb-root, .crumb-sep { color: var(--ke-sub); }
.crumb-cur { color: var(--ke-ink); font-weight: 600; }
.topbar-right { display: flex; align-items: center; gap: 12px; }
.role-chip { padding: 3px 10px; border-radius: var(--ke-radius-full); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; }
.logout { padding: 5px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-s); background: none; color: var(--ke-sub); font-size: 13px; cursor: pointer; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.logout:hover { border-color: var(--ke-line-strong); color: var(--ke-ink); }
.content { flex: 1; padding: 24px; overflow: auto; background: var(--ke-bg); }
</style>
