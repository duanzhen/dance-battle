/**
 * 登录请求
 */
export interface LoginData {
  username?: string;
  password?: string;
  rememberMe?: boolean;
}

/**
 * 登录响应
 */
export interface LoginResult {
  token: string;
  username?: string;
  /** 是否仍在使用默认密码(为 true 时前端强制弹窗要求修改) */
  defaultPassword?: boolean;
}

/**
 * 修改密码请求
 */
export interface ChangePasswordData {
  oldPassword?: string;
  newPassword?: string;
}
