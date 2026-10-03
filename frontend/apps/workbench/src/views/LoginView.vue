<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ApiError } from '../api/http';
import { useAuthStore } from '../stores/auth';
import { ThemeArtwork } from '@ke/shared';

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
  <div class="login-page atlas-login">
    <header class="login-masthead">
      <div class="login-brand">
        <span
          class="brand-mark"
          aria-hidden="true"
        >知</span>知识探索
      </div>
      <span class="atlas-eyebrow">CONTENT / 创作与运营</span>
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
          为知识编目，<br>为好奇开门。
        </h2>
        <p class="story-desc">
          从一张卡片、一份出处开始，将地方与人文、风味与生活、日常与科学，编成一本开放的知识图鉴。
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
          <p class="atlas-eyebrow">
            EDITOR ACCESS / 编辑入口
          </p>
          <h1 class="title">
            进入内容工作台
          </h1>
          <p class="subtitle">
            整理知识、编排入口，让内容抵达更多好奇的人。
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
          <p class="login-note">
            使用已有创作者、编辑或运营账号登录。
          </p>
        </form>
      </section>
    </main>
    <footer class="login-footer">
      从一张卡片出发 · 逐层深入 · 随时回望
    </footer>
  </div>
</template>
