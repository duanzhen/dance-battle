export interface TournamentVO {
  /**
   *
   */
  id: string | number;

  /**
   * 赛事名称
   */
  name: string;

  /**
   * 封面图片URL
   */
  coverImage: string;

  /**
   * 详情(列表卡片描述展示用)
   */
  description?: string;

  /**
   * 0:筹备 1:进行中 2:结束
   */
  status: number;

  /**
   * 设计稿宽度
   */
  logicalWidth: string | number;

  /**
   * 设计稿高度
   */
  logicalHeight: number;

  /**
   * {"bgColor": "#000", "fontFamily": "Roboto"}
   */
  themeConfig: string;

  /**
   * 备注
   */
  remark: string;

  /**
   * 创建时间(ISO 字符串)
   */
  createTime?: string;

  /**
   * 参赛选手总数(列表聚合)
   */
  playerCount?: number;

  /**
   * 赛段名称列表(按赛段链顺序,列表聚合)
   */
  stageNames?: string[];
}

export interface TournamentForm extends BaseEntity {
  /**
   *
   */
  id?: string | number;

  /**
   * 赛事名称
   */
  name?: string;

  /**
   * 封面图片URL
   */
  coverImage?: string;

  /**
   * 0:筹备 1:进行中 2:结束
   */
  status?: number;

  /**
   * 设计稿宽度
   */
  logicalWidth?: string | number;

  /**
   * 设计稿高度
   */
  logicalHeight?: number;

  /**
   * {"bgColor": "#000", "fontFamily": "Roboto"}
   */
  themeConfig?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface TournamentQuery extends PageQuery {
  /**
   * 赛事名称
   */
  name?: string;

  /**
   * 0:筹备 1:进行中 2:结束
   */
  status?: number;

  /**
   * 设计稿宽度
   */
  logicalWidth?: string | number;

  /**
   * 设计稿高度
   */
  logicalHeight?: number;

  /**
   * {"bgColor": "#000", "fontFamily": "Roboto"}
   */
  themeConfig?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
