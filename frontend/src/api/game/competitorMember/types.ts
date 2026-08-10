export interface CompetitorMemberVO {
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
  competitorId: string | number;

  /**
   *
   */
  playerId: string | number;

  /**
   * CAPTAIN, MEMBER
   */
  role: string;

  /**
   * 备注
   */
  remark: string;
}

export interface CompetitorMemberForm extends BaseEntity {
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
  competitorId?: string | number;

  /**
   *
   */
  playerId?: string | number;

  /**
   * CAPTAIN, MEMBER
   */
  role?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface CompetitorMemberQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  competitorId?: string | number;

  /**
   *
   */
  playerId?: string | number;

  /**
   * CAPTAIN, MEMBER
   */
  role?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
