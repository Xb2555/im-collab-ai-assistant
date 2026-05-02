// src/services/api/client.ts
import axios from 'axios';
import { useAuthStore } from '@/store/useAuthStore';

export const apiClient = axios.create({
  baseURL: '', 
  timeout: 60000,
});

// 请求拦截器：自动注入 Token
apiClient.interceptors.request.use(
  (config) => {
    // 每次请求实时从 Zustand store 中获取最新 token
    const token = useAuthStore.getState().accessToken;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// 响应拦截器：全局处理 401 和 40900(多端版本冲突)
apiClient.interceptors.response.use(
  (response) => {
    // ✨ 新增逻辑：拦截基于业务状态码的并发冲突 (40900 乐观锁)
    const res = response.data;
    if (res && res.code === 40900) {
      console.warn('【乐观锁拦截】操作已在其他端完成，丢弃本次操作，等待 SSE 刷新');
      // 抛出特定的 Error，这样组件里的 try-catch 就能拦截，不会继续执行错误的成功逻辑
      return Promise.reject(new Error('VERSION_CONFLICT')); 
    }
    
    // 如果没有冲突，原样返回 response，让具体 API 去校验 code !== 0
    return response;
  },
  (error) => {
    if (error.response?.status === 401) {
      console.warn('登录已过期，自动清理状态');
      // Token 失效或未登录，清理本地状态
      useAuthStore.getState().clearAuth();
      // 如果不在登录页，可以强制重定向到登录页 (利用 window.location)
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);