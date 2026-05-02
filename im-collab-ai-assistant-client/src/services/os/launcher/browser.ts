// src/services/os/launcher/browser.ts
import { Capacitor } from '@capacitor/core';
import { Browser } from '@capacitor/browser';

/**
 * 判断当前是否运行在 Tauri (桌面端) 环境中
 */
const isTauriEnvironment = (): boolean => {
  return typeof window !== 'undefined' && '__TAURI__' in window;
};

export const browserLauncher = {
  /**
   * 跨端安全的 URL 拉起方法
   */
  openUrl: async (url: string): Promise<void> => {
    if (!url) return;

    try {
      // 1. 移动端环境 (Capacitor - iOS/Android)
      // 产品逻辑：调用底层插件，唤起 App 内置浏览器。若 URL 触发了飞书的 Deep Link，
      // 手机系统会自动拉起本地飞书 App；若没装飞书，则在安全的半屏容器中渲染 H5。
      if (Capacitor.isNativePlatform()) {
        await Browser.open({ 
          url, 
          presentationStyle: 'popover', // iOS 半屏弹出效果
          toolbarColor: '#ffffff'
        });
        return;
      }

      // 2. 桌面端环境 (Tauri - Windows/macOS)
      // 产品逻辑：跳出 Tauri 沙盒，调用操作系统的原生 shell API，
      // 拉起用户电脑默认的浏览器 (如 Chrome/Edge) 或飞书桌面端协议。
      if (isTauriEnvironment()) {
        try {
          // 动态导入，防止纯 Web 端编译时找不到 @tauri-apps/api 报错
          const { open } = await import('@tauri-apps/plugin-shell');
          await open(url);
          return;
        } catch (e) {
          console.warn('Tauri shell open failed, falling back to window.open', e);
        }
      }

      // 3. Web 端环境 (纯浏览器)
      // 产品逻辑：最基础的兜底，新标签页打开
      window.open(url, '_blank', 'noopener,noreferrer');
      
    } catch (error) {
      console.error('拉起外部链接失败:', error);
      // 极端异常兜底
      window.open(url, '_blank');
    }
  }
};