import { defineStore } from 'pinia';
import { http, setUnauthorizedHandler, TOKEN_KEY } from '../api/http';
import type { LoginResp, MeResp } from '../api/types';
import router from '../router';

const REFRESH_KEY = 'ke_wb_refresh';
const USER_KEY = 'ke_wb_user';

const ROLE_LABELS: Record<string, string> = {
  EXPLORER: '探索',
  CREATOR: '创作者',
  EDITOR: '编辑',
  OPERATOR: '运营'
};

/** 角色英文枚举 → 顶栏徽标中文文案,未知值原样返回 */
export function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role;
}

interface AuthState {
  token: string;
  refreshToken: string;
  user: MeResp | null;
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({ token: '', refreshToken: '', user: null }),
  actions: {
    /** 登录:调 /auth/login 与 /me,写 localStorage 并同步 state */
    async login(phone: string, password: string) {
      const tokens = await http.post<LoginResp>('/auth/login', { phone, password });
      // 先落 token,保证后续 /me 请求带上新 Bearer
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
