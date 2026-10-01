import { createRouter, createWebHistory } from 'vue-router';
import { TOKEN_KEY } from '../api/http';
import LoginView from '../views/LoginView.vue';
import WbLayout from '../layout/WbLayout.vue';
import CardsView from '../views/CardsView.vue';
import CardEditView from '../views/CardEditView.vue';
import EntriesView from '../views/EntriesView.vue';
import ReviewsView from '../views/ReviewsView.vue';
import AssetsView from '../views/AssetsView.vue';
import MetricsView from '../views/MetricsView.vue';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    /** 不在侧栏菜单显示(编辑器页从列表/抽屉进入) */
    hidden?: boolean;
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { title: '登录' } },
    {
      path: '/',
      component: WbLayout,
      redirect: '/cards',
      children: [
        { path: 'cards', component: CardsView, meta: { title: '卡片管理' } },
        { path: 'cards/new', component: CardEditView, meta: { title: '新建卡片', hidden: true } },
        { path: 'cards/edit/:id', component: CardEditView, meta: { title: '编辑卡片', hidden: true } },
        { path: 'entries', component: EntriesView, meta: { title: '入口编排' } },
        { path: 'reviews', component: ReviewsView, meta: { title: '审核中心' } },
        { path: 'assets', component: AssetsView, meta: { title: '知识资源' } },
        { path: 'metrics', component: MetricsView, meta: { title: '数据看板' } }
      ]
    },
    // catch-all:未知路径回卡片管理(避免白屏;Phase 3 explorer 路由克隆此模式)
    { path: '/:pathMatch(.*)*', redirect: '/cards' }
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
