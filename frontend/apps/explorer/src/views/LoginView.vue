<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { useAuthStore } from '../stores/auth';

const PHONE_RE = /^1\d{10}$/;
const COOLDOWN_SECONDS = 60;

const tab = ref<'code' | 'password'>('code');
const phone = ref('');
const code = ref('');
const password = ref('');
const errorMsg = ref('');
const busy = ref(false);
const router = useRouter();
const auth = useAuthStore();

const phoneValid = computed(() => PHONE_RE.test(phone.value.trim()));

// —— 验证码 60s 倒计时:发送成功后启动,倒计时期间按钮禁用防重 ——
const countdown = ref(0);
let timer: ReturnType<typeof setInterval> | undefined;
const sendLabel = computed(() => (countdown.value > 0 ? `${countdown.value}s后重发` : '获取验证码'));
const canSendCode = computed(() => countdown.value === 0 && phoneValid.value);

function stopCountdown() {
  if (timer !== undefined) clearInterval(timer);
  timer = undefined;
}

function startCountdown() {
  stopCountdown();
  countdown.value = COOLDOWN_SECONDS;
  timer = setInterval(() => {
    countdown.value -= 1;
    if (countdown.value <= 0) stopCountdown();
  }, 1000);
}

onBeforeUnmount(stopCountdown);

function switchTab(next: 'code' | 'password') {
  if (tab.value === next) return;
  tab.value = next;
  errorMsg.value = '';
}

async function sendCode() {
  if (!canSendCode.value || busy.value) return;
  errorMsg.value = '';
  try {
    await auth.sendCode(phone.value.trim());
    startCountdown();
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '发送失败,请稍后重试';
  }
}

async function submit() {
  if (busy.value) return;
  errorMsg.value = '';
  busy.value = true;
  try {
    if (tab.value === 'code') await auth.loginByCode(phone.value.trim(), code.value.trim());
    else await auth.login(phone.value.trim(), password.value);
    await router.push('/');
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
        知识探索
      </h1>
      <p class="subtitle">
        验证码或密码登录,从一张卡片开始探索
      </p>
      <div
        class="tabs"
        role="tablist"
      >
        <button
          type="button"
          class="tab"
          role="tab"
          :class="{ active: tab === 'code' }"
          :aria-selected="tab === 'code'"
          @click="switchTab('code')"
        >
          验证码登录
        </button>
        <button
          type="button"
          class="tab"
          role="tab"
          :class="{ active: tab === 'password' }"
          :aria-selected="tab === 'password'"
          @click="switchTab('password')"
        >
          密码登录
        </button>
      </div>
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
      <div
        v-if="tab === 'code'"
        class="field"
      >
        <span class="field-label">验证码</span>
        <div class="code-row">
          <input
            v-model="code"
            class="input"
            type="text"
            name="code"
            maxlength="6"
            inputmode="numeric"
            placeholder="6 位验证码"
          >
          <button
            type="button"
            class="send-code"
            :disabled="!canSendCode || busy"
            @click="sendCode"
          >
            {{ sendLabel }}
          </button>
        </div>
      </div>
      <label
        v-else
        class="field"
      >
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
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px 16px; background: var(--ke-bg); }
.login-card { width: 360px; max-width: 100%; padding: 32px 28px; border: 1px solid var(--ke-line); border-radius: var(--ke-radius-l); background: var(--ke-surface); box-shadow: var(--ke-shadow-2); }
.title { margin: 0 0 6px; color: var(--ke-ink); font-family: var(--ke-font-display); font-size: 26px; font-weight: 900; text-align: center; }
.subtitle { margin: 0 0 18px; color: var(--ke-sub); font-size: 13px; text-align: center; }
.tabs { display: flex; margin-bottom: 18px; border-bottom: 1px solid var(--ke-line); }
.tab { flex: 1; padding: 10px 0; border: none; background: none; color: var(--ke-sub); font-size: 14px; cursor: pointer; border-bottom: 2px solid transparent; transition: color var(--ke-dur-fast) var(--ke-ease), border-color var(--ke-dur-fast) var(--ke-ease); }
.tab.active { color: var(--ke-primary); border-bottom-color: var(--ke-primary); font-weight: 600; }
.error { margin: 0 0 14px; padding: 8px 12px; border-radius: var(--ke-radius-s); background: var(--ke-danger-soft); color: var(--ke-danger); font-size: 13px; }
.field { display: block; margin-bottom: 14px; }
.field-label { display: block; margin-bottom: 6px; color: var(--ke-ink-2); font-size: 12px; }
.input { width: 100%; height: 38px; padding: 0 12px; border: 1px solid var(--ke-line-strong); border-radius: var(--ke-radius-s); background: var(--ke-surface); color: var(--ke-ink); font-size: 14px; box-sizing: border-box; transition: border-color var(--ke-dur-fast) var(--ke-ease), box-shadow var(--ke-dur-fast) var(--ke-ease); }
.input::placeholder { color: var(--ke-sub-2); }
.input:focus { outline: none; border-color: var(--ke-primary); box-shadow: var(--ke-focus); }
.code-row { display: flex; gap: 10px; }
.code-row .input { flex: 1; min-width: 0; }
.send-code { flex-shrink: 0; height: 38px; padding: 0 14px; border: 1px solid var(--ke-primary); border-radius: var(--ke-radius-s); background: var(--ke-primary-soft); color: var(--ke-primary); font-size: 13px; white-space: nowrap; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.send-code:disabled { opacity: .6; cursor: default; }
.send-code:hover:not(:disabled) { background: var(--ke-primary); color: var(--ke-white); }
.submit { width: 100%; height: 40px; margin-top: 6px; border: none; border-radius: var(--ke-radius-s); background: var(--ke-primary); color: var(--ke-white); font-size: 14px; font-weight: 600; cursor: pointer; transition: background var(--ke-dur-fast) var(--ke-ease); }
.submit:disabled { opacity: .6; cursor: default; }
.submit:hover:not(:disabled) { background: var(--ke-primary-deep); }
</style>
