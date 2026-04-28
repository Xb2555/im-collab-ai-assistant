// src/services/os/launcher/auth.ts
import { authApi } from '@/services/api/auth';

export const authLauncher = {
  /**
   * 唤起登录流程
   * MVP 阶段：直接使用系统/浏览器原生能力跳转。
   * 后续接入 Capacitor 时，在这里加个判断换成 @capacitor/browser 的 Browser.open() 即可。
   */
  startLogin: () => {
    // 触发 HTTP GET 跳转后端，后端会 302 重定向到真实的飞书页面
    window.location.href = authApi.getLoginUrl();
  }
};