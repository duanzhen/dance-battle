/**
 * 赛事级红蓝配色配置(所有下属淘汰赛共享,存于 t_tournament.themeConfig JSON)。
 *
 * bracketColorOrder: 对战树上每场的上下两名选手颜色
 *   RED_TOP = 上红下蓝(默认) / BLUE_TOP = 上蓝下红
 * matchColorOrder: 当前场次组件与裁判列表中左右两名选手颜色
 *   RED_LEFT = 左红右蓝(默认) / BLUE_LEFT = 右红左蓝
 * autoConfirmAdvancement: 跳过中间态确认阶段(默认关闭)
 *   开启后,上一个赛段完成时后端自动执行「确认晋级」,无需在中间态手动确认
 */
export interface TournamentColorConfig {
  bracketColorOrder: 'RED_TOP' | 'BLUE_TOP';
  matchColorOrder: 'RED_LEFT' | 'BLUE_LEFT';
}

export interface TournamentThemeConfig extends TournamentColorConfig {
  autoConfirmAdvancement: boolean;
}

export const DEFAULT_TOURNAMENT_COLOR_CONFIG: TournamentColorConfig = {
  bracketColorOrder: 'RED_TOP',
  matchColorOrder: 'RED_LEFT'
};

export const DEFAULT_TOURNAMENT_THEME_CONFIG: TournamentThemeConfig = {
  ...DEFAULT_TOURNAMENT_COLOR_CONFIG,
  autoConfirmAdvancement: false
};

export const parseTournamentColorConfig = (themeConfig?: string | null): TournamentColorConfig => {
  if (!themeConfig) {
    return { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
  }
  try {
    const t = JSON.parse(themeConfig);
    return {
      bracketColorOrder: t?.bracketColorOrder === 'BLUE_TOP' ? 'BLUE_TOP' : 'RED_TOP',
      matchColorOrder: t?.matchColorOrder === 'BLUE_LEFT' ? 'BLUE_LEFT' : 'RED_LEFT'
    };
  } catch {
    return { ...DEFAULT_TOURNAMENT_COLOR_CONFIG };
  }
};

export const parseTournamentThemeConfig = (themeConfig?: string | null): TournamentThemeConfig => {
  const colors = parseTournamentColorConfig(themeConfig);
  if (!themeConfig) {
    return { ...colors, autoConfirmAdvancement: false };
  }
  try {
    const t = JSON.parse(themeConfig);
    return {
      ...colors,
      autoConfirmAdvancement: t?.autoConfirmAdvancement === true
    };
  } catch {
    return { ...colors, autoConfirmAdvancement: false };
  }
};
