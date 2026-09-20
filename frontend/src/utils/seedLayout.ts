/**
 * SEED 模式标准种子摆位(前端镜像)。
 *
 * <p><b>与后端 {@code KnockoutGenerator.seedLayout} 必须逐字一致</b>——后端按它生成对阵与
 * 中间态预排,前端按它画对战树,任何一侧单独改动都会出现"图上配对 ≠ 实际场次配对"。
 * 后端只有这一个摆位口径(非 2 的幂按向上取整的规模摆位),前端不再保留第二套。</p>
 *
 * 以 4 人基数逐层翻倍,每对 (x,y)(x+y=n+1) 扩展为 {x,2n+1-x}、{y,2n+1-y}(x 的子对阵在前);
 * 新列内前半段的子对阵按 x/y 原样朝上,后半段按补数朝上;整表前一半=左列、后一半=右列。
 * 任意 2 的幂规模(16/32/64/128…)均可生成,无需查表。
 */

/** 4 人基数(赛事约定):左侧 (1,4);右侧 (3,2) */
const BASE_4 = [1, 4, 3, 2];

/** 取某规模的种子摆位:4 人基数逐层递推;非 2 的幂按向上取整的规模摆位 */
export const seedLayout = (n: number): number[] => {
  if (n <= 1) return n === 1 ? [1] : [];
  if (n === 2) return [1, 2];
  if (n === 4) return [...BASE_4];
  if ((n & (n - 1)) !== 0) {
    let p = 1;
    while (p < n) p <<= 1;
    return seedLayout(p);
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

/**
 * 与后端 KnockoutGeneratorTest 钉住的同一组基准:开发期自检,防止两侧悄悄改歪。
 * (前端暂无测试运行器,这里用 DEV 断言兜底;改后端摆位算法时请同步这里的期望值。)
 */
const KNOWN_LAYOUTS: Record<number, number[]> = {
  4: [1, 4, 3, 2],
  8: [1, 8, 5, 4, 3, 6, 7, 2],
  12: [1, 16, 8, 9, 12, 5, 13, 4, 3, 14, 6, 11, 10, 7, 15, 2],
  16: [1, 16, 8, 9, 12, 5, 13, 4, 3, 14, 6, 11, 10, 7, 15, 2]
};

if (import.meta.env?.DEV) {
  Object.entries(KNOWN_LAYOUTS).forEach(([size, expected]) => {
    const actual = seedLayout(Number(size));
    if (actual.join(',') !== expected.join(',')) {
      console.error(`[seedLayout] 与后端摆位不一致 n=${size}: 期望 ${expected} 实际 ${actual}`);
    }
  });
}
