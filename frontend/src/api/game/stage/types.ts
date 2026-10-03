import { RosterVO } from './rosterTypes';

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
   * AUDITION, KNOCKOUT, ARENA, RANK
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
   * 起始选手数量
   */
  teamCountStart: number;

  /**
   * 晋级选手数量
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
   * 入边池摘要(池化:目标赛段的名单来源)
   */
  incoming?: RosterVO[];

  /** 来源已全部结束但晋级者尚未确认(管理端列表下发,供开始赛段按钮判断依赖就绪) */
  awaitingAdvancement?: boolean;

  /** 赛事开启「跳过中间态确认」 */
  skipConfirm?: boolean;

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
   * 新增赛段的插入位置:插在该赛段之后,不传 = 插到链头。
   * 链顺序由后端按意图维护,不需要前端自己算前后指针。
   */
  afterStageId?: string | number;

  /**
   * 父ID (用于同分加赛)
   */
  parentStageId?: string | number;

  /**
   * 32进16 / 复活赛
   */
  name?: string;

  /**
   * AUDITION, KNOCKOUT, ARENA, RANK
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
   * 起始选手数量
   */
  teamCountStart?: number;

  /**
   * 晋级选手数量
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
   * 父ID (用于同分加赛)
   */
  parentStageId?: string | number;

  /**
   * 32进16 / 复活赛
   */
  name?: string;

  /**
   * AUDITION, KNOCKOUT, ARENA, RANK
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
   * 起始选手数量
   */
  teamCountStart?: number;

  /**
   * 晋级选手数量
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

/**
 * 赛段配置表单(只含配置字段)
 *
 * 刻意没有 prevStageId / nextStageId:配置保存走 `/game/stage/{id}/config`,
 * 后端不会、也无法从这条入口改赛段链。赛段在链上的位置不允许调整,移动入口已移除。
 */
export interface StageConfigForm {
  name?: string;
  stageMode?: string;
  members?: number;
  visualColIndex?: number;
  ruleConfig?: string;
  status?: string;
  teamCountStart?: number;
  teamCountEnd?: number;
  visualConfig?: string;
  remark?: string;
}
