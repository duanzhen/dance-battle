/**
 * 赛段配置类型定义
 */

// 赛段配置模式
export enum ConfigMode {
  NORMAL = 'normal', // 普通模式: 所有配置项可编辑
  INIT = 'init', // 初始化模式: 包含 init-only 字段
  INIT_DONE = 'init-done' // 初始化完成模式: init-only 字段只读
}

// 赛段类型枚举
export enum StageMode {
  KNOCKOUT = 'KNOCKOUT', // 淘汰赛
  GROUP = 'GROUP', // 小组赛
  FFA = 'FFA', // 自由对抗赛
  AUDITION = 'AUDITION', // 选拔赛
  SURVIVAL = 'SURVIVAL', // 海选赛
  ARENA = 'ARENA' // 擂台赛
}

// 淘汰赛模板类型
export enum KnockoutTemplate {
  FINAL = 'FINAL', // 决赛
  SEMI_FINAL = 'SEMI_FINAL', // 半决赛 (4进2)
  QUARTER_FINAL = 'QUARTER_FINAL', // 1/4决赛 (8进4)
  ROUND_16 = 'ROUND_16', // 16进8
  ROUND_32 = 'ROUND_32', // 32进16
  ROUND_64 = 'ROUND_64', // 64进32
  CUSTOM = 'CUSTOM' // 自定义
}

// 比赛格式
export type MatchFormat = 'BO1' | 'BO3' | 'BO5';

// 淘汰赛配置
export interface KnockoutConfig {
  template: KnockoutTemplate;
  format: MatchFormat;
  teamsCount: number;
  advanceCount: number;
}

// 小组赛配置
export interface GroupConfig {
  groupCount: number; // 分组数
  teamsPerGroup: number; // 每组队伍数
  winPoints: number; // 胜积分
  drawPoints: number; // 平积分
  lossPoints: number; // 负积分
  advancePerGroup: number; // 每组晋级数
  format: MatchFormat;
}

// 自由对抗赛配置
export interface FFAConfig {
  teamsCount: number; // 参赛队伍总数
  matchCount: number; // 每队比赛场次
  winPoints: number; // 胜积分
  lossPoints: number; // 负积分
  advanceCount: number; // 晋级名额
  format: MatchFormat;
}

// 选拔赛配置
export interface AuditionConfig {
  scale: number; // 海选规模
  advanceCondition: string; // 晋级条件
  advanceCount: number; // 晋级名额
  format: MatchFormat;
}

// 海选赛配置
export interface SurvivalConfig {
  roundsCount: number; // 海选轮数
  eliminationRate: number; // 每轮淘汰率 (0-1)
  advanceThreshold: number; // 晋线分数
  advanceByScore: boolean; // 是否按分数晋级
  advanceQuota: number; // 晋级名额
  format: MatchFormat;
}

// 擂台赛配置
export enum ChallengeOrder {
  RANDOM = 'RANDOM', // 随机
  RANKED = 'RANKED', // 按排名
  MANUAL = 'MANUAL' // 手动
}

export interface ArenaConfig {
  defenderTeamId: string; // 守擂方ID
  challengerCount: number; // 攻擂队伍数
  winStreakBonus: number; // 连胜奖励积分
  challengeOrder: ChallengeOrder; // 挑战顺序
  maxChallenges: number; // 最大挑战场次
  format: MatchFormat;
}

// 联合类型
export type StageConfig = KnockoutConfig | GroupConfig | FFAConfig | AuditionConfig | SurvivalConfig | ArenaConfig;

// 赛段数据接口
export interface StageData {
  id: number | string;
  name: string;
  stageMode: StageMode;
  ruleConfig: string; // JSON 字符串
  teamCountStart: number;
  teamCountEnd: number;
  status: 'DRAFT' | 'PENDING' | 'GAMING' | 'SETTLED' | 'DISCARD';
  isInitialized?: boolean; // 是否已完成初始化配置
  tournamentId?: string | number; // 赛事ID
}

// 赛段类型选择器事件
export interface StageTypeSelectEvent {
  stageMode: StageMode;
  ruleConfig: StageConfig;
  defaultName: string;
}
