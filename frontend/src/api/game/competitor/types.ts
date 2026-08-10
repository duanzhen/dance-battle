export interface CompetitorVO {
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
   * 上一阶段的CompetitorID
   */
  sourceCompetitorId: string | number;

  /**
   * 0:个人, 1:队伍
   */
  type: number;

  /**
   * 展示名称
   */
  name: string;

  /**
   * 参赛号
   */
  number: string;

  /**
   * 本赛段初始种子顺位
   */
  seedRank: number;

  /**
   * 本赛段最终排名
   */
  finalRank: number;

  /**
   * 本赛段结果
   */
  outcomeStatus: string;

  /**
   * 备注
   */
  remark: string;

  /**
   * 关联的选手列表
   */
  playerList?: Array<{
    id: string | number;
    tournamentId: string | number;
    name: string;
    avatar: string;
    idCard: string | number;
    competitorId?: string | number | null;
    competitorVo?: any;
    tags: string;
    remark: string;
  }> | null;
}

export interface CompetitorForm extends BaseEntity {
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
   * 选手ID（签到时关联选手）
   */
  playerId?: string | number;

  /**
   * 上一阶段的CompetitorID
   */
  sourceCompetitorId?: string | number;

  /**
   * 0:个人, 1:队伍
   */
  type?: number;

  /**
   * 展示名称
   */
  name?: string;

  /**
   * 本赛段初始种子顺位
   */
  seedRank?: number;

  /**
   * 本赛段最终排名
   */
  finalRank?: number;

  /**
   * 本赛段结果
   */
  outcomeStatus?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface CompetitorQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  stageId?: string | number;

  /**
   * 上一阶段的CompetitorID
   */
  sourceCompetitorId?: string | number;

  /**
   * 0:个人, 1:队伍
   */
  type?: number;

  /**
   * 展示名称
   */
  name?: string;

  /**
   * 本赛段初始种子顺位
   */
  seedRank?: number;

  /**
   * 本赛段最终排名
   */
  finalRank?: number;

  /**
   * 本赛段结果
   */
  outcomeStatus?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
