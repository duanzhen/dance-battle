import { describe, expect, it } from 'vitest';
import { sanitizeScoreInput } from './scoreInput';

describe('sanitizeScoreInput', () => {
  it('只保留数字和一个小数点', () => {
    expect(sanitizeScoreInput('12b.3').value).toBe('');
    expect(sanitizeScoreInput('12b.3', { fallback: '8' }).value).toBe('8');
    expect(sanitizeScoreInput('1.2.3', { fallback: '1.2' }).value).toBe('1.2');
  });

  it('兼容数字键盘的小数逗号并去掉多余前导零', () => {
    expect(sanitizeScoreInput(',5').value).toBe('0.5');
    expect(sanitizeScoreInput('007.25').value).toBe('7.25');
  });

  it('小数最多保留两位', () => {
    expect(sanitizeScoreInput('9.876').value).toBe('9.87');
    expect(sanitizeScoreInput('9.').value).toBe('9.');
  });

  it('按维度配置拒绝超过上限的输入', () => {
    expect(sanitizeScoreInput('10.51', { max: 10.5, fallback: '10.5' })).toEqual({
      value: '10.5',
      overMax: true,
      invalid: false
    });
    expect(sanitizeScoreInput('10.5', { max: 10.5 }).value).toBe('10.5');
    expect(sanitizeScoreInput('99', { max: null }).value).toBe('99');
  });
});
