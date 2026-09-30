<template>
  <div class="page">
    <h1 class="title">
      知识探索
    </h1>
    <p class="sub">
      从一张卡片出发，逐层深入，随时回望
    </p>
    <div class="themes">
      <div class="theme">
        <b>书院地标</b><span>12 张卡</span>
      </div>
      <div class="theme">
        <b>湘菜风物</b><span>9 张卡</span>
      </div>
      <div class="theme">
        <b>声音科学</b><span>7 张卡</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--ke-bg); padding: 24px 16px; }
.title { font-family: var(--ke-font-display); font-weight: 900; font-size: 32px; margin: 0; color: var(--ke-ink); }
.sub { color: var(--ke-sub); margin: 8px 0 24px; }
.themes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.theme { background: var(--ke-surface); border: 1px solid var(--ke-line); border-radius: var(--ke-radius-m); padding: 14px 8px; text-align: center; }
.theme span { display: block; font-size: 11px; color: var(--ke-sub); margin-top: 4px; }
</style>
