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
