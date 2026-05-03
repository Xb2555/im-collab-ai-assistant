// src/App.tsx
import { useEffect, useState } from 'react';
// ✨ 补全了 useNavigate 的导入
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/useAuthStore';
import { authApi } from '@/services/api/auth';

// ✨ 补全了移动端和桌面端底层插件的导入
import { Capacitor } from '@capacitor/core';
import { App as CapacitorApp } from '@capacitor/app';
import { onOpenUrl } from '@tauri-apps/plugin-deep-link';

import Login from '@/pages/Login';
import Callback from '@/pages/Callback';
import Dashboard from '@/pages/Dashboard';

/**
 * 全局路由守卫组件
 * 如果用户未登录，强制拦截并跳转到 /login
 */
function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const clearAuth = useAuthStore((state) => state.clearAuth);
  
  const [isVerifying, setIsVerifying] = useState(true);

  useEffect(() => {
    const verifyToken = async () => {
      if (isAuthenticated) {
        try {
          await authApi.getMe();
        } catch (error) {
          console.warn('应用初始化 Token 校验失败，自动清理状态');
          clearAuth(); 
        }
      }
      setIsVerifying(false);
    };

    verifyToken();
  }, [isAuthenticated, clearAuth]);

  if (isVerifying && isAuthenticated) {
    return (
      <div className="flex h-screen w-full items-center justify-center bg-zinc-50">
        <div className="flex flex-col items-center space-y-4">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-600 border-t-transparent"></div>
          <p className="text-sm text-zinc-500 animate-pulse">正在验证身份环境...</p>
        </div>
      </div>
    );
  }

  return isAuthenticated ? children : <Navigate to="/login" replace />;
}

// ✨ 全局 Deep Link 监听组件
function DeepLinkListener({ children }: { children: JSX.Element }) {
  const navigate = useNavigate();

  useEffect(() => {
    // 1. 桌面端 (Tauri) 监听
    if (typeof window !== 'undefined' && '__TAURI__' in window) {
      let unlisten: () => void;
      const setupDeepLink = async () => {
        unlisten = await onOpenUrl((urls) => {
          const urlStr = urls[0];
          if (urlStr && urlStr.includes('callback')) {
             const urlObj = new URL(urlStr, 'http://dummy.com'); // dummy base 帮助解析
             const code = urlObj.searchParams.get('code');
             const state = urlObj.searchParams.get('state');
             if (code) navigate(`/auth-callback?code=${code}&state=${state || ''}`);
          }
        });
      };
      setupDeepLink();
      return () => { if (unlisten) unlisten(); };
    }
    
    // 2. 移动端 (Capacitor) 监听
    if (Capacitor.isNativePlatform()) {
      const listener = CapacitorApp.addListener('appUrlOpen', (data) => {
        if (data.url.includes('callback')) {
          const urlObj = new URL(data.url);
          const code = urlObj.searchParams.get('code');
          const state = urlObj.searchParams.get('state');
          if (code) navigate(`/auth-callback?code=${code}&state=${state || ''}`);
        }
      });
      // ✨ 这里加了 (l: any) 修复了参数隐式 any 的报错
      return () => { listener.then((l: any) => l.remove()); };
    }
  }, [navigate]);

  return children;
}

export default function App() {
  return (
    <BrowserRouter>
      {/* ✨ 用监听器包裹 Routes，这样它就被使用起来了！ */}
      <DeepLinkListener>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/auth-callback" element={<Callback />} /> 
          <Route path="/" element={<RequireAuth><Dashboard /></RequireAuth>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </DeepLinkListener>
    </BrowserRouter>
  );
}