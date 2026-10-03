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
        知识探索
        <span class="logo-sub">EDITORIAL DESK / 工作台</span>
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
      <div class="side-note">
        知识有来路，好奇有去处。<br>创作 · 运营 · 审核
      </div>
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
.wb { display: flex; height: 100vh; height: 100dvh; overflow: hidden; }
.side { overflow-y: auto; width: 214px; flex-shrink: 0; display: flex; flex-direction: column; gap: 28px; padding: 32px 16px 24px; border-right: 1px solid var(--ke-side-line); background: var(--ke-side); color: var(--ke-side-text); }
.logo { padding: 0 8px 24px; border-bottom: 1px solid var(--ke-side-line); color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 21px; font-weight: 400; line-height: 1.7; }
.logo-sub { display: block; margin-top: 8px; font-family: var(--ke-font-mono); font-size: 10px; letter-spacing: .1em; color: var(--ke-sub); }
.nav { flex-shrink: 0; display: flex; flex-direction: column; gap: 8px; }
.nav-item { display: flex; align-items: center; gap: 12px; min-height: 44px; padding: 0 12px; border-radius: var(--ke-radius-xs); color: var(--ke-side-text); font-size: 13px; text-decoration: none; transition: background var(--ke-dur-fast), color var(--ke-dur-fast); }
.nav-item:hover { background: var(--ke-side-2); }
.nav-item.router-link-active { background: var(--ke-primary); color: var(--ke-white); }
.nav-item .ke-icon { width: 15px; height: 15px; }
.nav-text { flex: 1; }
.side-note { margin-top: auto; padding: 18px 8px 0; border-top: 1px solid var(--ke-side-line); color: var(--ke-sub); font-size: 10px; line-height: 1.9; }
.badge { min-width: 8px; height: 8px; border-radius: var(--ke-radius-full); background: var(--ke-line-strong); color: var(--ke-white); font-size: 11px; line-height: 8px; text-align: center; }
.badge.is-active { min-width: 16px; height: 16px; padding: 0 4px; background: var(--el-color-danger); line-height: 16px; }
.main-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar { height: 68px; flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 36px; background: var(--ke-surface); border-bottom: 1px solid var(--ke-line); }
.crumb { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.crumb-root, .crumb-sep { color: var(--ke-sub); }
.crumb-cur { color: var(--ke-ink); font-weight: 600; }
.topbar-right { display: flex; align-items: center; gap: 18px; }
.role-chip { color: var(--ke-sub); font-size: 11px; }
.logout { min-height: 44px; padding: 0 4px; border: none; background: transparent; color: var(--ke-sub); font-size: 12px; cursor: pointer; }
.logout:hover { color: var(--ke-ink); }
.content { flex: 1; padding: 36px; overflow: auto; background: var(--ke-bg); }

@media (width <= 900px) {
  .side { width: 176px; padding: 24px 12px; }
  .logo { font-size: 17px; }
  .content { padding: 24px; }
  .topbar { padding: 0 24px; }
}

@media (width <= 600px) {
  .wb { flex-direction: column; }
  .side { width: 100%; padding: 12px; gap: 12px; border-right: none; border-bottom: 1px solid var(--ke-line); }
  .logo { padding: 0 4px; border: none; font-size: 18px; }
  .logo-sub, .side-note { display: none; }
  .nav { flex-direction: row; overflow-x: auto; gap: 6px; }
  .nav-item { flex-shrink: 0; padding: 0 9px; gap: 6px; font-size: 12px; }
  .main-col { min-height: 0; }
  .topbar { height: 52px; padding: 0 16px; }
  .content { padding: 20px 16px; }
}
</style>
