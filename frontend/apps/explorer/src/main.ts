import { createApp } from 'vue';
import { createPinia } from 'pinia';
// Vant 全量引入(P0-8 决策):基础样式先注入,再由 tokens 与 theme-vant 变量覆盖
import Vant from 'vant';
import 'vant/lib/index.css';
import '@ke/shared/src/tokens.css';
import '@ke/shared/src/styles/theme-vant.css';
import { installSprite } from '@ke/shared';
import App from './App.vue';
import router from './router';
import { useAuthStore } from './stores/auth';

installSprite();
const app = createApp(App);
app.use(createPinia());
app.use(Vant);
useAuthStore().restore();
app.use(router);
app.mount('#app');
