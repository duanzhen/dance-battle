/**
 * 赛事级展示配置(所有下属淘汰赛共享,存于 t_tournament.themeConfig JSON)。
 *
 * matchLayoutOrder: 「当前场次」朝向(纯展示,不改 match 生成、不影响对战树)
 *   UPPER_LEFT = 上左下右(默认:上家/1 号位在左) / UPPER_RIGHT = 上右下左(左右互换)
 * matchColorOrder: 当前场次组件与裁判列表中左右两名选手颜色
 *   RED_LEFT = 左红右蓝(默认) / BLUE_LEFT = 右红左蓝
 * autoConfirmAdvancement: 跳过中间态确认阶段(默认开启)
 *   开启后,MC 导播台在开始赛段时弹窗确认,自动执行「确认晋级」后直接开始,无需在中间态手动确认
 *
 * 两者配合:朝向决定两名选手显示在屏幕哪一侧,红蓝再按「显示后的左右」上色。
 * 例:UPPER_RIGHT + RED_LEFT = 两名选手左右互换,但屏幕左侧仍然是红色。
 */
export type MatchLayoutOrder = 'UPPER_LEFT' | 'UPPER_RIGHT';

export interface TournamentColorConfig {
  matchLayoutOrder: MatchLayoutOrder;
  matchColorOrder: 'RED_LEFT' | 'BLUE_LEFT';
}

export interface TournamentThemeConfig extends TournamentColorConfig {
  autoConfirmAdvancement: boolean;
}

export const DEFAULT_TOURNAMENT_COLOR_CONFIG: TournamentColorConfig = {
  matchLayoutOrder: 'UPPER_LEFT',
  matchColorOrder: 'RED_LEFT'
};

export const DEFAULT_TOURNAMENT_THEME_CONFIG: TournamentThemeConfig = {
  ...DEFAULT_TOURNAMENT_COLOR_CONFIG,
  autoConfirmAdvancement: true
};

export const parseTournamentColorConfig = (themeConfig?: string | null): TournamentColorConfig => {
  if (!themeConfig) {
    return { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
  }
  try {
    const t = JSON.parse(themeConfig);
    // 兼容历史字段(bracketColorOrder / bracketLayoutOrder):RED_TOP=默认朝向、BLUE_TOP=镜像
    const legacy = t?.bracketColorOrder === 'BLUE_TOP' ? 'UPPER_RIGHT'
      : t?.bracketColorOrder === 'RED_TOP' ? 'UPPER_LEFT' : undefined;
    const layout = t?.matchLayoutOrder ?? t?.bracketLayoutOrder ?? legacy;
    return {
      matchLayoutOrder: layout === 'UPPER_RIGHT' ? 'UPPER_RIGHT' : 'UPPER_LEFT',
      matchColorOrder: t?.matchColorOrder === 'BLUE_LEFT' ? 'BLUE_LEFT' : 'RED_LEFT'
    };
  } catch {
    return { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
  }
};

/** 「当前场次」展示是否需要左右互换(上右下左=是;默认上左下右=否) */
export const isMatchMirrored = (colorConfig?: TournamentColorConfig | null): boolean =>
  colorConfig?.matchLayoutOrder === 'UPPER_RIGHT';

export const parseTournamentThemeConfig = (themeConfig?: string | null): TournamentThemeConfig => {
  const colors = parseTournamentColorConfig(themeConfig);
  if (!themeConfig) {
    return { ...colors, autoConfirmAdvancement: true };
  }
  try {
    const t = JSON.parse(themeConfig);
    return {
      ...colors,
      autoConfirmAdvancement: t?.autoConfirmAdvancement !== false
    };
  } catch {
    return { ...colors, autoConfirmAdvancement: true };
  }
};
