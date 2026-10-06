/**
 * 赛段名单(roster)类型:名单是赛段属性,以 stageId 寻址。
 */

export interface RosterVO {
  /** = 赛段 id */
  id: string | number;
  tournamentId?: string | number;
  targetStageId: string | number;
  /** 首来源组冗余展示(规则以 groups 为准) */
  sourceStageId?: string | number | null;
  resultFilter?: string;
  quota?: number;
  fillMode?: string;
  /** CONFIRMED/SKIPPED/READY/WAIT_SOURCE */
  state?: string;
  remark?: string;
  groups?: RosterGroup[];
  overrides?: RosterOverride[];
}

export interface RosterApplyBody {
  manualSelections?: Record<string, (string | number)[]>;
}

export interface RosterForm {
  sourceStageId?: string | number | null;
  resultFilter?: string;
  rankBandStart?: number | null;
  rankBandEnd?: number | null;
  zoneFilter?: string | null;
  roundFilter?: number | null;
  scoreMin?: number | null;
  scoreMax?: number | null;
  quota?: number;
  fillMode?: string;
  remark?: string;
  groups?: RosterGroupForm[];
}

export interface RosterGroupForm {
  resultFilter?: string;
  zone?: string;
  rankStart?: number | null;
  rankEnd?: number | null;
  rankByZone?: boolean;
  round?: number | null;
  scoreMin?: number | null;
  scoreMax?: number | null;
  orderBy?: string;
}

export interface RosterGroup {
  sourceStageId?: string | number | null;
  resultFilter?: string;
  zone?: string;
  rankStart?: number | null;
  rankEnd?: number | null;
  rankByZone?: boolean;
  round?: number | null;
  scoreMin?: number | null;
  scoreMax?: number | null;
  fillMode?: string;
  quota?: number;
  orderBy?: string;
}

export interface RosterGroupRule {
  sourceStageId?: string | number | null;
  resultFilter?: string;
  zone?: string | null;
  rankStart?: number | null;
  rankEnd?: number | null;
  rankByZone?: boolean | null;
  round?: number | null;
  scoreMin?: number | null;
  scoreMax?: number | null;
  fillMode?: string;
  quota?: number;
  orderBy?: string;
}

export type OverrideOp = 'ADD_SOURCE' | 'ADD_GUEST' | 'REMOVE' | 'SEED';

export interface RosterOverride {
  id: string | number;
  targetStageId?: string | number;
  op: OverrideOp;
  sourceCompetitorId?: string | number | null;
  playerId?: string | number | null;
  guestName?: string | null;
  guestType?: number | null;
  guestNumber?: string | null;
  seedRank?: number | null;
  /** 新增行时的放置方式:INSERT = 插到该座位并让后面的人后移;其它/不传 = 替换该座位 */
  placement?: 'INSERT' | 'REPLACE' | 'HOLDING';
  remark?: string;
}

/** 移出意图:把若干行从名单里拿掉,可选"后面的人整体顶上一位" */
export interface RosterRemoveBody {
  ids?: (string | number)[];
  sourceCompetitorIds?: (string | number)[];
  fillGap?: boolean;
}

export interface RosterOverrideBody {
  op: OverrideOp;
  sourceCompetitorId?: string | number | null;
  playerId?: string | number | null;
  guestName?: string | null;
  guestType?: number | null;
  guestNumber?: string | null;
  seedRank?: number | null;
  remark?: string;
}

export interface RosterPreview {
  stageId?: string | number;
  targetStageId?: string | number;
  ready?: boolean;
  applied?: boolean;
  skipped?: boolean;
  capacity?: number;
  items?: RosterPreviewItem[];
  warnings?: string[];
}

/** 中间态名单的移动意图:把某行移到目标座位(不传座位 = 移到待落位区) */
export interface RosterMoveBody {
  /** 中间层行 ID(优先) */
  overrideId?: string | number | null;
  /** 来源参赛方 ID */
  sourceCompetitorId?: string | number | null;
  /** 目标座位号;不传表示移到待落位区 */
  targetSeed?: number | null;
  /** 显式声明"移到待落位区" */
  toHolding?: boolean;
}

export interface RosterPreviewItem {
  refType: 'SOURCE' | 'GUEST';
  overrideId?: string | number | null;
  sourceCompetitorId?: string | number | null;
  sourceStageId?: string | number | null;
  entryTag?: string;
  playerId?: string | number | null;
  name?: string;
  type?: number;
  number?: string;
  outcomeStatus?: string;
  finalRank?: number | null;
  seedRank?: number | null;
  /** 这一行现在能不能调整:多入口汇合全放开;单入口里来源未结算的行会锁住(后端同口径) */
  adjustable?: boolean;
  /** 这一行来自的来源赛段还没结算:多入口汇合可以先排位(但选手后续可能变化) */
  sourcePending?: boolean;
}

export interface RosterCandidates {
  stageId?: string | number;
  remark?: string | null;
  state?: string | null;
  groups?: {
    label?: string;
    resultFilter?: string | null;
    zone?: string | null;
    rankStart?: number | null;
    rankEnd?: number | null;
    rankByZone?: boolean | null;
    round?: number | null;
    scoreMin?: number | null;
    scoreMax?: number | null;
    sourceStageId?: string | number | null;
    competitors?: any[];
  }[];
}
