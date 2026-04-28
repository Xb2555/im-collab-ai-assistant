// src/App.tsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/useAuthStore';
import Login from '@/pages/Login';
import Callback from '@/pages/Callback';
import Dashboard from '@/pages/Dashboard';

/**
 * 全局路由守卫组件
 * 如果用户未登录，强制拦截并跳转到 /login
 */
function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
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
        {/* 注意：如果是根目录下的 /callback，这里就改成 path="/callback" */}

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