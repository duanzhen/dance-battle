export interface PlayerVO {
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
  name: string;

  /**
   *
   */
  avatar: string;

  /**
   * 身份唯一标识
   */
  idCard: string | number;

  /**
   * 参赛队伍ID（签到后生成）
   */
  competitorId?: string | number | null;

  /**
   * 参赛队伍信息（签到后返回）
   */
  competitorVo?: {
    id: string | number;
    tournamentId: string | number;
    stageId: string | number;
    sourceCompetitorId: string | number | null;
    type: number;
    name: string;
    number: string;
    seedRank: number | null;
    finalRank: number | null;
    outcomeStatus: string | null;
    remark: string | null;
    playerList: any;
  } | null;

  /**
   * 标签: ["种子", "外卡"]
   */
  tags: string;

  /**
   * 备注
   */
  remark: string;
}

export interface PlayerForm extends BaseEntity {
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
  name?: string;

  /**
   *
   */
  avatar?: string;

  /**
   * 身份唯一标识
   */
  idCard?: string | number;

  /**
   * 标签: ["种子", "外卡"]
   */
  tags?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface PlayerQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 赛段ID
   */
  stageId?: string | number;

  /**
   *
   */
  name?: string;

  /**
   *
   */
  avatar?: string;

  /**
   * 身份唯一标识
   */
  idCard?: string | number;

  /**
   * 标签: ["种子", "外卡"]
   */
  tags?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
