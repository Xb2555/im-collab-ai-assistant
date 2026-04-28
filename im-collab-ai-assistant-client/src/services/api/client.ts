// src/services/api/client.ts
import axios from 'axios';
import { useAuthStore } from '@/store/useAuthStore';

export const apiClient = axios.create({
  baseURL: '', 
  timeout: 10000,
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

// 响应拦截器：全局处理 401 等常见错误
apiClient.interceptors.response.use(
  (response) => response,
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