import { createRouter, createWebHistory } from 'vue-router';
import { TOKEN_KEY } from '../api/http';
import LoginView from '../views/LoginView.vue';
import HomeView from '../views/HomeView.vue';
import CardsView from '../views/CardsView.vue';
import CardView from '../views/CardView.vue';
import PathView from '../views/PathView.vue';
import RunView from '../views/RunView.vue';
import SummaryView from '../views/SummaryView.vue';
import EntryCreateView from '../views/EntryCreateView.vue';
import ShareSetupView from '../views/ShareSetupView.vue';
import MeView from '../views/MeView.vue';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    /** 匿名可浏览(01 文档:浏览无登录要求);未标记的默认受保护 */
    public?: boolean;
  }
}

const router = createRouter({
  // base 取 vite BASE_URL:dev 为 /,部署挂 /app/ 上下文时由 --base=/app/ 注入(nginx 单实例双端)
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: LoginView, meta: { title: '登录' } },
    // 探索端首页(品牌头/搜索/专题格/推荐入口/继续探索卡占位);匿名可浏览
    { path: '/home', component: HomeView, meta: { title: '知识探索', public: true } },
    // 卡片列表页:搜索/专题过滤 + keyset 分页;?q=&theme= 由首页搜索/专题透传;匿名可浏览
    { path: '/cards', component: CardsView, meta: { title: '卡片', public: true } },
    // 卡片详情页(CardRenderer 按 templateType 分发);匿名可浏览,非 PUBLISHED 由 404 空态承载
    { path: '/cards/:id(\\d+)', component: CardView, meta: { title: '卡片详情', public: true } },
    // 我的路径(路径树/断点续探/解释档位,FR-E03/E04/E05):个性化页,受保护(非 public)
    { path: '/path', component: PathView, meta: { title: '我的路径' } },
    // 执行态页(2s 轮询 FR-S04):个性化页,受保护;question/重试 payload 经 history state 携带
    { path: '/runs/:id(\\d+)', component: RunView, meta: { title: '智能服务' } },
    // 成果整理页(FR-E07/E09):个性化页,受保护;query.sessionId 必带(缺失回 /path),
    // ?runId 存在即报告态;重试 payload 经 history state(keSummary)携带
    { path: '/summary', component: SummaryView, meta: { title: '整理发现' } },
    // 用一句话新增入口四步流(FR-N01–N05,Task 28):个性化页,受保护;卡 id 必为数字
    { path: '/cards/:id(\\d+)/entry/new', component: EntryCreateView, meta: { title: '新增入口' } },
    // 分享设置页(FR-H02/H03 界面,Task 32):个性化页,受保护;query.sessionId 必带(缺失回 /path)
    { path: '/share/new', component: ShareSetupView, meta: { title: '分享探索' } },
    // 个人空间(FR-U02/U05,Task 34):收藏/路径/成果/私人入口四入口 + 今日配额;个性化页,受保护
    // (匿名经首页「我的」图标进入 → 守卫带 redirect 回跳登录)
    { path: '/me', component: MeView, meta: { title: '个人空间' } },
    // 免登录分享页(FR-H04/H05 界面,Task 32):meta.public 匿名可浏览;**懒加载独立 chunk**
    // (不引 Vant,自足 token 样式,体积门 chunk gzip ≤ 50KB,见 src/__tests__/share-size.spec.ts)
    { path: '/s/:token', component: () => import('../views/ShareView.vue'), meta: { title: '探索分享', public: true } },
    { path: '/', redirect: '/home' },
    // catch-all:未知路径回首页(避免白屏)
    { path: '/:pathMatch(.*)*', redirect: '/home' }
  ]
});

// 导航守卫:无 token 且目标非 /login 且未标 meta.public → 回登录页(带 redirect 回跳,登录后回到原目标)
// (键名与 http.ts 共用 TOKEN_KEY;个性化页如 Phase 4 的 /path 默认仍受保护)
router.beforeEach((to) => {
  if (to.path !== '/login' && !to.meta.public && !localStorage.getItem(TOKEN_KEY)) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  return true;
});

export default router;
