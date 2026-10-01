import { createRouter, createWebHistory } from 'vue-router';
import { TOKEN_KEY } from '../api/http';
import LoginView from '../views/LoginView.vue';
import HomeView from '../views/HomeView.vue';
import CardsView from '../views/CardsView.vue';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { title: '登录' } },
    // 探索端首页(品牌头/搜索/专题格/推荐入口/继续探索卡占位)
    { path: '/home', component: HomeView, meta: { title: '知识探索' } },
    // 卡片浏览页占位:Task 14 替换为真卡片页;?q=&theme= 由首页搜索/专题透传
    { path: '/cards', component: CardsView, meta: { title: '卡片' } },
    { path: '/', redirect: '/home' },
    // catch-all:未知路径回首页(避免白屏)
    { path: '/:pathMatch(.*)*', redirect: '/home' }
  ]
});

// 导航守卫:无 token 且目标非 /login → 回登录页(键名与 http.ts 共用 TOKEN_KEY,不再写双份字面量)
router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem(TOKEN_KEY)) {
    return { path: '/login' };
  }
  return true;
});

export default router;
