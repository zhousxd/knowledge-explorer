import { createApp } from 'vue';
import '@ke/shared/src/tokens.css';
import '@ke/shared/src/styles/theme-element.css';
import { installSprite } from '@ke/shared';
import App from './App.vue';

installSprite();
createApp(App).mount('#app');
