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
   * LEFT, RIGHT, CENTER
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
   * LEFT, RIGHT, CENTER
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
   * LEFT, RIGHT, CENTER
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
