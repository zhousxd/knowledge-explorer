import { defineStore } from 'pinia';
import { http, setUnauthorizedHandler, TOKEN_KEY } from '../api/http';
import type { LoginResp, MeResp } from '../api/types';
import router from '../router';

const REFRESH_KEY = 'ke_ex_refresh';
const USER_KEY = 'ke_ex_user';

interface AuthState {
  token: string;
  refreshToken: string;
  user: MeResp | null;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({ token: '', refreshToken: '', user: null }),
  actions: {
    /** 发送短信验证码(60s 冷却由后端频控,前端做倒计时) */
    async sendCode(phone: string) {
      await http.post('/auth/sms/send', { phone });
    },
    /** 验证码登录:新手机号由后端自动注册为 EXPLORER */
    async loginByCode(phone: string, code: string) {
      const tokens = await http.post<LoginResp>('/auth/sms/login', { phone, code });
      await this.adoptTokens(tokens);
    },
    /** 密码登录 */
    async login(phone: string, password: string) {
      const tokens = await http.post<LoginResp>('/auth/login', { phone, password });
      await this.adoptTokens(tokens);
    },
    /** 两种登录共用:先落 token,保证后续 /me 请求带上新 Bearer */
    async adoptTokens(tokens: LoginResp) {
      this.token = tokens.accessToken;
      this.refreshToken = tokens.refreshToken;
      localStorage.setItem(TOKEN_KEY, tokens.accessToken);
      localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
      this.user = await http.get<MeResp>('/me');
      localStorage.setItem(USER_KEY, JSON.stringify(this.user));
    },
    logout() {
      this.clearCredentials();
      void router.push('/login');
    },
    clearCredentials() {
      this.token = '';
      this.refreshToken = '';
      this.user = null;
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(REFRESH_KEY);
      localStorage.removeItem(USER_KEY);
    },
    /** 应用启动时从 localStorage 恢复会话 */
    restore() {
      this.token = localStorage.getItem(TOKEN_KEY) ?? '';
      this.refreshToken = localStorage.getItem(REFRESH_KEY) ?? '';
      const raw = localStorage.getItem(USER_KEY);
      if (raw) {
        try {
          this.user = JSON.parse(raw) as MeResp;
        } catch {
          this.user = null;
        }
      }
    }
  }
});

// 401 统一处理:清除本地凭证并回到登录页(回调机制由 http 层提供,避免与 router 循环耦合)
setUnauthorizedHandler(() => {
  useAuthStore().logout();
});
