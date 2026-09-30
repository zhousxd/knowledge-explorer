import { createApp } from 'vue';
import { createPinia } from 'pinia';
import '@ke/shared/src/tokens.css';
import '@ke/shared/src/styles/theme-element.css';
import { installSprite } from '@ke/shared';
import App from './App.vue';
import router from './router';
import { useAuthStore } from './stores/auth';

installSprite();
const app = createApp(App);
app.use(createPinia());
useAuthStore().restore();
app.use(router);
app.mount('#app');
