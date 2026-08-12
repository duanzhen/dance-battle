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
}

/**
 * 修改密码请求
 */
export interface ChangePasswordData {
  oldPassword?: string;
  newPassword?: string;
}
