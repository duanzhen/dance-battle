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
  priority?: number;
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
  priority?: number;
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
  priority?: number;
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
  priority?: number;
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
  remark?: string;
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
