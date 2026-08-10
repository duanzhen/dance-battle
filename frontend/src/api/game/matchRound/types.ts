export interface MatchRoundVO {
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
  roundSequence: number;

  /**
   *
   */
  status: string;

  /**
   * 备注
   */
  remark: string;
}

export interface MatchRoundForm extends BaseEntity {
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
  roundSequence?: number;

  /**
   *
   */
  status?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface MatchRoundQuery extends PageQuery {
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
  roundSequence?: number;

  /**
   *
   */
  status?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
