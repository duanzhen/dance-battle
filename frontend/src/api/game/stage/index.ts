import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { StageVO, StageForm, StageQuery } from '@/api/game/stage/types';

/**
 * 查询赛段流程列表
 * @param query
 * @returns {*}
 */

export const listStage = (query?: StageQuery): AxiosPromise<StageVO[]> => {
  return request({
    url: '/game/stage/list',
    method: 'get',
    params: query
  });
};

/**
 * 查询赛段流程详细
 * @param id
 */
export const getStage = (id: string | number): AxiosPromise<StageVO> => {
  return request({
    url: '/game/stage/' + id,
    method: 'get'
  });
};

/**
 * 新增赛段流程
 * @param data
 */
export const addStage = (data: StageForm) => {
  return request({
    url: '/game/stage',
    method: 'post',
    data: data
  });
};

/**
 * 修改赛段流程
 * @param data
 */
export const updateStage = (data: StageForm) => {
  return request({
    url: '/game/stage',
    method: 'put',
    data: data
  });
};

/**
 * 删除赛段流程
 * @param id
 */
export const delStage = (id: string | number | Array<string | number>) => {
  return request({
    url: '/game/stage/' + id,
    method: 'delete'
  });
};

/**
 * 获取赛事第一个赛段
 * @param tournamentId 赛事ID
 */
export const getFirstStage = (tournamentId: string | number): AxiosPromise<StageVO> => {
  return request({
    url: '/game/stage/first/' + tournamentId,
    method: 'get'
  });
};

/**
 * 大屏赛程流转:赛事全部赛段链 + 当前进行中赛段/场次
 * @param tournamentId 赛事ID
 */
export const getStageFlow = (tournamentId: string | number) => {
  return request({
    url: '/game/stage/flow/' + tournamentId,
    method: 'get'
  });
};

/** 擂台赛总览:轮转队列(含每人积分)与当前对决 */
export const getArenaOverview = (stageId: string | number) => {
  return request({
    url: '/game/stage/' + stageId + '/arena-overview',
    method: 'get'
  });
};

/**
 * 嘉宾加入赛段(除海选外任意赛段,赛段中间态 PENDING/GAMING 可加)
 * @param stageId 赛段ID
 * @param data { name, type?, number?, playerId? }
 */
export const addStageGuest = (stageId: string | number, data: {
  name: string;
  type?: number;
  number?: string;
  playerId?: string | number;
}) => {
  return request({
    url: '/game/stage/' + stageId + '/guest',
    method: 'post',
    data
  });
};

/**
 * 插入嘉宾赛段:在指定赛段与其下一赛段之间插入淘汰赛赛段并变轨
 * (仅当下一赛段干净——无参赛方、未生成对阵、未结束时允许)
 * @param data { stageId, name, advanceCount? }
 */
export const insertGuestStage = (data: {
  stageId: string | number;
  name: string;
  advanceCount?: number;
}): AxiosPromise<StageVO> => {
  return request({
    url: '/game/stage/insert-guest-stage',
    method: 'post',
    data
  });
};

/**
 * 撤销插入的嘉宾赛段(未开始时允许,清理数据并恢复原链表)
 * @param stageId 嘉宾赛段ID
 */
export const removeGuestStage = (stageId: string | number) => {
  return request({
    url: '/game/stage/guest-stage/' + stageId,
    method: 'delete'
  });
};

/**
 * 按外部抽签结果批量设定赛段参赛方种子顺序(seedRank 1..n,仅未初始化时允许)
 * @param stageId 赛段ID
 * @param competitorIds 按抽签结果排列的参赛方ID(顺序即种子顺序)
 */
export const setStageSeedOrder = (stageId: string | number, competitorIds: (string | number)[]) => {
  return request({
    url: '/game/stage/' + stageId + '/seed-order',
    method: 'post',
    data: { competitorIds }
  });
};

/**
 * 下一赛段对战树预排:上一赛段胜者(含未最终确认)按种子顺位排入本赛段
 * @param stageId 要预排的(下一)赛段ID
 */
export const getStagePreBracket = (stageId: string | number) => {
  return request({
    url: '/game/stage/prebracket/' + stageId,
    method: 'get'
  });
};

/**
 * 排名赛同分晋级调整:赛段已结算后,导播台在中间态手动指定晋级者
 * (传入全部待定者即全部晋级,未选中的待定者标记淘汰)
 * @param stageId 排名赛赛段ID
 * @param competitorIds 要标记晋级的待定参赛者ID
 */
export const adjustStageAdvancement = (stageId: string | number, competitorIds: (string | number)[]) => {
  return request({
    url: '/game/stage/' + stageId + '/adjust-advancement',
    method: 'post',
    data: competitorIds
  });
};

/**
 * 排名赛排名明细:各圈参赛者的总分与各维度聚合分(排名展示组件维度模式使用)
 * @param stageId 排名赛赛段ID
 */
export const getStageRankDetail = (stageId: string | number) => {
  return request({
    url: '/game/stage/' + stageId + '/rank-detail',
    method: 'get'
  });
};
