/**
 * vitest 全局前置:补齐 jsdom 缺失的浏览器 API。
 * Element Plus 表格/抽屉依赖 ResizeObserver 计算布局,jsdom 未实现,给无操作桩即可。
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

(globalThis as Record<string, unknown>).ResizeObserver ??= ResizeObserverStub;
