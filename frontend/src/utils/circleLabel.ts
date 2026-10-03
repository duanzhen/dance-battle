/**
 * 海选圈展示名(全站唯一口径):第 1 圈 = A圈、第 2 圈 = B圈… 超过 26 圈按 AA/AB 继续。
 * 仅展示用;后端存储/判定的分区名仍是 ZONE-k。
 */
export const circleLabel = (no?: number | string | null): string => {
  const n = Number(no);
  if (!Number.isFinite(n) || n < 1) return '';
  let x = Math.floor(n);
  let letters = '';
  while (x > 0) {
    const rem = (x - 1) % 26;
    letters = String.fromCharCode(65 + rem) + letters;
    x = Math.floor((x - 1) / 26);
  }
  return letters + '圈';
};
