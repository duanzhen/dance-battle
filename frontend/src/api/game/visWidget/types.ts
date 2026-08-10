export interface VisWidgetVO {
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
  sceneId: string | number;

  /**
   * 控件备注
   */
  name: string;

  /**
   * BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE
   */
  type: string;

  /**
   *
   */
  layoutConfig: string;

  /**
   * X坐标
   */
  x: number;

  /**
   * Y坐标
   */
  y: number;

  /**
   * 宽度
   */
  w: number;

  /**
   * 高度
   */
  h: number;

  /**
   * Z轴层级
   */
  zIndex: number;

  /**
   * 是否可见：0-隐藏 1-显示
   */
  visible: number;

  /**
   * 是否锁定：0-否 1-是（锁定后不可编辑）
   */
  locked: number;

  /**
   *
   */
  dataConfig: string;

  /**
   *
   */
  renderConfig: string;

  /**
   * 备注
   */
  remark: string;
}

export interface VisWidgetForm extends BaseEntity {
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
  sceneId?: string | number;

  /**
   * 控件备注
   */
  name?: string;

  /**
   * BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE
   */
  type?: string;

  /**
   *
   */
  layoutConfig?: string;

  /**
   * X坐标
   */
  x?: number;

  /**
   * Y坐标
   */
  y?: number;

  /**
   * 宽度
   */
  w?: number;

  /**
   * 高度
   */
  h?: number;

  /**
   * Z轴层级
   */
  zIndex?: number;

  /**
   * 是否可见：0-隐藏 1-显示
   */
  visible?: number;

  /**
   * 是否锁定：0-否 1-是（锁定后不可编辑）
   */
  locked?: number;

  /**
   *
   */
  dataConfig?: string;

  /**
   *
   */
  renderConfig?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface VisWidgetQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   *
   */
  sceneId?: string | number;

  /**
   * 控件备注
   */
  name?: string;

  /**
   * BRACKET, SCOREBOARD, PLAYER_CARD, IMAGE
   */
  type?: string;

  /**
   *
   */
  layoutConfig?: string;

  /**
   * X坐标
   */
  x?: number;

  /**
   * Y坐标
   */
  y?: number;

  /**
   * 宽度
   */
  w?: number;

  /**
   * 高度
   */
  h?: number;

  /**
   * Z轴层级
   */
  zIndex?: number;

  /**
   * 是否可见：0-隐藏 1-显示
   */
  visible?: number;

  /**
   * 是否锁定：0-否 1-是（锁定后不可编辑）
   */
  locked?: number;

  /**
   *
   */
  dataConfig?: string;

  /**
   *
   */
  renderConfig?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
