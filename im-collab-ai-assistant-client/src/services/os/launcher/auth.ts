// src/services/os/launcher/auth.ts
import { Browser } from '@capacitor/browser';
import { authApi } from '@/services/api/auth';

const isTauriEnvironment = (): boolean => {
  return typeof window !== 'undefined' && '__TAURI__' in window;
};

export const authLauncher = {
  isTauriEnvironment,

  /**
   * 获取飞书 OAuth 授权 URL（用于二维码渲染或 App 内打开）
   */
  getOAuthUrl: async (): Promise<string> => {
    const result = await authApi.getLoginUrl();
    return result.authorizationUri;
  },

  /**
   * 在移动端 App 内浏览器中打开 OAuth URL
   */
  openInAppBrowser: async (url: string): Promise<void> => {
    await Browser.open({ url });
  },

  /**
   * 统一登录入口：
   * - Web: 直接跳转后端登录入口
   * - Tauri: 返回 OAuth URL 给页面用于渲染二维码
   */
  startLogin: async (): Promise<string | null> => {
    if (!isTauriEnvironment()) {
      window.location.href = authApi.getLoginUrlRedirectPath();
      return null;
    }
    return authLauncher.getOAuthUrl();
  }
};
