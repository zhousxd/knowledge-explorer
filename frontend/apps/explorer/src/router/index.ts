import { createRouter, createWebHistory } from 'vue-router';
import { TOKEN_KEY } from '../api/http';
import LoginView from '../views/LoginView.vue';
import Home from '../pages/Home.vue';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { title: '登录' } },
    // 公开浏览首页(冒烟占位;Task 13/14 在此接入真实卡片数据)
    { path: '/home', component: Home, meta: { title: '知识探索' } },
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
