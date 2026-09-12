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

/** 海选分圈确保圈场次:按配置建齐 ZONE 圈(一个圈 = 一个 match),幂等 */
export const ensureAuditionCircles = (id: string | number) => {
  return request({
    url: '/game/stage/' + id + '/ensure-circle-slots',
    method: 'post'
  });
};

/** 擂台赛参赛选手弃权:弃权后不再参与排队;进行中的对决包含该选手时作废并下一位补位 */
export const withdrawArenaCompetitor = (stageId: string | number, competitorId: string | number) => {
  return request({
    url: `/game/stage/${stageId}/competitor/${competitorId}/withdraw`,
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

/** 擂台赛:创建并开始下一场对决(胜者守擂、败者排到队尾;平局时擂主与挑战者均排到队尾) */
export const startNextArenaMatch = (id: string | number) => {
  return request({
    url: '/game/stage/' + id + '/arena-next',
    method: 'post'
  });
};
