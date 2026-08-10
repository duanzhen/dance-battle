export interface MatchParticipantVO {
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
  matchId: string | number;

  /**
   *
   */
  competitorId: string | number;

  /**
   *
   */
  displaySlotIndex: number;

  /**
   * 总分/票数
   */
  scoreValue: number;

  /**
   * 本场排名
   */
  rankInMatch: number;

  /**
   * 选手结果
   */
  outcomeStatus: string;

  /**
   * 备注
   */
  remark: string;
}

export interface MatchParticipantForm extends BaseEntity {
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
  matchId?: string | number;

  /**
   *
   */
  competitorId?: string | number;

  /**
   *
   */
  displaySlotIndex?: number;

  /**
   * 总分/票数
   */
  scoreValue?: number;

  /**
   * 本场排名
   */
  rankInMatch?: number;

  /**
   * 选手结果
   */
  outcomeStatus?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface MatchParticipantQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  matchId?: string | number;

  /**
   *
   */
  competitorId?: string | number;

  /**
   *
   */
  displaySlotIndex?: number;

  /**
   * 总分/票数
   */
  scoreValue?: number;

  /**
   * 本场排名
   */
  rankInMatch?: number;

  /**
   * 选手结果
   */
  outcomeStatus?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
