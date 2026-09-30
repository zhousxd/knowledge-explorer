<template>
  <div class="wb">
    <aside class="side">
      <div class="logo">
        知识探索 · 工作台
      </div>
      <nav>
        <a class="on">
          卡片管理
        </a>
        <a>入口编排</a>
        <a>审核中心</a>
      </nav>
    </aside>
    <main class="body">
      占位：卡片管理
    </main>
  </div>
</template>

<style scoped>
.wb { display: flex; height: 100vh; }
.side { width: 220px; background: var(--ke-side); color: var(--ke-side-text); padding: 18px 12px; }
.logo { color: var(--ke-white); font-weight: 700; padding: 0 10px 14px; border-bottom: 1px solid var(--ke-side-line); }
nav a { display: block; padding: 10px 12px; border-radius: var(--ke-radius-s); font-size: 13px; }
nav a.on { background: var(--ke-primary); color: var(--ke-white); }
.body { flex: 1; padding: 24px; background: var(--ke-bg); }
</style>
