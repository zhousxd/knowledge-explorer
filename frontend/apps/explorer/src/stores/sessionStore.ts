import { defineStore } from 'pinia';
import { createSession, updateExplainLevel } from '../api/sessions';
import type { ExplainLevel } from '../api/sessions';

/**
 * 会话轻量 store(P5-21/22):卡片页服务键的前置会话上下文 + 讲解档位记忆(FR-E10)。
 * 页面级生命周期 —— 不做持久化/恢复(刷新后由下次服务键重建,可接受 MVP);跨页(卡片页 →
 * 执行态页)存活由 Pinia 应用级单例保证。首次服务键创建会话(theme=卡专题,goal 置空 ——
 * P5-21 授权简化),后续复用同一会话,服务 run 全部挂根节点(parentNodeId 缺省),
 * 完整路径挂接由后续任务接续。
 */
interface SessionState {
  sessionId: number | null;
  /** 会话专题(THEMES key,首建时落定) */
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
     * 卡片页服务键前置:无会话则建(POST /api/sessions)并本地记住;已有则直接复用。
     * 失败原样抛出由视图 toast(不落脏 sessionId,下次可重试)。
     */
    async ensureForCard(card: { theme: string }): Promise<number> {
      if (this.sessionId != null) return this.sessionId;
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
