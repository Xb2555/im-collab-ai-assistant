// src/services/api/client.ts
import axios from 'axios';
import { useAuthStore } from '@/store/useAuthStore';

// ✨ 加上 (window as any) 强制绕过 TypeScript 检查
const isTauri = typeof window !== 'undefined' && (window as any).__TAURI_INTERNALS__ !== undefined;

const getBaseUrl = () => {
  // 1. 如果是桌面端 (Tauri)，强制写死后端 Spring Boot 的绝对地址！
  if (isTauri) {
    return 'http://81.71.143.236:18080'; 
  }
  // 2. 如果是网页端开发，保持为空，走 Vite 的 Proxy 代理
  return '';
};

export const apiClient = axios.create({
  baseURL: getBaseUrl(), // ✨ 使用动态判断的地址
  timeout: 60000, 
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

// 响应拦截器：全局处理 40100 和 40900
apiClient.interceptors.response.use(
  (response) => {
    const res = response.data;
    if (res && res.code === 40100) {
      console.warn('登录已过期，自动清理状态');
      useAuthStore.getState().clearAuth();
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
      return Promise.reject(new Error('UNAUTHORIZED'));
    }
    if (res && res.code === 40900) {
      console.warn('【乐观锁拦截】操作已在其他端完成');
      const conflictError = new Error('VERSION_CONFLICT');
      (conflictError as any).businessMessage = res.message; 
      return Promise.reject(conflictError); 
    }
    return response;
  },
  (error) => {
    if (error.response?.status === 401) {
      useAuthStore.getState().clearAuth();
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);