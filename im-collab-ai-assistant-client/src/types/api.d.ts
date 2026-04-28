// src/types/api.d.ts

/**
 * 通用响应结构 
 */
export interface ApiResponse<T = unknown> {
  code: number;
  data: T;
  message: string;
}

// --- Auth 模块相关契约 ---

/**
 * 登录用户信息 
 */
export interface User {
  name: string;
  avatarUrl: string;
}

/**
 * code 换 token 请求体
 */
export interface AuthCallbackRequest {
  code: string;
  state: string;
}

/**
 * code 换 token 响应体 
 */
export interface AuthCallbackResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}