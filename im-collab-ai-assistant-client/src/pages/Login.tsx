// src/pages/Login.tsx
import { useEffect, useRef, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import { Button } from '@/components/ui/button';
import { authLauncher } from '@/services/os/launcher/auth';
import { useAuthStore } from '@/store/useAuthStore';
import { Navigate } from 'react-router-dom';
import { Terminal, LayoutDashboard, Zap } from 'lucide-react';
import { browserLauncher } from '@/services/os/launcher/browser';
export default function Login() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [loading, setLoading] = useState(false);
  const [authStep, setAuthStep] = useState<'idle' | 'opening-browser' | 'waiting-return'>('idle');
  const [lastOAuthUrl, setLastOAuthUrl] = useState<string | null>(null);
  const [showFallback, setShowFallback] = useState(false);
  const fallbackTimerRef = useRef<number | null>(null);
  const isWebPlatform = Capacitor.getPlatform() === 'web';

  useEffect(() => {
    return () => {
      if (fallbackTimerRef.current) {
        window.clearTimeout(fallbackTimerRef.current);
      }
    };
  }, []);

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  const handleLogin = async () => {
    try {
      setLoading(true);
      setShowFallback(false);
      const platform = Capacitor.getPlatform();
      const isTauriDesktop = typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
      const client: 'web' | 'desktop' =
        isTauriDesktop || platform === 'ios' || platform === 'android' ? 'desktop' : 'web';
      const oauthUrl = await authLauncher.getOAuthUrl(client);

      if (isTauriDesktop) {
        console.info('[LOGIN_PATH] desktop-shell-open');
        setAuthStep('opening-browser');
        setLastOAuthUrl(oauthUrl);
        await browserLauncher.openUrl(oauthUrl);
        setAuthStep('waiting-return');
        fallbackTimerRef.current = window.setTimeout(() => {
          setShowFallback(true);
        }, 12000);
        return;
      }

      if (platform === 'web') {
        console.info('[LOGIN_PATH] web-window-location');
        setAuthStep('opening-browser');
        window.location.href = oauthUrl;
        return;
      }

      if (platform === 'ios' || platform === 'android') {
        setAuthStep('opening-browser');
        await authLauncher.openInAppBrowser(oauthUrl);
        return;
      }

      throw new Error(`未知平台: ${platform}`);
    } catch (error) {
      console.error(error);
      alert(error instanceof Error ? error.message : '获取登录二维码失败');
      setAuthStep('idle');
    } finally {
      setLoading(false);
    }
  };

  const handleDesktopRetry = async () => {
    if (!lastOAuthUrl) {
      await handleLogin();
      return;
    }
    try {
      setAuthStep('opening-browser');
      await browserLauncher.openUrl(lastOAuthUrl);
      setAuthStep('waiting-return');
      setShowFallback(false);
      if (fallbackTimerRef.current) {
        window.clearTimeout(fallbackTimerRef.current);
      }
      fallbackTimerRef.current = window.setTimeout(() => {
        setShowFallback(true);
      }, 12000);
    } catch (error) {
      console.error(error);
      alert(error instanceof Error ? error.message : '重新唤起授权失败');
    }
  };

  // const handleWebDirectLogin = () => {
  //   window.location.href = 'https://api.yiiie.cn/api/auth/lark/login';
  // };

  return (
    // 1. 底色改为极其干净的冷白灰
    // 1. 底色改为极其干净的冷白灰
    <div className="relative flex min-h-screen flex-col items-center justify-center bg-[#F8F9FC] overflow-hidden">
      
      {/* ✨ 新增：动态弥散光晕，增加科技感和呼吸感 */}
      <div className="absolute top-[-10%] left-[-10%] w-[40%] h-[40%] rounded-full bg-blue-400/20 blur-[120px] mix-blend-multiply animate-pulse" style={{ animationDuration: '8s' }}></div>
      <div className="absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] rounded-full bg-indigo-400/20 blur-[120px] mix-blend-multiply animate-pulse" style={{ animationDuration: '10s' }}></div>

      {/* 2. 祖传点阵配方优化：降低透明度，放大点距，确保背景不杂乱 */}
      <div className="absolute inset-0 bg-[radial-gradient(rgba(0,0,0,0.04)_1px,transparent_1px)] [background-size:24px_24px] [mask-image:radial-gradient(ellipse_80%_80%_at_50%_50%,#000_60%,transparent_100%)] z-0"></div>

      <div className="relative z-10 w-full max-w-5xl px-4 grid lg:grid-cols-2 gap-12 items-center">
        
        {/* 左侧：品牌与价值主张 */}
        <div className="hidden lg:flex flex-col space-y-8 pr-12">
          <div className="inline-flex items-center space-x-2 rounded-full border border-zinc-200 bg-white/60 backdrop-blur-sm px-3 py-1 text-sm font-medium text-zinc-600 shadow-sm w-fit">
            <span className="flex h-2 w-2 rounded-full bg-[#3370ff]"></span>
            <span>Agent-Pilot 系统已就绪</span>
          </div>
          
          <div className="space-y-4 relative z-10">
            {/* ✨ 优化：使用 bg-clip-text 让文字拥有非常高级的深色渐变质感 */}
            <h1 className="text-4xl font-extrabold tracking-tight sm:text-5xl bg-clip-text text-transparent bg-gradient-to-br from-zinc-900 via-zinc-700 to-zinc-500 pb-2">
              化繁为简的<br />协同控制中枢
            </h1>
            <p className="text-lg text-zinc-500 leading-relaxed max-w-md">
              以 AI Agent 为主驾驶，将 IM 对话转化为可执行的工作流。从意图捕捉到文档演示一键闭环，辅助您的每一次决策。
            </p>
          </div>

          <div className="grid grid-cols-2 gap-6 pt-4 border-t border-zinc-200/80">
            <div className="space-y-2">
              <Terminal className="h-5 w-5 text-zinc-600" />
              <h3 className="font-semibold text-zinc-900">自然语言驱动</h3>
              <p className="text-sm text-zinc-500">抛弃繁琐点击，一行指令即可编排复杂套件任务。</p>
            </div>
            <div className="space-y-2">
              <LayoutDashboard className="h-5 w-5 text-zinc-600" />
              <h3 className="font-semibold text-zinc-900">多端全局监控</h3>
              <p className="text-sm text-zinc-500">透明化的步骤条与实时预览，掌控全局进度。</p>
            </div>
          </div>
        </div>

{/* 右侧：极简悬浮卡片 */}
        <div className="w-full max-w-md mx-auto">
          {/* ✨ 优化：毛玻璃拟物态卡片，更厚实的微小立体感 */}
          <div className="relative flex flex-col space-y-8 rounded-3xl border border-white/60 bg-white/70 p-10 shadow-[0_8px_40px_rgb(0,0,0,0.06)] backdrop-blur-xl ring-1 ring-zinc-200/50 z-10">
            <div className="text-center space-y-3">
              <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-zinc-900 border border-zinc-800 shadow-[6px_6px_16px_rgba(0,0,0,0.06),-6px_-6px_16px_rgba(255,255,255,1)]">
                <Zap className="h-6 w-6 text-white drop-shadow-sm" fill="currentColor" fillOpacity={0.15} />
              </div>
              <h2 className="text-2xl font-bold tracking-tight text-zinc-900">
                Agent Pilot
              </h2>
              <p className="text-sm text-zinc-500">
                连接您的工作空间以启动控制台
              </p>
            </div>

            <div className="space-y-4">
              {/* ✨ 优化：增加渐变按钮底色与同色系弥散阴影，点击感更强 */}
              <Button
                className="w-full h-12 text-base font-medium bg-gradient-to-r from-[#3370ff] to-[#2558d1] hover:from-[#2b5fd9] hover:to-[#1e4ab5] text-white shadow-md shadow-[#3370ff]/25 border border-[#3370ff]/50 transition-all active:scale-[0.98]"
                onClick={handleLogin}
                disabled={loading}
              >
                {loading
                  ? (authStep === 'opening-browser' ? '正在打开浏览器授权...' : '正在处理登录...')
                  : '授权飞书账号登录'}
              </Button>

              {/*
              {isWebPlatform && (
                <button
                  type="button"
                  className="w-full text-sm text-zinc-500 hover:text-zinc-700 underline underline-offset-2"
                  onClick={handleWebDirectLogin}
                  disabled={loading}
                >
                  在浏览器中直接登录
                </button>
              )}
              */}

              {!isWebPlatform && authStep !== 'idle' && (
                <div className="text-center text-xs text-zinc-500">
                  授权完成将自动返回应用
                </div>
              )}

              {!isWebPlatform && authStep === 'waiting-return' && showFallback && (
                <button
                  type="button"
                  className="w-full text-sm text-zinc-500 hover:text-zinc-700 underline underline-offset-2"
                  onClick={handleDesktopRetry}
                  disabled={loading}
                >
                  我已授权，点击重试/重新唤起
                </button>
              )}

              <div className="text-center text-xs text-zinc-400">
                登录即表示同意以 OAuth2 接入飞书开放平台
              </div>
            </div>
          </div>
        </div>

      </div>
    </div>
  );
}
