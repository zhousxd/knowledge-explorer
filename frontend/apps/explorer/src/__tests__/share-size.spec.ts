/**
 * 分享页体积门(Task 32 授权降级版硬门):不建独立 Vite 多页入口,改为
 * ShareView 懒加载独立 chunk + 自足样式(不引 Vant)→ 构建后断言该 chunk
 * gzip ≤ 50KB。需在 pnpm build 之后经 `pnpm run test:size` 单独运行;
 * 普通 pnpm test 遇 dist 缺失/过期(源码晚于产物)自动跳过,不因构建顺序误报。
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { describe, expect, it } from 'vitest';

// 路径锚定应用根:vitest 经 pnpm 脚本运行时 cwd=explorer 包目录
// (jsdom 环境下 import.meta.url 非 file: scheme,不可用于 fileURLToPath)
const appRoot = process.cwd();
const distAssets = join(appRoot, 'dist', 'assets');
const shareViewSrc = join(appRoot, 'src', 'views', 'ShareView.vue');
const routerSrc = join(appRoot, 'src', 'router', 'index.ts');

const SIZE_LIMIT = 50 * 1024;

/** dist 存在且全部 js 产物不旧于 ShareView 相关源码 → 才值得断言 */
const freshDist = (() => {
  if (!existsSync(distAssets)) return false;
  const js = readdirSync(distAssets).filter((f) => f.endsWith('.js'));
  if (js.length === 0) return false;
  const distNewest = Math.max(...js.map((f) => statSync(join(distAssets, f)).mtimeMs));
  const srcNewest = Math.max(statSync(shareViewSrc).mtimeMs, statSync(routerSrc).mtimeMs);
  return distNewest >= srcNewest;
})();

describe.skipIf(!freshDist)('ShareView 懒加载 chunk 体积门(≤50KB gz;需先 pnpm build)', () => {
  it(`ShareView chunk(js+css)gzip 合计 ≤ ${SIZE_LIMIT / 1024}KB(自足样式,不引 Vant)`, () => {
    const chunks = readdirSync(distAssets).filter((f) => f.startsWith('ShareView') && /\.(js|css)$/.test(f));
    const jsChunk = chunks.find((f) => f.endsWith('.js'));
    expect(jsChunk, 'dist/assets 下应存在 ShareView 懒加载 chunk(ShareView-*.js)').toBeDefined();
    let total = 0;
    for (const f of chunks) {
      const gzSize = gzipSync(readFileSync(join(distAssets, f))).length;
      total += gzSize;
      // 实测体积回显:供门禁/报告记录
      console.info(`[share-size] ${f} raw=${readFileSync(join(distAssets, f)).length}B gz=${gzSize}B`);
    }
    console.info(`[share-size] ShareView 合计 gz=${total}B (limit ${SIZE_LIMIT}B)`);
    expect(total).toBeLessThanOrEqual(SIZE_LIMIT);
  });
});
