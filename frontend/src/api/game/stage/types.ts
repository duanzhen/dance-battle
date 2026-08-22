export interface StageVO {
  /**
   *
   */
  id: string | number;

  /**
   *
   */
  tournamentId: string | number;

  /**
   * 上一赛段ID
   */
  prevStageId: string | number;

  /**
   * 下一赛段ID (可修改以实现途中变轨)
   */
  nextStageId: string | number;

  /**
   * 父ID (用于同分加赛)
   */
  parentStageId: string | number;

  /**
   * 32进16 / 复活赛
   */
  name: string;

  /**
   * AUDITION, KNOCKOUT, GROUP, ARENA, RANK
   */
  stageMode: string;

  /**
   * 在大图中处于第几列 (X轴)
   */
  visualColIndex: number;

  /**
   *
   */
  ruleConfig: string;

  /**
   * 状态
   */
  status: string;

  /**
   * 起始队伍数量
   */
  teamCountStart: number;

  /**
   * 晋级队伍数量
   */
  teamCountEnd: number;

  /**
   * 赛制格式：BO1 / BO3 / BO5
   */
  format: string;

  /**
   * 是否完成初始化配置：0-否 1-是
   */
  isInitialized: number;

  /**
   * 视觉配置：{"color": "#f59e0b", "icon": "trophy"}
   */
  visualConfig: string;

  /**
   * 备注
   */
  remark: string;
}

export interface StageForm extends BaseEntity {
  /**
   *
   */
  id?: string | number;

  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 上一赛段ID
   */
  prevStageId?: string | number;

  /**
   * 下一赛段ID (可修改以实现途中变轨)
   */
  nextStageId?: string | number;

  /**
   * 父ID (用于同分加赛)
   */
  parentStageId?: string | number;

  /**
   * 32进16 / 复活赛
   */
  name?: string;

  /**
   * AUDITION, KNOCKOUT, GROUP, ARENA, RANK
   */
  stageMode?: string;

  /**
   * 在大图中处于第几列 (X轴)
   */
  visualColIndex?: number;

  /**
   *
   */
  ruleConfig?: string;

  /**
   * 状态
   */
  status?: string;

  /**
   * 起始队伍数量
   */
  teamCountStart?: number;

  /**
   * 晋级队伍数量
   */
  teamCountEnd?: number;

  /**
   * 赛制格式：BO1 / BO3 / BO5
   */
  format?: string;

  /**
   * 是否完成初始化配置：0-否 1-是
   */
  isInitialized?: number;

  /**
   * 视觉配置：{"color": "#f59e0b", "icon": "trophy"}
   */
  visualConfig?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface StageQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 上一赛段ID
   */
  prevStageId?: string | number;

  /**
   * 下一赛段ID (可修改以实现途中变轨)
   */
  nextStageId?: string | number;

  /**
   * 父ID (用于同分加赛)
   */
  parentStageId?: string | number;

  /**
   * 32进16 / 复活赛
   */
  name?: string;

  /**
   * AUDITION, KNOCKOUT, GROUP, ARENA, RANK
   */
  stageMode?: string;

  /**
   * 在大图中处于第几列 (X轴)
   */
  visualColIndex?: number;

  /**
   *
   */
  ruleConfig?: string;

  /**
   * 状态
   */
  status?: string;

  /**
   * 起始队伍数量
   */
  teamCountStart?: number;

  /**
   * 晋级队伍数量
   */
  teamCountEnd?: number;

  /**
   * 赛制格式：BO1 / BO3 / BO5
   */
  format?: string;

  /**
   * 是否完成初始化配置：0-否 1-是
   */
  isInitialized?: number;

  /**
   * 视觉配置：{"color": "#f59e0b", "icon": "trophy"}
   */
  visualConfig?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
