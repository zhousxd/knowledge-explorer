<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { KeIcon } from '@ke/shared';
import { fetchLatestSession } from '../api/sessions';
import type { ResumeSession } from '../api/sessions';
import ResumeCard from '../components/ResumeCard.vue';
import ThemeGrid from '../components/ThemeGrid.vue';
import { useAuthStore } from '../stores/auth';
import { HOME_ENTRIES } from '../mock/home';

const router = useRouter();
const auth = useAuthStore();
const keyword = ref('');
// 断点续探入口(FR-E01):已登录挂载时拉最近会话,404(无会话)→ null 整卡隐藏(P4-16 契约)
const resume = ref<ResumeSession | null>(null);

onMounted(() => {
  if (!auth.token) return;
  void fetchLatestSession()
    .then((latest) => {
      resume.value = latest;
    })
    .catch(() => {}); // 网络失败静默隐藏续探卡(首页其余功能区不受影响)
});

function goLogin() {
  void router.push('/login');
}

/** 个人空间入口(Task 34):标题行右侧「我的」图标按钮;匿名点击由 /me 守卫带 redirect 进登录 */
function goMe() {
  void router.push('/me');
}

function goMyPath() {
  void router.push('/path');
}

/** 继续上次探索:带 sessionId 进路径页,定位到最近访问节点 */
function onResume() {
  if (!resume.value) return;
  void router.push({ path: '/path', query: { sessionId: String(resume.value.sessionId) } });
}

function search() {
  const q = keyword.value.trim();
  if (!q) return; // 空关键词不触发跳转
  void router.push({ path: '/cards', query: { q } });
}

function openTheme(theme: string) {
  void router.push({ path: '/cards', query: { theme } });
}

// 演示入口统一落到卡片占位页;Phase 5 推荐服务接真数据后改跳具体卡片
function openEntry() {
  void router.push('/cards');
}
</script>

<template>
  <div class="page">
    <button
      v-if="!auth.token"
      type="button"
      class="login-hint"
      @click="goLogin"
    >
      登录后记录你的探索路径
    </button>
    <div class="title-row">
      <h1 class="title">
        知识探索
      </h1>
      <button
        type="button"
        class="my-entry"
        aria-label="个人空间"
        data-testid="me-entry"
        @click="goMe"
      >
        <KeIcon
          class="me-ic"
          name="me"
        />
        我的
      </button>
    </div>
    <p class="sub">
      从一张卡片出发，逐层深入，随时回望
    </p>
    <button
      v-if="auth.token"
      type="button"
      class="mypath"
      @click="goMyPath"
    >
      <KeIcon
        class="mp-ic"
        name="path"
      />
      我的路径
    </button>
    <ResumeCard
      v-if="resume"
      :session="resume"
      @continue="onResume"
    />
    <form
      class="search"
      role="search"
      @submit.prevent="search"
    >
      <KeIcon
        class="s-icon"
        name="search"
      />
      <input
        v-model="keyword"
        class="s-input"
        type="search"
        name="q"
        placeholder="搜索卡片、主题或问题"
        aria-label="搜索卡片、主题或问题"
      >
    </form>
    <div class="sec-title">
      主题专题 <span class="ln" />
    </div>
    <ThemeGrid @select="openTheme" />
    <div class="sec-title">
      今日推荐入口 <span class="ln" />
    </div>
    <button
      v-for="entry in HOME_ENTRIES"
      :key="entry.name"
      type="button"
      class="entry"
      @click="openEntry"
    >
      <span class="e-icon">
        <KeIcon :name="entry.icon" />
      </span>
      <span class="e-text">
        <span class="e-name">
          {{ entry.name }}<span class="demo-chip">演示</span>
        </span>
        <span class="e-sub">
          {{ entry.sub }}
        </span>
      </span>
      <span class="e-arrow">
        <KeIcon name="chev" />
      </span>
    </button>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 24px 16px 96px; background: var(--ke-bg); }
.login-hint { display: flex; width: 100%; align-items: center; margin: 0 0 14px; padding: 9px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 12px; text-align: left; cursor: pointer; box-sizing: border-box; }
.title-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.title { margin: 0; font-family: var(--ke-font-display); font-size: 20px; font-weight: 900; line-height: 1.3; color: var(--ke-ink); }
.my-entry { display: inline-flex; align-items: center; gap: 4px; padding: 6px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-ink); font-size: 12px; font-weight: 600; font-family: var(--ke-font); cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.my-entry:active { background: var(--ke-primary-soft); }
.me-ic { width: 14px; height: 14px; color: var(--ke-primary); }
.sub { margin: 4px 0 14px; font-size: 12px; color: var(--ke-sub); }
.mypath { display: inline-flex; align-items: center; gap: 5px; margin: -6px 0 12px; padding: 6px 12px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-full); background: var(--ke-surface); color: var(--ke-ink); font-size: 12px; font-weight: 600; font-family: var(--ke-font); cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.mypath:active { background: var(--ke-primary-soft); }
.mp-ic { width: 14px; height: 14px; color: var(--ke-primary); }
.search { display: flex; align-items: center; gap: 8px; height: 44px; padding: 0 14px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-l); background: var(--ke-surface); transition: border-color var(--ke-dur-fast) var(--ke-ease), box-shadow var(--ke-dur-fast) var(--ke-ease); }
.search:focus-within { border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.s-icon { width: 18px; height: 18px; color: var(--ke-sub-2); }
.s-input { flex: 1; min-width: 0; height: 100%; border: none; outline: none; background: transparent; color: var(--ke-ink); font-size: 14px; }
.s-input::placeholder { color: var(--ke-sub-2); }
.sec-title { display: flex; align-items: center; gap: 6px; margin: 16px 0 6px; font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.sec-title .ln { flex: 1; height: 1px; background: var(--ke-line); }
.entry { display: flex; width: 100%; align-items: center; gap: 10px; margin: 0 0 10px; padding: 12px 14px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); text-align: left; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); box-sizing: border-box; }
.entry:active { background: var(--ke-primary-soft); }
.e-icon { display: flex; width: 36px; height: 36px; flex-shrink: 0; align-items: center; justify-content: center; border-radius: var(--ke-radius-s); background: var(--ke-primary-soft); color: var(--ke-primary); }
.e-text { flex: 1; min-width: 0; }
.e-name { display: block; font-size: 14px; font-weight: 600; line-height: 1.5; color: var(--ke-ink); }
.demo-chip { display: inline-block; margin-left: 6px; padding: 0 7px; border-radius: var(--ke-radius-full); background: var(--ke-line-2); color: var(--ke-sub); font-size: 11px; font-weight: 600; line-height: 1.5; vertical-align: 1px; }
.e-sub { display: block; margin-top: 2px; font-size: 12px; line-height: 1.5; color: var(--ke-sub); }
.e-arrow { display: flex; flex-shrink: 0; color: var(--ke-sub-2); }
</style>
