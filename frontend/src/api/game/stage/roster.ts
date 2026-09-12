import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import {
  RosterVO,
  RosterApplyBody,
  RosterForm,
  RosterCandidates,
  RosterGroupRule,
  RosterOverride,
  RosterOverrideBody,
  RosterPreview
} from './rosterTypes';

/** 目标赛段名单详情 */
export const getStageRoster = (stageId: string | number): AxiosPromise<RosterVO> => {
  return request({
    url: `/game/stage/${stageId}/roster`,
    method: 'get'
  });
};

/** 出口视角:引用某来源赛段的名单列表 */
export const listRostersBySource = (sourceStageId: string | number): AxiosPromise<RosterVO[]> => {
  return request({
    url: '/game/stage/roster/list',
    method: 'get',
    params: { sourceStageId }
  });
};

/** 整单装配(快照物化,唯一写库动作) */
export const applyStageRoster = (stageId: string | number, data: RosterApplyBody): AxiosPromise<number> => {
  return request({
    url: `/game/stage/${stageId}/roster/apply`,
    method: 'post',
    data
  });
};

/** 追加来源组(多组并集,幂等去重) */
export const addRosterGroups = (stageId: string | number, data: RosterForm): AxiosPromise<RosterVO> => {
  return request({
    url: `/game/stage/${stageId}/roster/groups`,
    method: 'post',
    data
  });
};

/** 显式跳过(本赛段不带人) */
export const skipStageRoster = (stageId: string | number) => {
  return request({
    url: `/game/stage/${stageId}/roster/skip`,
    method: 'post'
  });
};

/** 名单候选(按来源组返回) */
export const getRosterCandidates = (stageId: string | number): AxiosPromise<RosterCandidates> => {
  return request({
    url: `/game/stage/${stageId}/roster/candidates`,
    method: 'get'
  });
};

/** 名单实时预览(规则 + 人工覆盖合并) */
export const getRosterPreview = (stageId: string | number): AxiosPromise<RosterPreview> => {
  return request({
    url: `/game/stage/${stageId}/roster/preview`,
    method: 'get'
  });
};

/** 保存手工名单顺序(中间态两列拖动结果) */
export const setRosterOrder = (stageId: string | number, items: { sourceCompetitorId?: string | number; overrideId?: string | number }[]) => {
  return request({
    url: `/game/stage/${stageId}/roster/order`,
    method: 'put',
    data: { items }
  });
};

/** 删除某一来源组 */
export const removeRosterGroup = (stageId: string | number, groupIndex: number) => {
  return request({
    url: `/game/stage/${stageId}/roster/groups/${groupIndex}`,
    method: 'delete'
  });
};

/** 编辑某一来源组规则(出口/入口自定义配置共用) */
export const updateRosterGroup = (stageId: string | number, groupIndex: number, data: RosterGroupRule) => {
  return request({
    url: `/game/stage/${stageId}/roster/groups/${groupIndex}`,
    method: 'put',
    data
  });
};

/** 名单人工覆盖列表 */
export const listRosterOverrides = (stageId: string | number): AxiosPromise<RosterOverride[]> => {
  return request({
    url: `/game/stage/${stageId}/roster/overrides`,
    method: 'get'
  });
};

/** 新增人工覆盖 */
export const addRosterOverride = (stageId: string | number, data: RosterOverrideBody): AxiosPromise<RosterOverride> => {
  return request({
    url: `/game/stage/${stageId}/roster/overrides`,
    method: 'post',
    data
  });
};

/** 编辑人工覆盖 */
export const updateRosterOverride = (
  stageId: string | number,
  overrideId: string | number,
  data: RosterOverrideBody
) => {
  return request({
    url: `/game/stage/${stageId}/roster/overrides/${overrideId}`,
    method: 'put',
    data
  });
};

/** 撤销人工覆盖 */
export const deleteRosterOverride = (stageId: string | number, overrideId: string | number) => {
  return request({
    url: `/game/stage/${stageId}/roster/overrides/${overrideId}`,
    method: 'delete'
  });
};
