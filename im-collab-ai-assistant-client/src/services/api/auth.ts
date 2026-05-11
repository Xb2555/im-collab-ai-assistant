// src/services/api/auth.ts
import { apiClient } from './client';
import type { ApiResponse, AuthCallbackRequest, AuthCallbackResponse, User } from '@/types/api';

export interface LarkLoginUrlData {
  authorizationUri: string;
  state: string;
}

export type LarkLoginUrlResponse = ApiResponse<LarkLoginUrlData>;
export type OAuthClientType = 'web' | 'desktop';

export const authApi = {
  /**
   * 获取飞书授权 URL（JSON，不重定向）
   */
 
  getLoginUrl: async (client: OAuthClientType = 'web'): Promise<LarkLoginUrlData> => {
    const response = await apiClient.get<LarkLoginUrlResponse>('/auth/login-url', {
      params: { client },
    });

    if (response.data.code !== 0) {
      throw new Error(response.data.message || '获取登录授权链接失败');
    }
    localStorage.setItem('lark_oauth_state', response.data.data.state);
    return response.data.data;
  },

  /**
   * 完成登录 (Code 换 Token) 
   */
  callback: async (data: AuthCallbackRequest): Promise<AuthCallbackResponse> => {
    const response = await apiClient.post<ApiResponse<AuthCallbackResponse>>('/auth/callback', data);
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '登录授权失败');
    }
    return response.data.data;
  },

  /**
   * 获取当前登录用户
   */
  getMe: async (): Promise<User> => {
    const response = await apiClient.get<ApiResponse<User>>('/auth/me');
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '获取用户信息失败');
    }
    return response.data.data;
  },

  /**
   * 退出登录 
   */
  logout: async (): Promise<boolean> => {
    const response = await apiClient.post<ApiResponse<boolean>>('/auth/logout');
    return response.data.code === 0;
  }
};