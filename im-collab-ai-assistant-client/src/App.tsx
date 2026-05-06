// src/App.tsx
import { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/useAuthStore';
import { authApi } from '@/services/api/auth';

// ✨ 补全了移动端和桌面端底层插件的导入
import { Capacitor } from '@capacitor/core';
import { App as CapacitorApp } from '@capacitor/app';
import { onOpenUrl } from '@tauri-apps/plugin-deep-link';
import { listen } from '@tauri-apps/api/event';
import Login from '@/pages/Login';
import Callback from '@/pages/Callback';
import Dashboard from '@/pages/Dashboard';
// 在现有的 import 列表末尾增加这一行
import { Toaster } from "sonner";

// 在 src/App.tsx 顶部增加导入
import DashboardMobile from '@/pages/DashboardMobile';
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
        } catch {
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

function DeepLinkListener({ children }: { children: JSX.Element }) {
  const navigate = useNavigate();

  useEffect(() => {
    const parseAndNavigate = (urlStr: string, source: string) => {
      if (!urlStr || !urlStr.includes('callback')) {
        return;
      }
      try {
        const queryString = urlStr.includes('?') ? urlStr.split('?')[1] : '';
        const urlParams = new URLSearchParams(queryString);
        const code = urlParams.get('code');
        const state = urlParams.get('state');
        const error = urlParams.get('error');

        console.log(`[DeepLink:${source}] 收到 URL:`, urlStr);

        if (error) {
          console.error(`[DeepLink:${source}] 授权错误:`, error);
          navigate(`/auth-callback?error=${encodeURIComponent(error)}`, { replace: true });
          return;
        }

        if (code) {
          console.log(`[DeepLink:${source}] 解析到 code，准备跳转 /auth-callback`);
          navigate(`/auth-callback?code=${encodeURIComponent(code)}&state=${encodeURIComponent(state || '')}`, { replace: true });
          return;
        }

        console.warn(`[DeepLink:${source}] 未解析到 code`);
      } catch (e) {
        console.error(`[DeepLink:${source}] URL 解析失败:`, e);
      }
    };

    if (typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window) {
      let unlistenDeepLink: (() => void) | undefined;
      let unlistenSingleInstance: (() => void) | undefined;

      const setupDeepLink = async () => {
        console.log('[DeepLink] 初始化 Tauri deep link 监听');

        unlistenDeepLink = await onOpenUrl((urls) => {
          const urlStr = urls?.[0];
          if (urlStr) {
            parseAndNavigate(urlStr, 'onOpenUrl');
          }
        });

        unlistenSingleInstance = await listen<string>('oauth-deep-link', (event) => {
          const urlStr = event.payload;
          parseAndNavigate(urlStr, 'single-instance');
        });

        console.log('[DeepLink] 监听注册完成');
      };

      setupDeepLink().catch((err) => {
        console.error('[DeepLink] 监听初始化失败:', err);
      });

      return () => {
        unlistenDeepLink?.();
        unlistenSingleInstance?.();
      };
    }

    if (Capacitor.isNativePlatform()) {
      const listener = CapacitorApp.addListener('appUrlOpen', (data) => {
        parseAndNavigate(data.url, 'capacitor-appUrlOpen');
      });
      return () => {
        listener.then((l) => l.remove());
      };
    }
  }, [navigate]);

  return children;
}

export default function App() {
  // 简易判断是否为移动端尺寸
  const [isMobile, setIsMobile] = useState(window.innerWidth < 768);

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  return (
    <BrowserRouter>
      <DeepLinkListener>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/auth-callback" element={<Callback />} /> 
          <Route path="/" element={
            <RequireAuth>
               {isMobile ? <DashboardMobile /> : <Dashboard />}
            </RequireAuth>
          } />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </DeepLinkListener>
      <Toaster position="top-center" richColors />
    </BrowserRouter>
  );
}