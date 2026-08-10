import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 比赛结果提交接口(裁判台实时 / 管理端批量共用)
 */

export interface ScoreEntry {
  competitorId: string | number;
  /** 维度(MULTI_DIM 时如 TECH/SHOW,默认 MAIN) */
  dimension?: string;
  score: number;
  /** 打分裁判ID */
  refereeId?: string | number;
  /** 动作:SCORE / VOTE */
  action?: string;
}

export interface SubmitResultForm {
  /** STANDARD 模式:competitorId -> WIN/LOSS/DRAW */
  outcomes?: Record<string, string>;
  /** VOTING/RANKING 模式:明细分 */
  scores?: ScoreEntry[];
  /** 提交裁判ID */
  refereeId?: string | number;
  /** 提交后若赛段所有场已结算,是否自动 complete(默认 true) */
  finalizeStageIfComplete?: boolean;
}

export interface ParticipantResult {
  competitorId: string | number;
  scoreValue: number;
  rankInMatch: number;
  /** 本场结果 WIN/LOSS/DRAW(STANDARD);其他模式为空 */
  outcomeStatus?: string;
}

export interface MatchResultVO {
  matchId: string | number;
  status: string;
  participants: ParticipantResult[];
}

/** 提交比赛结果 */
export const submitResult = (id: string | number, data: SubmitResultForm): AxiosPromise<MatchResultVO> => {
  return request({
    url: '/game/match/' + id + '/submit-result',
    method: 'post',
    data
  });
};

/** 导播台确认公布结果(MANUAL 模式):用裁判判完暂存的结果结算场次 */
export const publishResult = (id: string | number): AxiosPromise<MatchResultVO> => {
  return request({
    url: '/game/match/' + id + '/publish-result',
    method: 'post'
  });
};

/**
 * 开始指定场次:PENDING → GAMING(赛段未开始则随场次开始),其余场次保持 PENDING
 * 用于跳过其他场次、先开始指定场次
 */
export const startMatch = (id: string | number) => {
  return request({
    url: '/game/match/' + id + '/start',
    method: 'post'
  });
};

/** 回退单场结算(调试用) */
export const resetMatch = (id: string | number) => {
  return request({
    url: '/game/match/' + id + '/reset',
    method: 'post'
  });
};
