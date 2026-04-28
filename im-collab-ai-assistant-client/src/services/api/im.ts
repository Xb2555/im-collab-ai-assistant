// src/services/api/im.ts
import { apiClient } from './client';
import type { ApiResponse } from '@/types/api';

// 在顶部增加契约
export interface SendMessageRequest {
  chatId: string;
  text: string; 
  idempotencyKey: string; 
}
// --- 已有接口契约 ---
export interface ChatItem { 
  chatId: string; 
  name: string; 
  avatarUrl?: string; 
}

export interface GetChatsResponse { 
  items: ChatItem[]; 
  hasMore: boolean; 
  pageToken: string | null; 
}

export interface CreateChatRequest { 
  name: string; 
  description?: string; 
  chatType?: 'private' | 'public'; 
  userOpenIds?: string[]; 
  uuid: string; 
}

export interface CreateChatResponse { 
  chatId: string; 
  name: string; 
  chatType: string; 
  ownerOpenId: string; 
}

// 👇 就是因为少了下面这段，才会报你那个错！
export interface UserItem {
  openId: string;
  name: string;
  avatarUrl?: string;
  department?: string;
}

export const imApi = {
  // 1. 获取群聊列表
getJoinedChats: async (): Promise<GetChatsResponse> => {
    const response = await apiClient.get<ApiResponse<GetChatsResponse>>(
      '/api/im/chats/joined',
      {
        params: { 
          containsCurrentBot: true, 
          pageSize: 20,
          _t: Date.now() // ✨ 核心修复：加上时间戳，强制打破浏览器 GET 缓存！
        }
      }
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 2. 创建群聊
  createChat: async (data: CreateChatRequest): Promise<CreateChatResponse> => {
    const response = await apiClient.post<ApiResponse<CreateChatResponse>>(
      '/api/im/chats/createChat', 
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 3. 模糊搜索组织架构内的用户
  searchUsers: async (query: string): Promise<UserItem[]> => {
    if (!query) return [];
    const response = await apiClient.get<ApiResponse<{ items: UserItem[] }>>(
      '/api/im/organization-users/search', 
      { params: { query, pageSize: 10 } }
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data.items || [];
  },

  // 在 imApi 对象里增加方法
  // 4. 发送群消息
  sendMessage: async (data: SendMessageRequest): Promise<any> => {
    const response = await apiClient.post<ApiResponse<any>>(
      '/api/im/messages/send', 
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  }
};