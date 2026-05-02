// src/services/api/client.ts
import axios from 'axios';
import { useAuthStore } from '@/store/useAuthStore';

export const apiClient = axios.create({
  baseURL: '', 
  timeout: 60000, // 保持你的 60s，很适合 AI 场景
});

// 请求拦截器：自动注入 Token
apiClient.interceptors.request.use(
  (config) => {
    const token = useAuthStore.getState().accessToken;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// 响应拦截器：全局处理 40100(未登录) 和 40900(多端版本冲突)
apiClient.interceptors.response.use(
  (response) => {
    const res = response.data;
    
    // 1. 拦截未登录 (适配后端最新的 40100 业务码)
    if (res && res.code === 40100) {
      console.warn('登录已过期，自动清理状态');
      useAuthStore.getState().clearAuth();
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
      return Promise.reject(new Error('UNAUTHORIZED'));
    }

    // 2. 拦截多端冲突 (40900 乐观锁)
    if (res && res.code === 40900) {
      console.warn('【乐观锁拦截】操作已在其他端完成，丢弃本次操作，等待 SSE 刷新');
      const conflictError = new Error('VERSION_CONFLICT');
      (conflictError as any).businessMessage = res.message; // 附带一下后端的原话
      return Promise.reject(conflictError); 
    }
    
    return response;
  },
  (error) => {
    // 兜底：如果后端真的发了真实的 HTTP 401，这里也拦截一下，双重保险
    if (error.response?.status === 401) {
      useAuthStore.getState().clearAuth();
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);