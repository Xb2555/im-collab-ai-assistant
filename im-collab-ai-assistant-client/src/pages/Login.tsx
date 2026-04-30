// src/pages/Login.tsx
import { useState } from 'react';
import { Capacitor } from '@capacitor/core';
import { Button } from '@/components/ui/button';
import { authLauncher } from '@/services/os/launcher/auth';
import { useAuthStore } from '@/store/useAuthStore';
import { Navigate } from 'react-router-dom';
import { Terminal, LayoutDashboard, Zap } from 'lucide-react';

export default function Login() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [loading, setLoading] = useState(false);
  const isWebPlatform = Capacitor.getPlatform() === 'web';

  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  const handleLogin = async () => {
    try {
      setLoading(true);
      const platform = Capacitor.getPlatform();

      if (platform === 'ios' || platform === 'android') {
        const oauthUrl = await authLauncher.getOAuthUrl();
        await authLauncher.openInAppBrowser(oauthUrl);
        return;
      }

      const oauthUrl = await authLauncher.getOAuthUrl();
      window.location.href = oauthUrl;
    } catch (error) {
      console.error(error);
      alert(error instanceof Error ? error.message : '获取登录二维码失败');
    } finally {
      setLoading(false);
    }
  };

  const handleWebDirectLogin = () => {
    window.location.href = '/api/auth/lark/login';
  };

  return (
    // 1. 底色改为极其干净的冷白灰
    <div className="relative flex min-h-screen flex-col items-center justify-center bg-[#FAFAFC] overflow-hidden">
      
      {/* 2. 祖传点阵配方：纯黑 + 6%透明度，1px大小，20px间距，超柔和渐隐边缘 */}
      <div className="absolute inset-0 bg-[radial-gradient(rgba(0,0,0,0.06)_1px,transparent_1px)] [background-size:20px_20px] [mask-image:radial-gradient(ellipse_60%_60%_at_50%_50%,#000_60%,transparent_100%)]"></div>

      <div className="relative z-10 w-full max-w-5xl px-4 grid lg:grid-cols-2 gap-12 items-center">
        
        {/* 左侧：品牌与价值主张 */}
        <div className="hidden lg:flex flex-col space-y-8 pr-12">
          <div className="inline-flex items-center space-x-2 rounded-full border border-zinc-200 bg-white/60 backdrop-blur-sm px-3 py-1 text-sm font-medium text-zinc-600 shadow-sm w-fit">
            <span className="flex h-2 w-2 rounded-full bg-[#3370ff]"></span>
            <span>Agent-Pilot 系统已就绪</span>
          </div>
          
          <div className="space-y-4">
            <h1 className="text-4xl font-extrabold tracking-tight text-zinc-900 sm:text-5xl">
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
          {/* 3. 卡片阴影变得更通透柔和，配合纯白背景 */}
          <div className="flex flex-col space-y-8 rounded-2xl border border-zinc-200/60 bg-white p-10 shadow-[0_8px_30px_rgb(0,0,0,0.04)]">
            <div className="text-center space-y-3">
              <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl bg-zinc-900 shadow-sm">
                <Zap className="h-6 w-6 text-white" />
              </div>
              <h2 className="text-2xl font-bold tracking-tight text-zinc-900">
                Agent Pilot
              </h2>
              <p className="text-sm text-zinc-500">
                连接您的工作空间以启动仪表盘
              </p>
            </div>

            <div className="space-y-4">
              <Button
                className="w-full h-12 text-base font-medium bg-[#3370ff] hover:bg-[#2b5fd9] text-white shadow-sm transition-all active:scale-[0.98]"
                onClick={handleLogin}
                disabled={loading}
              >
                {loading ? '正在跳转飞书授权...' : '授权飞书账号登录'}
              </Button>

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
