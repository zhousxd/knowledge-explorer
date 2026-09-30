import type { CardStatus } from './api/types';

/**
 * 卡片状态 → 文案与 el-tag 内置 type(不写自定义 hex)。
 * 语义映射 04 文档 §2.4 工作台状态标签:已发布=绿(success)、待审核=警示(warning)、
 * 草稿=灰(info)、已停用=红(danger)。
 */
export const STATUS_META: Record<CardStatus, { label: string; tagType: 'success' | 'warning' | 'info' | 'danger' }> = {
  DRAFT: { label: '草稿', tagType: 'info' },
  PENDING: { label: '待审核', tagType: 'warning' },
  PUBLISHED: { label: '已发布', tagType: 'success' },
  DISABLED: { label: '已停用', tagType: 'danger' }
};

/** 模板类型 → chip 文案(04 文档 §2.4:入口模板 chip,主色浅底深字) */
export const TEMPLATE_LABELS: Record<string, string> = {
  TEXT: '图文',
  COMPARE: '对比',
  TIMELINE: '时间线',
  TASK: '任务'
};
