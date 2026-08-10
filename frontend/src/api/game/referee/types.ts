export interface RefereeVO {
  /**
   *
   */
  id: string | number;

  /**
   *
   */
  tenantId: string | number;

  /**
   *
   */
  tournamentId: string | number;

  /**
   * 裁判名称
   */
  name: string;

  /**
   * 头像URL
   */
  avatar?: string;

  /**
   * 登录凭证
   */
  authKey: string;

  /**
   * 已绑定的赛段数量(>0 视为 ACTIVE)
   */
  assignedStageCount?: number;

  /**
   * 权限: ["STAGE_1_GROUP_A"]
   */
  permissions: string;

  /**
   * 备注
   */
  remark: string;
}

export interface RefereeForm extends BaseEntity {
  /**
   *
   */
  id?: string | number;

  /**
   *
   */
  tenantId?: string | number;

  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 裁判名称
   */
  name?: string;

  /**
   * 头像URL
   */
  avatar?: string;

  /**
   * 登录凭证
   */
  authKey?: string;

  /**
   * 权限: ["STAGE_1_GROUP_A"]
   */
  permissions?: string;

  /**
   * 备注
   */
  remark?: string;
}

export interface RefereeQuery extends PageQuery {
  /**
   *
   */
  tournamentId?: string | number;

  /**
   * 裁判名称
   */
  name?: string;

  /**
   * 登录凭证
   */
  authKey?: string;

  /**
   * 日期范围参数
   */
  params?: any;
}
