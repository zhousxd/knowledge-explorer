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
    <section class="home-intro">
      <p class="atlas-eyebrow">
        A FIELD GUIDE TO CURIOSITY
      </p>
      <h2 class="intro-title">
        从一个地方，<br>走进一个世界。
      </h2>
      <p class="sub">
        地标、风物与日常里的科学
      </p>
    </section>
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
      <span>专题图鉴</span> <span class="ln" /> <span class="sec-no">INDEX / 03</span>
    </div>
    <ThemeGrid @select="openTheme" />
    <ResumeCard
      v-if="resume"
      :session="resume"
      @continue="onResume"
    />
    <button
      v-if="!auth.token"
      type="button"
      class="login-hint"
      @click="goLogin"
    >
      登录后记录你的探索路径
    </button>
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
    <nav
      class="home-nav"
      aria-label="主导航"
    >
      <span
        class="home-nav-current"
        aria-current="page"
      >发现</span>
      <button
        type="button"
        class="mypath"
        @click="goMyPath"
      >
        我的路径
      </button>
      <button
        type="button"
        @click="goMe"
      >
        我的
      </button>
    </nav>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; padding: 28px 24px 110px; background: var(--ke-bg); }
.title-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding-bottom: 18px; border-bottom: 1px solid var(--ke-ink); }
.title { margin: 0; font-size: 20px; font-weight: 700; line-height: 1.3; color: var(--ke-ink); }
.my-entry { display: inline-flex; align-items: center; gap: 6px; min-height: 44px; padding: 0 4px 0 12px; border: none; background: transparent; color: var(--ke-sub); font-size: 12px; cursor: pointer; }
.me-ic { width: 16px; height: 16px; }
.home-intro { padding: 30px 0 22px; }
.intro-title { margin: 16px 0 12px; font-family: var(--ke-font-display); font-size: clamp(32px, 8.7vw, 42px); font-weight: 400; line-height: 1.4; letter-spacing: -.035em; }
.sub { margin: 0; font-size: 12px; line-height: 1.8; color: var(--ke-sub); }
.search { display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 14px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-xs); background: var(--ke-surface-2); transition: border-color var(--ke-dur-fast), box-shadow var(--ke-dur-fast); }
.search:focus-within { border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.s-icon { width: 17px; height: 17px; color: var(--ke-sub); }
.s-input { flex: 1; min-width: 0; height: 100%; border: none; outline: none; background: transparent; color: var(--ke-ink); font-size: 13px; }
.s-input::placeholder { color: var(--ke-sub); }
.sec-title { display: flex; align-items: center; gap: 12px; margin: 28px 0 14px; font-size: 13px; font-weight: 700; color: var(--ke-ink); }
.sec-title .ln { flex: 1; height: 1px; background: var(--ke-line); }
.sec-no { font-family: var(--ke-font-mono); font-size: 10px; font-weight: 400; color: var(--ke-sub); }
.entry { display: flex; align-items: center; gap: 12px; width: 100%; margin: 0; padding: 18px 0; border: none; border-bottom: 1px solid var(--ke-line); background: transparent; color: var(--ke-ink); text-align: left; cursor: pointer; }
.entry:hover { background: var(--ke-surface-2); }
.e-icon { display: flex; width: 32px; height: 38px; flex-shrink: 0; align-items: center; justify-content: center; color: var(--ke-primary); }
.e-text { flex: 1; min-width: 0; }
.e-name { display: block; font-size: 14px; font-weight: 600; line-height: 1.6; }
.e-sub { display: block; margin-top: 6px; font-size: 11px; line-height: 1.6; color: var(--ke-sub); }
.e-arrow { display: flex; color: var(--ke-primary); }
.demo-chip { display: inline-block; margin-left: 8px; padding: 0 5px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-xs); font-size: 10px; font-weight: 400; color: var(--ke-sub); vertical-align: 1px; }
.login-hint { width: 100%; min-height: 44px; margin-top: 20px; padding: 12px 0; border: none; border-bottom: 1px solid var(--ke-line); background: transparent; color: var(--ke-sub); font-size: 12px; text-align: left; cursor: pointer; }
.home-nav { position: fixed; z-index: var(--ke-z-bar); right: 0; bottom: 0; left: 0; display: flex; align-items: center; gap: 12px; max-width: 560px; margin: auto; padding: 12px 24px calc(12px + env(safe-area-inset-bottom, 0px)); border-top: 1px solid var(--ke-line); background: var(--ke-surface); }
.home-nav button, .home-nav-current { flex: 1; display: grid; place-items: center; min-height: 44px; border: none; border-radius: var(--ke-radius-xs); background: transparent; color: var(--ke-sub); font-size: 12px; text-align: center; }
.home-nav button { cursor: pointer; }
.home-nav-current { background: var(--ke-accent); color: var(--ke-on-accent); font-weight: 700; }
</style>
