import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { StageVO, StageForm, StageConfigForm, StageQuery } from '@/api/game/stage/types';

/**
 * 剔除 ruleConfig 中已废弃的 transition 后再提交。
 *
 * <p>后端已把「下一赛段」的唯一事实源收敛为赛段链 next(见
 * {@code TStageLifecycleServiceImpl#resolveNextStageId}):{@code transition.targetStageId}
 * 是历史遗留项、后端已无写入方,且一旦与链 next 不一致会直接报错「下一赛段有两个答案」。</p>
 *
 * <p>各赛段配置组件会保留解析到的整个 ruleConfig(显式保留或整体展开),若不在此统一剥离,
 * 历史脏值会被反复回写,而界面上没有任何入口可以清除它——最终表现为「完成赛段」报错且无法修复。
 * 所有赛段写的入口(add/update/config)都过这里,新增赛制也自动覆盖。</p>
 */
const stripLegacyTransition = (ruleConfig?: string): string | undefined => {
  if (!ruleConfig) {
    return ruleConfig;
  }
  try {
    const parsed = JSON.parse(ruleConfig);
    if (parsed && typeof parsed === 'object' && 'transition' in parsed) {
      delete (parsed as Record<string, unknown>).transition;
      return JSON.stringify(parsed);
    }
  } catch {
    // ruleConfig 非法时不阻断提交(解析错误交由后端给出),原样透传
  }
  return ruleConfig;
};

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
    data: { ...data, ruleConfig: stripLegacyTransition(data.ruleConfig) }
  });
};

/**
 * 修改赛段流程(兼容入口)
 *
 * 后端已把它收敛成「只改配置」:请求里带的 prevStageId / nextStageId 一律被忽略,
 * 不会再改赛段链。新代码请用 updateStageConfig(配置) / moveStageAfter(改链)。
 * @param data
 */
export const updateStage = (data: StageForm) => {
  return request({
    url: '/game/stage',
    method: 'put',
    data: { ...data, ruleConfig: stripLegacyTransition(data.ruleConfig) }
  });
};

/**
 * 修改赛段配置(配置面板唯一入口)
 * @param id 赛段ID
 * @param data 配置字段(不含 prevStageId / nextStageId)
 */
export const updateStageConfig = (id: string | number, data: StageConfigForm) => {
  return request({
    url: '/game/stage/' + id + '/config',
    method: 'put',
    data: { ...data, ruleConfig: stripLegacyTransition(data.ruleConfig) }
  });
};

/**
 * 调整赛段链顺序:把该赛段移动到 afterStageId 之后(不传 = 移到链头)
 *
 * 传意图而不是前后指针:顺序由后端按现有链推导后统一写入。
 */
export const moveStageAfter = (id: string | number, afterStageId: string | number | null) => {
  return request({
    url: '/game/stage/' + id + '/link',
    method: 'put',
    data: { afterStageId }
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
 * 海选赛段结果(统一口径):原始海选成绩 + 二海/三海…加赛明细。
 * 二海分数只用于同分者决出晋级顺序,不计入原始总分;各组件统一消费本结果。
 */
export const getAuditionResult = (stageId: string | number) => {
  return request({
    url: '/game/stage/' + stageId + '/audition-result',
    method: 'get'
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

/**
 * 导出海选结果 Excel:号码 / 选手名 / 各裁判分数(每裁判一列) / 总平均分 / 排名
 * @param stageId 海选赛赛段ID
 */
export const exportAuditionResult = (stageId: string | number) => {
  return request({
    url: '/game/stage/' + stageId + '/export-audition-result',
    method: 'get',
    responseType: 'blob'
  });
};
