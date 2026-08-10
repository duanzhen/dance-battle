import { to } from 'await-to-js';
import { getToken, removeToken, setToken } from '@/utils/auth';
import { login as loginApi, logout as logoutApi } from '@/api/login';
import { LoginData } from '@/api/types';
import defAva from '@/assets/images/profile.jpg';
import { defineStore } from 'pinia';
import { ref } from 'vue';

export const useUserStore = defineStore('user', () => {
  const token = ref(getToken());
  const name = ref('');
  const nickname = ref('');
  const userId = ref<string | number>('');
  const tenantId = ref<string>('');
  const avatar = ref('');
  const roles = ref<Array<string>>([]); // 用户角色编码集合 → 判断路由权限
  const permissions = ref<Array<string>>([]); // 用户权限编码集合 → 判断按钮权限

  /**
   * 登录
   * @param userInfo
   * @returns
   */
  const login = async (userInfo: LoginData): Promise<void> => {
    const [err, res] = await to(loginApi(userInfo));
    if (res) {
      // 响应拦截器已返回 R 响应体,res.data 即 {token, username} 载荷
      const data = res.data;
      setToken(data.token);
      token.value = data.token;
      name.value = data.username || '';
      return Promise.resolve();
    }
    return Promise.reject(err);
  };

  // 获取用户信息(单账号体系:本地默认角色/权限,不请求后端)
  const getInfo = async (): Promise<void> => {
    roles.value = ['admin'];
    permissions.value = ['*'];
    userId.value = 1;
    tenantId.value = '0';
    if (!avatar.value) {
      avatar.value = defAva;
    }
    return Promise.resolve();
  };

  // 注销
  const logout = async (): Promise<void> => {
    try {
      await logoutApi();
    } catch (e) {
      // 后端注销失败也继续清理本地状态
    }
    token.value = '';
    roles.value = [];
    permissions.value = [];
    removeToken();
  };

  const setAvatar = (value: string) => {
    avatar.value = value;
  };

  return {
    userId,
    tenantId,
    token,
    nickname,
    avatar,
    roles,
    permissions,
    login,
    getInfo,
    logout,
    setAvatar
  };
});
