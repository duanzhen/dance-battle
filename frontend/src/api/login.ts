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
