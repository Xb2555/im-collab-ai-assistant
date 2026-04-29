// src/App.tsx
import { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/useAuthStore';
import { authApi } from '@/services/api/auth';
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
  
  //新增：增加一个校验状态，防止在校验完成前页面闪烁
  const [isVerifying, setIsVerifying] = useState(true);

  useEffect(() => {
    const verifyToken = async () => {
      // 只有在本地以为自己登录了的时候，才去向后端确认
      if (isAuthenticated) {
        try {
          await authApi.getMe();
        } catch (error) {
          console.warn('应用初始化 Token 校验失败，自动清理状态');
          // 这里的 clearAuth 会把 isAuthenticated 设为 false，从而触发重定向
          clearAuth(); 
        }
      }
      setIsVerifying(false);
    };

    verifyToken();
  }, [isAuthenticated, clearAuth]);

  // ✨ 新增：在校验请求返回前，展示一个友好的 Loading 动画
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

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 公开路由 */}
        <Route path="/login" element={<Login />} />
        
        {/* 这个路由必须和你们飞书后台配置的回调地址路径一致 */}
        <Route path="/auth-callback" element={<Callback />} /> 

        {/* 保护路由 */}
        <Route
          path="/"
          element={
            <RequireAuth>
              <Dashboard />
            </RequireAuth>
          }
        />
        
        {/* 兜底：未匹配到的路径统统回首页 */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}