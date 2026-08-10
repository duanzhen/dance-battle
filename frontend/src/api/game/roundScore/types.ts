export interface RoundScoreVO {
  /**
   *
   */
  id: string | number;

  /**
   *
   */
  tenantId: string | number;

  /**
   *
   */
  tournamentId: string | number;

  /**
   *
   */
  roundId: string | number;

  /**
   *
   */
  competitorId: string | number;

  /**
   * SCORE, VOTE
   */
  action: string;

  /**
   *
   */
  refereeId: string | number;

  /**
   *
   */
  score: number;

  /**
   *
   */
  dimension: string;

  /**
   * 备注
   */
  remark: string;
}

export interface RoundScoreForm extends BaseEntity {
  /**
   *
   */
  id?: string | number;

  /**
   *
   */
  tenantId?: string | number;

  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  roundId?: string | number;

  /**
   *
   */
  competitorId?: string | number;

  /**
   * SCORE, VOTE
   */
  action?: string;

  /**
   *
   */
  refereeId?: string | number;

  /**
   *
   */
  score?: number;

  /**
   *
   */
  dimension?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface RoundScoreQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  roundId?: string | number;

  /**
   *
   */
  competitorId?: string | number;

  /**
   * SCORE, VOTE
   */
  action?: string;

  /**
   *
   */
  refereeId?: string | number;

  /**
   *
   */
  dimension?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
