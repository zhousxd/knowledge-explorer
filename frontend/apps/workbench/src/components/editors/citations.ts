/**
 * 引用(citations)输入的解析与校验 —— 与后端写路径同规则:
 * 输入为逗号分隔数字串("1,2" 或全角"1,2"),空 = 未引用。
 * 权威契约:citations[n] 为指向 sources 数组的 1-based 索引,非空时每个编号
 * 须落在 [1, sources.length](后端 CitationIndexValidator,越界 → 400)。
 */

/** 解析引用输入;含非数字 token 返回 null(交由行内错误提示),空串返回 [] */
export function parseCitations(raw: string): number[] | null {
  const tokens = splitTokens(raw);
  if (tokens.length === 0) {
    return [];
  }
  const nums: number[] = [];
  for (const token of tokens) {
    if (!/^\d+$/.test(token)) {
      return null;
    }
    nums.push(Number(token));
  }
  return nums;
}

/** 行内校验文案('' = 合法):非数字 / 编号 < 1 / 超出来源数 */
export function citationsError(raw: string, sourcesCount: number): string {
  for (const token of splitTokens(raw)) {
    if (!/^\d+$/.test(token)) {
      return `引用索引「${token}」不是数字`;
    }
    const no = Number(token);
    if (no < 1) {
      return '引用编号须为 1 起的整数(未引用请留空)';
    }
    if (no > sourcesCount) {
      return `引用 [${no}] 超出来源范围(共 ${sourcesCount} 条)`;
    }
  }
  return '';
}

/** 组装进 content 的 citations 字段:非法或为空时省略(undefined = 不写该键) */
export function citationsOf(raw: string, sourcesCount: number): number[] | undefined {
  if (citationsError(raw, sourcesCount) !== '') {
    return undefined;
  }
  const parsed = parseCitations(raw);
  return parsed !== null && parsed.length > 0 ? parsed : undefined;
}

function splitTokens(raw: string): string[] {
  return raw
    .split(/[,,]/)
    .map((token) => token.trim())
    .filter((token) => token !== '');
}
