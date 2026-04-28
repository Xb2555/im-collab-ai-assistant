// src/store/useAuthStore.ts
import { create } from 'zustand';
import type { User } from '@/types/api';

interface AuthState {
  accessToken: string | null;
  user: User | null;
  isAuthenticated: boolean;
  setAuth: (token: string, user: User) => void;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  // 初始化时从本地存储同步状态
  accessToken: localStorage.getItem('agent_token'),
  user: JSON.parse(localStorage.getItem('agent_user') || 'null'),
  isAuthenticated: !!localStorage.getItem('agent_token'),

  setAuth: (token, user) => {
    localStorage.setItem('agent_token', token);
    localStorage.setItem('agent_user', JSON.stringify(user));
    set({ accessToken: token, user, isAuthenticated: true });
  },

  clearAuth: () => {
    localStorage.removeItem('agent_token');
    localStorage.removeItem('agent_user');
    set({ accessToken: null, user: null, isAuthenticated: false });
  }
}));