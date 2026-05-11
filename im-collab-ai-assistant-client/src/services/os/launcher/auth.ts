// src/services/os/launcher/auth.ts
import { Browser } from '@capacitor/browser';
import { Capacitor } from '@capacitor/core';
import { authApi, type OAuthClientType } from '@/services/api/auth';

const isTauriEnvironment = (): boolean => {
  return typeof window !== 'undefined' && (window as any).__TAURI_INTERNALS__ !== undefined;
};

export const authLauncher = {
  isTauriEnvironment,

  /**
   * 获取飞书 OAuth 授权 URL（用于二维码渲染或 App 内打开）
   */
  getOAuthUrl: async (client: OAuthClientType): Promise<string> => {
    const result = await authApi.getLoginUrl(client);
    return result.authorizationUri;
  },

  /**
   * 在移动端 App 内浏览器中打开 OAuth URL
   */
  openInAppBrowser: async (url: string): Promise<void> => {
    await Browser.open({ url });
  },

  /**
   * 统一登录入口（显式 client 路线）：
   * - tauri / ios / android -> desktop
   * - web -> web
   * 返回 OAuth URL 供外层决定打开方式（重定向或内嵌浏览器）
   */
  startLogin: async (): Promise<string> => {
    const platform = Capacitor.getPlatform();
    const client: OAuthClientType =
      isTauriEnvironment() || platform === 'ios' || platform === 'android' ? 'desktop' : 'web';

    return authLauncher.getOAuthUrl(client);
  }
};
