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
