/** 评分输入统一限制为最多两位小数 */
export const SCORE_INPUT_DECIMALS = 2;

export interface SanitizedScoreInput {
  /** 可写回输入框的规范化文本 */
  value: string;
  /** 输入超过配置上限,此时 value 为调用方给的 fallback */
  overMax: boolean;
  /** 含数字/小数点和逗号以外的字符,或存在多个小数点 */
  invalid: boolean;
}

interface SanitizeScoreInputOptions {
  max?: number | null;
  decimals?: number;
  fallback?: string;
}

/**
 * 规范化裁判分数输入:
 * - 兼容部分数字键盘输出的小数逗号,并统一成小数点;
 * - 只接受非负数字和一个小数点,多余小数位直接截断;
 * - 数值超过配置上限时回退到 fallback,不把超限值留在输入框。
 */
export function sanitizeScoreInput(raw: string, options: SanitizeScoreInputOptions = {}): SanitizedScoreInput {
  const decimals = Math.max(0, Math.floor(options.decimals ?? SCORE_INPUT_DECIMALS));
  const fallback = options.fallback ?? '';
  const text = String(raw ?? '')
    .trim()
    .replace(/,/g, '.');
  const invalid = !/^\d*\.?\d*$/.test(text);

  if (invalid) {
    return { value: fallback, overMax: false, invalid: true };
  }
  if (text === '') {
    return { value: '', overMax: false, invalid: false };
  }

  const [rawInt = '', rawDec] = text.split('.');
  const intText = rawInt.replace(/^0+(?=\d)/, '');
  const value = rawDec === undefined ? intText : `${intText || '0'}.${rawDec.slice(0, decimals)}`;
  const max = options.max == null ? Number.NaN : Number(options.max);
  const overMax = Number.isFinite(max) && Number(value) > max;

  return {
    value: overMax ? fallback : value,
    overMax,
    invalid: false
  };
}
