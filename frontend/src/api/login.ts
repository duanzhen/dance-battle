import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { ChangePasswordData, LoginData, LoginResult } from './types';

/**
 * 登录
 */
export function login(data: LoginData): AxiosPromise<LoginResult> {
  return request({
    url: '/login',
    headers: {
      isToken: false
    },
    method: 'post',
    data
  });
}

/**
 * 注销
 */
export function logout(): AxiosPromise<any> {
  return request({
    url: '/logout',
    method: 'post'
  });
}

/**
 * 修改密码
 */
export function changePassword(data: ChangePasswordData): AxiosPromise<any> {
  return request({
    url: '/changePassword',
    method: 'post',
    data
  });
}

/**
 * 查询当前账号是否仍在使用默认密码(登录后刷新页面时用于恢复强制改密弹窗)
 */
export function getPasswordStatus(): AxiosPromise<{ defaultPassword: boolean }> {
  return request({
    url: '/login/passwordStatus',
    method: 'get'
  });
}
