/**
 * 大屏(投射页)公开数据接口。
 *
 * <p>对应后端 {@code /tournament/screen/**}(ScreenController,全为 {@code @SaIgnore} 只读接口)。
 * 大屏是公开播放端,不携带管理员 JWT 也不携带任何凭证,因此这里用独立 axios 实例:
 * 不加鉴权头,也不挂管理端「登录状态已过期」拦截器——大屏永远不会被登录弹窗挡住。</p>
 *
 * <p>函数名与参数刻意与管理端同名接口保持一致,投射页各控件只改 import 来源,不改调用逻辑。</p>
 */
import axios from 'axios';
import type { AxiosPromise } from 'axios';
import { attachEnvelope } from '@/utils/apiEnvelope';
import type { MatchQuery, MatchVO } from '@/api/game/match/types';
import type { MatchParticipantQuery, MatchParticipantVO } from '@/api/game/matchParticipant/types';
import type { CompetitorQuery, CompetitorVO } from '@/api/game/competitor/types';
import type { PlayerQuery, PlayerVO } from '@/api/game/player/types';
import type { RefereeQuery, RefereeVO } from '@/api/game/referee/types';
import type { StageVO } from '@/api/game/stage/types';
import type { TournamentVO } from '@/api/game/tournament/types';
import type { VisSceneVO } from '@/api/game/visScene/types';
import type { VisWidgetQuery, VisWidgetVO } from '@/api/game/visWidget/types';

const screenRequest = attachEnvelope(
  axios.create({
    baseURL: import.meta.env.VITE_APP_BASE_API,
    timeout: 30000,
    headers: {
      'Content-Type': 'application/json;charset=utf-8',
      clientid: import.meta.env.VITE_APP_CLIENT_ID
    }
  }) as any
);

// ==================== 场景 / 控件 ====================

/** 场景详情 */
export const getVisScene = (id: string | number): AxiosPromise<VisSceneVO> => {
  return screenRequest({
    url: '/tournament/screen/scene/' + id,
    method: 'get'
  });
};

/** 场景控件列表 */
export const listVisWidget = (query?: VisWidgetQuery): AxiosPromise<VisWidgetVO[]> => {
  return screenRequest({
    url: '/tournament/screen/widget/list',
    method: 'get',
    params: query
  });
};

// ==================== 赛事 / 赛段 ====================

/** 赛事信息(名称、主题配色等) */
export const getTournament = (id: string | number): AxiosPromise<TournamentVO> => {
  return screenRequest({
    url: '/tournament/screen/tournament/' + id,
    method: 'get'
  });
};

/** 赛段详情 */
export const getStage = (id: string | number): AxiosPromise<StageVO> => {
  return screenRequest({
    url: '/tournament/screen/stage/' + id,
    method: 'get'
  });
};

/** 赛前对阵 */
export const getStagePreBracket = (stageId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/stage/' + stageId + '/prebracket',
    method: 'get'
  });
};

/** 排名赛明细 */
export const getStageRankDetail = (stageId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/stage/' + stageId + '/rank-detail',
    method: 'get'
  });
};

/** 海选结果 */
export const getAuditionResult = (stageId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/stage/' + stageId + '/audition-result',
    method: 'get'
  });
};

/** 擂台赛总览 */
export const getArenaOverview = (stageId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/stage/' + stageId + '/arena-overview',
    method: 'get'
  });
};

/** 全赛事赛段流程 */
export const getStageFlow = (tournamentId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/flow/' + tournamentId,
    method: 'get'
  });
};

// ==================== 场次 / 参赛方 ====================

/** 场次列表 */
export const listMatch = (query?: MatchQuery): AxiosPromise<MatchVO[]> => {
  return screenRequest({
    url: '/tournament/screen/match/list',
    method: 'get',
    params: query
  });
};

/** 场次详情 */
export const getMatch = (id: string | number): AxiosPromise<MatchVO> => {
  return screenRequest({
    url: '/tournament/screen/match/' + id,
    method: 'get'
  });
};

/** 场次当前上场选手 */
export const getMatchCurrentCompetitor = (id: string | number) => {
  return screenRequest({
    url: '/tournament/screen/match/' + id + '/current-competitor',
    method: 'get'
  });
};

/** 场次参赛明细列表 */
export const listMatchParticipant = (query?: MatchParticipantQuery): AxiosPromise<MatchParticipantVO[]> => {
  return screenRequest({
    url: '/tournament/screen/matchParticipant/list',
    method: 'get',
    params: query
  });
};

/** 按赛段取出各场次参赛明细(按 matchId 分组) */
export const listParticipantsByStage = async (
  stageId: string | number
): Promise<Record<string, MatchParticipantVO[]>> => {
  const grouped: Record<string, MatchParticipantVO[]> = {};
  const res: any = await listMatchParticipant({ stageId, pageNum: 1, pageSize: 999 } as MatchParticipantQuery);
  const rows: MatchParticipantVO[] = res?.data?.data || res?.data || [];
  rows.forEach((p: MatchParticipantVO) => {
    if (p?.matchId == null) return;
    const key = String(p.matchId);
    (grouped[key] ||= []).push(p);
  });
  return grouped;
};

/** 参赛单位列表 */
export const listCompetitor = (query?: CompetitorQuery): AxiosPromise<CompetitorVO[]> => {
  return screenRequest({
    url: '/tournament/screen/competitor/list',
    method: 'get',
    params: query
  });
};

/** 选手自然人列表 */
export const listPlayer = (query?: PlayerQuery): AxiosPromise<PlayerVO[]> => {
  return screenRequest({
    url: '/tournament/screen/player/list',
    method: 'get',
    params: query
  });
};

// ==================== 裁判展示 ====================

/** 裁判列表 */
export const listReferee = (query?: RefereeQuery): AxiosPromise<RefereeVO[]> => {
  return screenRequest({
    url: '/tournament/screen/referee/list',
    method: 'get',
    params: query
  });
};

/** 某赛段各场次(圈)已分配的裁判 */
export const listMatchReferee = (stageId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/match-referee/list',
    method: 'get',
    params: { stageId }
  });
};

/** 某赛段已分配的裁判ID */
export const getStageRefereeIds = (stageId: string | number) => {
  return screenRequest({
    url: '/tournament/screen/referee-stage/referee-ids',
    method: 'get',
    params: { stageId }
  });
};
