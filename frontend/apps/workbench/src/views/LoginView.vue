<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { useAuthStore } from '../stores/auth';

const phone = ref('');
const password = ref('');
const errorMsg = ref('');
const busy = ref(false);
const router = useRouter();
const auth = useAuthStore();

async function submit() {
  if (busy.value) return;
  errorMsg.value = '';
  busy.value = true;
  try {
    await auth.login(phone.value.trim(), password.value);
    await router.push('/cards');
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '登录失败,请稍后重试';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <form
      class="login-card"
      @submit.prevent="submit"
    >
      <h1 class="title">
        知识探索 · 工作台
      </h1>
      <p class="subtitle">
        登录后进入内容工作台
      </p>
      <p
        v-if="errorMsg"
        class="error"
        role="alert"
      >
        {{ errorMsg }}
      </p>
      <label class="field">
        <span class="field-label">手机号</span>
        <input
          v-model="phone"
          class="input"
          type="tel"
          name="phone"
          placeholder="请输入手机号"
          autocomplete="username"
        >
      </label>
      <label class="field">
        <span class="field-label">密码</span>
        <input
          v-model="password"
          class="input"
          type="password"
          name="password"
          placeholder="请输入密码"
          autocomplete="current-password"
        >
      </label>
      <button
        class="submit"
        type="submit"
        :disabled="busy"
      >
        {{ busy ? '登录中' : '登录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px; background: var(--ke-bg); }
.login-card { width: 360px; padding: 32px 28px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); box-shadow: var(--ke-shadow-2); }
.title { margin: 0 0 6px; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 24px; font-weight: 700; }
.subtitle { margin: 0 0 20px; color: var(--ke-sub); font-size: 13px; }
.error { margin: 0 0 14px; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.field { display: block; margin-bottom: 14px; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.input { width: 100%; height: 38px; padding: 0 12px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 14px; box-sizing: border-box; transition: border-color var(--ke-dur-fast) var(--ke-ease), box-shadow var(--ke-dur-fast) var(--ke-ease); }
.input::placeholder { color: var(--ke-sub-2); }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.submit { width: 100%; height: 40px; margin-top: 6px; border: none; border-radius: var(--ke-radius-s); background: var(--ke-primary); color: var(--ke-white); font-size: 14px; font-weight: 600; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.submit:hover { background: var(--ke-primary-deep); }
.submit:disabled { opacity: .6; cursor: default; }
</style>
