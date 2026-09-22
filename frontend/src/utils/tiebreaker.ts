/**
 * 同分加赛(二海/三海…)识别。
 *
 * <p>口径与后端 {@code SettlementSupport.isTiebreaker} 对齐:优先看
 * {@code matchType === 'TIEBREAKER'}(显式字段);历史数据该列为空,
 * 退回 {@code remark} 前缀「同分加赛」识别——老库不需要迁移即可继续工作。</p>
 *
 * <p>不要在各页面各写一份 {@code remark.startsWith(...)}:显式字段一旦成为唯一口径,
 * 散落的文本判断会静默失效。</p>
 */

/** 加赛场次的显式类型标记(对应 t_match.match_type) */
export const MATCH_TYPE_TIEBREAKER = 'TIEBREAKER';

/** 历史数据的加赛备注前缀(对应 t_match.remark) */
export const TIEBREAKER_REMARK_PREFIX = '同分加赛';

/** 该场次是否为同分加赛(二海/三海…);入参可为 match VO */
export function isTiebreakerMatch(match: any): boolean {
  if (!match) {
    return false;
  }
  const type = match.matchType;
  if (type === MATCH_TYPE_TIEBREAKER) {
    return true;
  }
  // 显式标了其它类型(如 NORMAL)就不是加赛,不再看 remark
  if (type != null && type !== '') {
    return false;
  }
  return String(match.remark || '').startsWith(TIEBREAKER_REMARK_PREFIX);
}
