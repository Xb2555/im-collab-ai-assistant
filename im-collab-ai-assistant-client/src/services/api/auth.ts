// src/services/api/auth.ts
import { apiClient } from './client';
import type { ApiResponse, AuthCallbackRequest, AuthCallbackResponse, User } from '@/types/api';

export interface LarkLoginUrlData {
  authorizationUri: string;
  state: string;
}

export type LarkLoginUrlResponse = ApiResponse<LarkLoginUrlData>;

export const authApi = {
  /**
   * Web 端直跳后端登录入口（后端 302 到飞书授权页）
   */
  getLoginUrlRedirectPath: (): string => {
    return '/api/auth/lark/login';
  },

  /**
   * 获取飞书授权 URL（JSON，不重定向）
   */
  getLoginUrl: async (): Promise<LarkLoginUrlData> => {
    // 判断当前是不是在 Tauri 桌面端环境中
    const isTauri = typeof window !== 'undefined' && '__TAURI__' in window;
    const clientType = isTauri ? 'desktop' : 'web'; 
    
    // 把身份告诉后端
    const response = await apiClient.get<LarkLoginUrlResponse>(`/api/auth/login-url?client=${clientType}`);
    
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
    const response = await apiClient.post<ApiResponse<AuthCallbackResponse>>('/api/auth/callback', data);
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '登录授权失败');
    }
    return response.data.data;
  },

  /**
   * 获取当前登录用户
   */
  getMe: async (): Promise<User> => {
    const response = await apiClient.get<ApiResponse<User>>('/api/auth/me');
    if (response.data.code !== 0) {
      throw new Error(response.data.message || '获取用户信息失败');
    }
    return response.data.data;
  },

  /**
   * 退出登录 
   */
  logout: async (): Promise<boolean> => {
    const response = await apiClient.post<ApiResponse<boolean>>('/api/auth/logout');
    return response.data.code === 0;
  }
};