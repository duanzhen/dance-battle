/**
 * 大屏屏幕配置(多控制端共用)类型定义。
 *
 * 屏幕从各控制端本地(history.state)上移到服务端,屏幕列表与"当前投射的场景"
 * 都按赛事持久化,任一台控制端改动都会广播给其它控制端。
 */
export interface VisScreenVO {
  /** 主键 */
  id: string | number;
  /** 所属赛事 */
  tournamentId: string | number;
  /** 屏幕名 */
  name: string;
  /** 排序 */
  sortOrder: number;
  /** 当前投射的场景ID(未投射为 null) */
  currentSceneId?: string | number | null;
  /** 备注 */
  remark?: string;
}

export interface VisScreenForm {
  /** 主键(修改时必填) */
  id?: string | number;
  /** 所属赛事 */
  tournamentId?: string | number;
  /** 屏幕名 */
  name?: string;
  /** 排序 */
  sortOrder?: number;
  /** 当前投射的场景ID */
  currentSceneId?: string | number | null;
  /** 备注 */
  remark?: string;
}

export interface VisScreenQuery {
  /** 所属赛事 */
  tournamentId: string | number;
}
