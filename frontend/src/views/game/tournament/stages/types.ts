/**
 * 赛段配置类型定义
 */

// 赛段配置模式
export enum ConfigMode {
  /** 创建模式: 新建赛段时,创建配置 + 初始配置均可编辑 */
  CREATE = 'create',
  /** 初始配置模式: 赛段已创建、未开始,初始配置可编辑,创建配置锁定 */
  INIT = 'init',
  /** 开始后模式: 创建配置与初始配置均只读,仅开始后参数可编辑 */
  STARTED = 'started'
}

// 赛段类型枚举
export enum StageMode {
  KNOCKOUT = 'KNOCKOUT', // 淘汰赛
  GROUP = 'GROUP', // 小组赛
  AUDITION = 'AUDITION', // 海选赛
  ARENA = 'ARENA', // 擂台赛
  RANK = 'RANK' // 排名赛
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
  teamsPerGroup: number; // 每组选手数
  winPoints: number; // 胜积分
  drawPoints: number; // 平积分
  lossPoints: number; // 负积分
  advancePerGroup: number; // 每组晋级数
  format: MatchFormat;
}

// 海选赛配置
export interface AuditionConfig {
  /** 海选规模(历史遗留字段;海选不限制起始人数,已不再使用) */
  scale?: number;
  advanceCondition: string; // 晋级条件
  advanceCount: number; // 晋级名额
  /** 海选为打分制,无比赛格式(BO1/BO3) */
  format?: MatchFormat;
  circles?: number; // 分圈数(1=不分圈)
  /** 每圈晋级人数(按圈顺序 ZONE-1..n,各圈可不相同;未配置按 advanceCount 均分) */
  circleAdvanceCounts?: number[];
  /** 每圈绑定的裁判ID列表(按圈顺序 ZONE-1..n,每圈可多个;未配置时生成对阵自动按 圈数=裁判数 1:1 或全部绑每圈) */
  circleRefereeIds?: (string | number)[][];
}

// 排名赛评分维度(可自定义,不写死)
export interface RankDimension {
  key: string; // 维度标识,如 TECH/CREATE/SHOW
  name: string; // 维度名称
  weight: number; // 权重(维度间按 WEIGHTED 合成时使用)
  maxScore: number; // 该维度满分
}

// 排名赛配置:逐选手轮次,多名裁判按自定义维度打分,总分排名后按名额晋级
export interface RankingConfig {
  scale: number; // 参赛人数
  advanceCount: number; // 晋级名额
  // 结果公布模式:AUTO=实时公布 / MANUAL=导播台手动公布 / BATCH=全部完成后一次性公布
  publishMode: 'AUTO' | 'MANUAL' | 'BATCH';
  // 公布范围:BATCH 使用,ALL=公布全部排名 / TOP_N=只公布前 N 名晋级名单
  publishScope: 'ALL' | 'TOP_N';
  scoring: {
    type: 'MULTI_DIM';
    matchMode: 'RANKING';
    refereeAggregateRule: 'SUM' | 'AVG' | 'TRIMMED_MEAN';
    aggregateRule: 'SUM' | 'AVG' | 'WEIGHTED' | 'TRIMMED_MEAN';
    trimRatio: number;
    dimensions: RankDimension[];
  };
}

export interface ArenaConfig {
  scale: number; // 进入擂台赛总人数(擂主1人 + 攻擂N人)
  format: MatchFormat;
  /** 平局双方各加1分(双方下场时;默认关闭,只有胜场记1分) */
  drawBothScore?: boolean;
}

// 联合类型
export type StageConfig = KnockoutConfig | GroupConfig | AuditionConfig | ArenaConfig | RankingConfig;

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
  prevStageId?: string | number | null;
  nextStageId?: string | number | null;
}

// 赛段类型选择器事件
export interface StageTypeSelectEvent {
  stageMode: StageMode;
  ruleConfig: StageConfig;
  defaultName: string;
}
