// src/pages/Callback.tsx
import { useEffect, useRef, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { authApi } from '@/services/api/auth';
import { useAuthStore } from '@/store/useAuthStore';

export default function Callback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const setAuth = useAuthStore((state) => state.setAuth);
  
  // 使用 useRef 防止 React 18 严格模式下的开发环境双次触发
  const hasFetched = useRef(false);
  const [statusText, setStatusText] = useState('正在安全验证身份，请稍候...');

  useEffect(() => {
    if (hasFetched.current) return;
    hasFetched.current = true;

    const code = searchParams.get('code');
    const state = searchParams.get('state') || '';

    if (!code) {
      console.warn('未获取到授权码，返回登录页');
      navigate('/login', { replace: true });
      return;
    }

    // 调用我们在第一步写的 API
    authApi.callback({ code, state })
      .then((data) => {
        // 成功！将 token 和用户信息写入 Zustand 全局状态
        setAuth(data.accessToken, data.user);
        setStatusText('登录成功，正在进入...');
        // 给用户一个短暂的成功过渡感
        window.setTimeout(() => {
          navigate('/', { replace: true });
        }, 500);
      })
      .catch((err) => {
        console.error('授权换 Token 失败:', err);
        // TODO: 后续可以接入 shadcn 的 Toast 提示错误
        navigate('/login', { replace: true });
      });

  }, [searchParams, navigate, setAuth]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="flex flex-col items-center space-y-4">
        {/* 一个简单的 Tailwind 纯 CSS Loading 动画 */}
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
        <p className="text-sm text-muted-foreground animate-pulse">
          {statusText}
        </p>
      </div>
    </div>
  );
}