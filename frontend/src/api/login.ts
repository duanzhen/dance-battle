import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { LoginData, LoginResult } from './types';

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
