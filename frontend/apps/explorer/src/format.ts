/**
 * 探索端时间格式化(ResumeCard 眉标与路径节点副标共用);
 * 输入为后端 ISO-8601 串(P4-16 冻结契约),非法值原样兜底。
 */

/** ISO 时间 → 相对日(今天/昨天/N月N日),供「继续探索 · {相对日}探索」 */
export function formatRelativeDay(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diffDays = Math.round((startOfDay(new Date()) - startOfDay(date)) / 86400000);
  if (diffDays <= 0) return '今天';
  if (diffDays === 1) return '昨天';
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

/** ISO 时间 → 'N月D日 HH:mm',供路径节点副标 */
export function formatShortDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
