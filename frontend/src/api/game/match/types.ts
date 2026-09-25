export interface MatchVO {
  /**
   *
   */
  id: string | number;

  /**
   *
   */
  tournamentId: string | number;

  /**
   *
   */
  stageId: string | number;

  /**
   *
   */
  name: string;

  /**
   * 显示分区:LEFT/RIGHT(淘汰赛上下半区)、CENTER(季军赛/排名赛)、ZONE-n(海选第 n 圈)、G1..Gn(小组赛分组)
   */
  displayZone: string;

  /**
   * Y轴排序
   */
  displayRow: number;

  /**
   *
   */
  displayCol: number;

  /**
   *
   */
  status: string;

  /**
   * STANDARD, VOTING, RANKING
   */
  matchMode: string;

  /**
   * 场次类型:NORMAL=普通场 / TIEBREAKER=同分加赛(二海/三海…)
   * 识别加赛请统一用 @/utils/tiebreaker 的 isTiebreakerMatch(带 remark 兜底)
   */
  matchType?: string;

  /**
   *
   */
  promotionRule: string;

  /**
   * 备注
   */
  remark: string;
}

export interface MatchForm extends BaseEntity {
  /**
   *
   */
  id?: string | number;

  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  stageId?: string | number;

  /**
   *
   */
  name?: string;

  /**
   * 显示分区:LEFT/RIGHT(淘汰赛上下半区)、CENTER(季军赛/排名赛)、ZONE-n(海选第 n 圈)、G1..Gn(小组赛分组)
   */
  displayZone?: string;

  /**
   * Y轴排序
   */
  displayRow?: number;

  /**
   *
   */
  displayCol?: number;

  /**
   *
   */
  status?: string;

  /**
   * STANDARD, VOTING, RANKING
   */
  matchMode?: string;

  /**
   *
   */
  promotionRule?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface MatchQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  stageId?: string | number;

  /**
   *
   */
  name?: string;

  /**
   * 显示分区:LEFT/RIGHT(淘汰赛上下半区)、CENTER(季军赛/排名赛)、ZONE-n(海选第 n 圈)、G1..Gn(小组赛分组)
   */
  displayZone?: string;

  /**
   * Y轴排序
   */
  displayRow?: number;

  /**
   *
   */
  displayCol?: number;

  /**
   *
   */
  status?: string;

  /**
   * STANDARD, VOTING, RANKING
   */
  matchMode?: string;

  /**
   *
   */
  promotionRule?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
