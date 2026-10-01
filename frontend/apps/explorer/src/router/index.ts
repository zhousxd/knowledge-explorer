import { createRouter, createWebHistory } from 'vue-router';
import { TOKEN_KEY } from '../api/http';
import LoginView from '../views/LoginView.vue';
import HomeView from '../views/HomeView.vue';
import CardsView from '../views/CardsView.vue';
import CardView from '../views/CardView.vue';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    /** 匿名可浏览(01 文档:浏览无登录要求);未标记的默认受保护 */
    public?: boolean;
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { title: '登录' } },
    // 探索端首页(品牌头/搜索/专题格/推荐入口/继续探索卡占位);匿名可浏览
    { path: '/home', component: HomeView, meta: { title: '知识探索', public: true } },
    // 卡片列表页:搜索/专题过滤 + keyset 分页;?q=&theme= 由首页搜索/专题透传;匿名可浏览
    { path: '/cards', component: CardsView, meta: { title: '卡片', public: true } },
    // 卡片详情页(CardRenderer 按 templateType 分发);匿名可浏览,非 PUBLISHED 由 404 空态承载
    { path: '/cards/:id(\\d+)', component: CardView, meta: { title: '卡片详情', public: true } },
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
