import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 赛段生命周期接口(赛事流程引擎)
 */

export interface InitializeStageForm {
  stageId: string | number;
  /** 显式指定参赛方(可选,默认取本赛段所有 PENDING 参赛方) */
  competitorIds?: (string | number)[];
  /** 是否按上赛段 finalRank 自动排种子,默认 true */
  autoSeedFromRank?: boolean;
}

export interface GenerateMatchesForm {
  stageId: string | number;
  /** 覆盖 ruleConfig 后再生成(可选) */
  ruleConfig?: string;
}

/** 初始化赛段:锁定参赛方名单 + 排种子 */
export const initializeStage = (data: InitializeStageForm) => {
  return request({
    url: '/game/stage/initialize',
    method: 'post',
    data
  });
};

/** 生成对阵 */
export const generateMatches = (data: GenerateMatchesForm) => {
  return request({
    url: '/game/stage/generate-matches',
    method: 'post',
    data
  });
};

export interface CircleCompetitorInfo {
  competitorId: string | number;
  name: string;
  number: string;
  slotIndex: number;
}

export interface CircleAssignVo {
  matchId: string | number;
  matchName: string;
  displayZone: string;
  competitors: CircleCompetitorInfo[];
}

/** 海选分圈随机抽取:已签到选手随机分到各圈场次(可重抽,赛段未开始时) */
export const randomCircles = (id: string | number): AxiosPromise<CircleAssignVo[]> => {
  return request({
    url: '/game/stage/' + id + '/random-circles',
    method: 'post'
  });
};

/** 开始赛段:PENDING→GAMING */
export const startStage = (id: string | number) => {
  return request({
    url: '/game/stage/' + id + '/start',
    method: 'put'
  });
};

/** 完成赛段:GAMING→SETTLED(结算后需在中间态「确认晋级」) */
export const completeStage = (id: string | number) => {
  return request({
    url: '/game/stage/' + id + '/complete',
    method: 'put'
  });
};

/** 重置赛段为草稿:清除已生成对阵,参赛方回退待定,可重新排种子/生成(仅 DRAFT/PENDING 可用) */
export const resetStageToDraft = (id: string | number) => {
  return request({
    url: '/game/stage/' + id + '/reset-to-draft',
    method: 'post'
  });
};

/** 擂台赛:创建并开始下一场对决(胜者守擂、败者排到队尾) */
export const startNextArenaMatch = (id: string | number) => {
  return request({
    url: '/game/stage/' + id + '/arena-next',
    method: 'post'
  });
};

/** 计算晋级(MANUAL 模式显式触发),返回晋级人数 */
export const calculateAdvancement = (id: string | number, data?: { seedOverrides?: Record<string, number> }): AxiosPromise<number> => {
  return request({
    url: '/game/stage/' + id + '/calculate-advancement',
    method: 'post',
    data: data || {}
  });
};

/**
 * 海选弃权/顶替(结算后、确认晋级前):
 * 仅传 withdrawnCompetitorId=标记弃权,其后晋级者名次整体前移(不顶替时末尾空位即轮空);
 * 传 replacementCompetitorId=把任意被淘汰的选手顶替晋级,补齐到晋级名单末尾。
 */
export const promoteReplacement = (
  stageId: string | number,
  data: { withdrawnCompetitorId?: string | number; replacementCompetitorId?: string | number }
) => {
  return request({
    url: '/game/stage/' + stageId + '/promote-replacement',
    method: 'post',
    data
  });
};
