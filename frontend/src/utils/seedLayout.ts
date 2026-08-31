/**
 * SEED 模式标准种子摆位(通用生成器)。
 *
 * 与后端 KnockoutGenerator.seedLayout 完全一致:
 * 以 4 人基数逐层翻倍,每对 (x,y)(x+y=n+1) 扩展为 {x,2n+1-x}、{y,2n+1-y}(x 的子对阵在前);
 * 新列内前半段的子对阵按 x/y 原样朝上,后半段按补数朝上;整表前一半=左列、后一半=右列。
 * 任意 2 的幂规模(16/32/64/128…)均可生成,无需查表。
 */

/** 4 人基数(赛事约定):左侧 (1,4);右侧 (3,2) */
const BASE_4 = [1, 4, 3, 2];

/** 递归种子位(与后端 KnockoutGenerator.seedPositions 一致):4 以下规模回退 */
const seedPositions = (n: number): number[] => {
  if (n <= 1) return [1];
  const prev = seedPositions(Math.floor(n / 2));
  const res: number[] = new Array(n);
  for (let i = 0; i < prev.length; i++) {
    res[2 * i] = prev[i];
    res[2 * i + 1] = n + 1 - prev[i];
  }
  return res;
};

/** 取某规模的种子摆位:4 人基数逐层递推;4 以下或非 2 的幂回退递归种子位 */
export const seedLayout = (n: number): number[] => {
  if (n <= 4) {
    return n === 4 ? [...BASE_4] : seedPositions(n);
  }
  if ((n & (n - 1)) !== 0) {
    return seedPositions(n);
  }
  const prev = seedLayout(Math.floor(n / 2));
  const out: number[] = new Array(n);
  const parentCount = prev.length / 2;
  const parentsPerColumn = prev.length / 4; // 每列父对阵数
  const halfChildren = parentsPerColumn; // 每列子对阵前半段数量
  let idx = 0;
  for (let i = 0; i < parentCount; i++) {
    const x = prev[2 * i];
    const y = prev[2 * i + 1];
    const cx = n + 1 - x;
    const cy = n + 1 - y;
    const childBase = (i % parentsPerColumn) * 2; // 子对阵在列内起始位置
    const child1AsIs = childBase < halfChildren;
    const child2AsIs = childBase + 1 < halfChildren;
    if (child1AsIs) {
      out[idx++] = x;
      out[idx++] = cx;
    } else {
      out[idx++] = cx;
      out[idx++] = x;
    }
    if (child2AsIs) {
      out[idx++] = y;
      out[idx++] = cy;
    } else {
      out[idx++] = cy;
      out[idx++] = y;
    }
  }
  return out;
};
