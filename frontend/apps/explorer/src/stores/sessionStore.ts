import { defineStore } from 'pinia';
import { createSession, fetchLatestSession, updateExplainLevel } from '../api/sessions';
import type { ExplainLevel } from '../api/sessions';

/**
 * 会话轻量 store:卡片页服务键的前置会话上下文 + 讲解档位记忆(FR-E10)。
 * Pinia 应用级单例跨页(卡片页 → 执行态页)存活;刷新后由 ensureForCard 断点续探 ——
 * FR-E01/A1 语义:连续探索属于「一次会话」,同专题复用最新 ACTIVE 会话链式挂节点,
 * 跨专题才新建(01 §3 会话按专题组织)。完整路径挂接见 CardView.launchRun(A2 游标)。
 */
interface SessionState {
  sessionId: number | null;
  /** 会话专题(THEMES key,首建/续探时落定) */
  theme: string;
  /** 会话目标(建会话置空串;标题展示由后端 titleOf=最新节点卡题兜底) */
  goal: string;
  /** 当前讲解档位(与会话创建默认 SIMPLE 一致;切换经 changeExplainLevel 落服务端) */
  explainLevel: ExplainLevel;
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    sessionId: null,
    theme: '',
    goal: '',
    explainLevel: 'SIMPLE'
  }),

  actions: {
    /**
     * 卡片页服务键前置:同专题存在最新 ACTIVE 会话则续探(FR-E01 断点续探,
     * A1「一次会话内连续深入」);跨专题/无会话才新建(POST /api/sessions)。
     * latest 404=null 归一;失败原样抛出由视图 toast(不落脏 sessionId,下次可重试)。
     */
    async ensureForCard(card: { theme: string }): Promise<number> {
      if (this.sessionId != null) return this.sessionId;
      const latest = await fetchLatestSession();
      if (latest != null && latest.theme === card.theme) {
        this.sessionId = latest.sessionId;
        this.theme = latest.theme;
        return this.sessionId;
      }
      const created = await createSession(card.theme, null);
      this.sessionId = created.sessionId;
      this.theme = card.theme;
      return this.sessionId;
    },

    /**
     * FR-E10 讲解档位切换:PUT /sessions/{id}/explain-level 成功后以响应回填本地档位;
     * 无会话/失败原样抛出由视图 toast(本地档位不变)。档位随下次服务提交生效(Task 22)。
     */
    async changeExplainLevel(level: ExplainLevel): Promise<void> {
      if (this.sessionId == null) {
        throw new Error('会话尚未创建');
      }
      const resp = await updateExplainLevel(this.sessionId, level);
      this.explainLevel = resp.explainLevel as ExplainLevel;
    }
  }
});
