import { createApp } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
// Element 基础样式先注入,再由主题 tokens 覆盖(--el-* 映射 --ke-*,见 theme-element.css)
import 'element-plus/dist/index.css';
import '@ke/shared/src/tokens.css';
import '@ke/shared/src/styles/theme-element.css';
import { installSprite } from '@ke/shared';
import App from './App.vue';
import router from './router';
import { useAuthStore } from './stores/auth';

installSprite();
const app = createApp(App);
app.use(createPinia());
app.use(ElementPlus, { locale: zhCn });
useAuthStore().restore();
app.use(router);
app.mount('#app');
