<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { useAuthStore } from '../stores/auth';
import { ThemeArtwork } from '@ke/shared';

const PHONE_RE = /^1\d{10}$/;
const COOLDOWN_SECONDS = 60;

const tab = ref<'code' | 'password'>('code');
const phone = ref('');
const code = ref('');
const password = ref('');
const errorMsg = ref('');
const busy = ref(false);
const router = useRouter();
const route = useRoute();
const auth = useAuthStore();

/** 登录后回跳:redirect 仅接受站内路径(以 / 开头且非 //,防外链),否则回首页 */
function afterLoginPath(): string {
  const redirect = route.query.redirect;
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/';
}

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
    await router.push(afterLoginPath());
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '登录失败,请稍后重试';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login-page atlas-login">
    <header class="login-masthead">
      <div class="login-brand">
        <span
          class="brand-mark"
          aria-hidden="true"
        >知</span>知识探索
      </div>
      <RouterLink
        class="login-back"
        to="/home"
      >
        先去看看 ↗
      </RouterLink>
    </header>
    <main class="login-spread">
      <section
        class="login-story"
        aria-label="知识探索介绍"
      >
        <p class="atlas-eyebrow">
          KNOWLEDGE EXPLORER / 开放知识图鉴
        </p>
        <h2 class="story-title">
          好奇的下一页，<br>从这里开始。
        </h2>
        <p class="story-desc">
          沿着书院的屋檐，追寻一口湘菜的来路，听见日常里的科学。从一张卡片，走进一个世界。
        </p>
        <div class="story-plate">
          <ThemeArtwork theme="academy" />
          <div class="plate-note">
            <span>图版 01 / 书院地标</span><span>建筑示意</span>
          </div>
        </div>
        <div class="story-index">
          <span><i aria-hidden="true" />书院地标</span>
          <span><i aria-hidden="true" />湘菜风物</span>
          <span><i aria-hidden="true" />声音科学</span>
        </div>
      </section>
      <section
        class="login-form-area"
        aria-label="账号登录"
      >
        <form
          class="login-card"
          @submit.prevent="submit"
        >
          <h1 class="title">
            继续你的探索
          </h1>
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
            <label
              class="field-label"
              for="login-code"
            >验证码</label>
            <div class="code-row">
              <input
                id="login-code"
                v-model="code"
                class="input"
                type="text"
                name="code"
                maxlength="6"
                inputmode="numeric"
                autocomplete="one-time-code"
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
      </section>
    </main>
    <footer class="login-footer">
      从一张卡片出发 · 逐层深入 · 随时回望
    </footer>
  </div>
</template>
