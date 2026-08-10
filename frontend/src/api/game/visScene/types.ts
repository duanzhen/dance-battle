export interface VisSceneVO {
  /**
   *
   */
  id: string | number;

  /**
   *
   */
  tournamentId: string | number;

  /**
   * 场景名: 总决赛KV / 竖屏比分
   */
  name: string;

  /**
   *
   */
  designWidth: string | number;

  /**
   *
   */
  designHeight: number;

  /**
   *
   */
  backgroundConfig: string;

  /**
   * 场景格式：DEFAULT / VERTICAL / CUSTOM
   */
  format: string;

  /**
   * 背景颜色
   */
  bgColor: string;

  /**
   * 是否为模板场景：0-否 1-是
   */
  isTemplate: number;

  /**
   * 模板分类：MAIN / SIDE / SCOREBOARD
   */
  templateCategory: string;

  /**
   * 场景排序
   */
  sortOrder: number;

  /**
   * 备注
   */
  remark: string;
}

export interface VisSceneForm extends BaseEntity {
  /**
   *
   */
  id?: string | number;

  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 场景名: 总决赛KV / 竖屏比分
   */
  name?: string;

  /**
   *
   */
  designWidth?: string | number;

  /**
   *
   */
  designHeight?: number;

  /**
   *
   */
  backgroundConfig?: string;

  /**
   * 场景格式：DEFAULT / VERTICAL / CUSTOM
   */
  format?: string;

  /**
   * 背景颜色
   */
  bgColor?: string;

  /**
   * 是否为模板场景：0-否 1-是
   */
  isTemplate?: number;

  /**
   * 模板分类：MAIN / SIDE / SCOREBOARD
   */
  templateCategory?: string;

  /**
   * 场景排序
   */
  sortOrder?: number;

  /**
   * 备注
   */
  remark?: string;
}

export interface VisSceneQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 场景名: 总决赛KV / 竖屏比分
   */
  name?: string;

  /**
   *
   */
  designWidth?: string | number;

  /**
   *
   */
  designHeight?: number;

  /**
   *
   */
  backgroundConfig?: string;

  /**
   * 场景格式：DEFAULT / VERTICAL / CUSTOM
   */
  format?: string;

  /**
   * 背景颜色
   */
  bgColor?: string;

  /**
   * 是否为模板场景：0-否 1-是
   */
  isTemplate?: number;

  /**
   * 模板分类：MAIN / SIDE / SCOREBOARD
   */
  templateCategory?: string;

  /**
   * 场景排序
   */
  sortOrder?: number;

  /**
   * 日期范围参数
   */
  params?: any;
}
