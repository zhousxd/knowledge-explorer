/**
 * vitest 全局前置:补齐 jsdom 缺失的浏览器 API。
 * Vant 组件(弹层/表格等)依赖 ResizeObserver 计算布局,jsdom 未实现,给无操作桩即可。
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

(globalThis as Record<string, unknown>).ResizeObserver ??= ResizeObserverStub;
