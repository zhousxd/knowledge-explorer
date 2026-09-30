import { defineStore } from 'pinia';
import { listReviews } from '../api/reviews';

/**
 * 审核待办计数(P2-7 预留位):侧栏「审核中心」徽标的数据源。
 * ReviewsView 挂载与每次裁决后刷新;WbLayout 只读 total 渲染徽标,不做请求。
 */
interface ReviewState {
  /** PENDING 队列总数;0 = 无待审(徽标退化为预留圆点) */
  total: number;
}

export const useReviewStore = defineStore('review', {
  state: (): ReviewState => ({ total: 0 }),
  actions: {
    /** 拉取 PENDING 总数(size=1 只为取 total);403 等错误静默归零——无权限角色不显示计数 */
    async refreshPendingTotal(): Promise<void> {
      try {
        const data = await listReviews({ status: 'PENDING', page: 1, size: 1 });
        this.total = data.total;
      } catch {
        this.total = 0;
      }
    }
  }
});
