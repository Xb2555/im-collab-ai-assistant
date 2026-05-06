// src/services/api/client.ts
import axios from 'axios';
import { useAuthStore } from '@/store/useAuthStore';
import { Capacitor } from '@capacitor/core'; // ✨ 引入 Capacitor

// ✨ 加上 (window as any) 强制绕过 TypeScript 检查
// ✨ 将其导出，方便其他非 axios 的底层 fetch 也能复用这个逻辑
export const isTauri = typeof window !== 'undefined' && (window as any).__TAURI_INTERNALS__ !== undefined;
// ✨ 新增：判断是否为 Capacitor 移动端原生环境 (iOS/Android App)
export const isMobileNative = Capacitor.isNativePlatform();

export const getBaseUrl = () => {
  // 比赛阶段：全平台统一直连后端，避免依赖 Vite/Vercel 代理配置
  return 'https://api.yiiie.cn';
};

export const apiClient = axios.create({
  baseURL: getBaseUrl(), 
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
    const requestUrl = `${error?.config?.baseURL ?? ''}${error?.config?.url ?? ''}`;
    console.error('[API_ERROR]', {
      message: error?.message,
      code: error?.code,
      status: error?.response?.status,
      requestUrl,
      method: error?.config?.method,
    });

    if (error.response?.status === 401) {
      useAuthStore.getState().clearAuth();
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);