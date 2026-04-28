// src/services/api/auth.ts
import { apiClient } from './client';
import type { ApiResponse, AuthCallbackRequest, AuthCallbackResponse, User } from '@/types/api';

export const authApi = {
  /**
   * 跳转飞书授权页
   * 前端可以直接让浏览器打开该地址，后端会重定向到飞书授权页
   */
  getLoginUrl: () => {
    return '/api/auth/lark/login';
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