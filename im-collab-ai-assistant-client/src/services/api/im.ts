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
  avatar?: string;
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

export interface UserItem {
  openId: string;
  name: string;
  avatarUrl?: string;
  department?: string;
}

export interface InviteChatRequest {
  chatId: string;
  userOpenIds: string[];
}

export interface InviteChatResponse {
  invalidOpenIds: string[];
  notExistedOpenIds: string[];
  pendingApprovalOpenIds: string[];
}

// --- 新增：历史消息接口契约 ---
export interface LarkMessageHistoryItem {
  messageId?: string | null;
  msgType?: string | null;
  createTime?: string | null;
  chatId?: string | null;
  senderId?: string | null;
  senderType?: string | null;
  content?: string | null;
}

export interface LarkMessageHistoryResponse {
  items: LarkMessageHistoryItem[];
  hasMore: boolean;
  pageToken?: string | null;
}

export interface GetHistoryRequest {
  containerIdType: 'chat' | 'thread';
  containerId: string;
  startTime?: string;
  endTime?: string;
  sortType?: 'ByCreateTimeAsc' | 'ByCreateTimeDesc';
  pageSize?: number;
  pageToken?: string;
  signal?: AbortSignal; // ✨ 新增：用于主动中断请求
}

// ✨ 新增：分享链接相关契约
export type ChatShareLinkValidityPeriod = "week" | "year" | "permanently";

export interface CreateChatShareLinkRequest {
  chatId: string;
  validityPeriod?: ChatShareLinkValidityPeriod;
}

export interface ChatShareLinkData {
  shareLink: string | null;
  expireTime: string | null;
  isPermanent: boolean;
}

export const imApi = {
  // 1. 获取群聊列表
getJoinedChats: async (): Promise<GetChatsResponse> => {
    const response = await apiClient.get<ApiResponse<GetChatsResponse>>(
      '/im/chats/joined',
      {
        params: { 
          containsCurrentBot: true, 
          pageSize: 20,
          _t: Date.now() //  核心修复：加上时间戳，强制打破浏览器 GET 缓存！
        }
      }
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 2. 创建群聊
  createChat: async (data: CreateChatRequest): Promise<CreateChatResponse> => {
    const response = await apiClient.post<ApiResponse<CreateChatResponse>>(
      '/im/chats/createChat', 
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 3. 模糊搜索组织架构内的用户
  searchUsers: async (query: string): Promise<UserItem[]> => {
    if (!query) return [];
    const response = await apiClient.get<ApiResponse<{ items: UserItem[] }>>(
      '/im/organization-users/search', 
      { params: { query, pageSize: 10 } }
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data.items || [];
  },

  // 在 imApi 对象里增加方法
  // 4. 发送群消息
  sendMessage: async (data: SendMessageRequest): Promise<any> => {
    const response = await apiClient.post<ApiResponse<any>>(
      '/im/messages/send', 
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  //  5. 新增：邀请用户加入群聊
  invite: async (data: InviteChatRequest): Promise<InviteChatResponse> => {
    const response = await apiClient.post<ApiResponse<InviteChatResponse>>(
      '/im/chats/invite',
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

// 2. 修改 getChatHistory 的实现，把 signal 透传给 apiClient
  getChatHistory: async (params: GetHistoryRequest): Promise<LarkMessageHistoryResponse> => {
    // 将 signal 从 params 中解构出来，避免把它当成 URL 参数发给后端
    const { signal, ...restParams } = params; 
    
    const response = await apiClient.get<ApiResponse<LarkMessageHistoryResponse>>(
      '/im/messages/history',
      { 
        params: restParams,
        signal // ✨ 新增：把取消信号传递给 Axios
      }
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  //  7. 获取群分享链接
  createShareLink: async (data: CreateChatShareLinkRequest): Promise<ChatShareLinkData> => {
    const response = await apiClient.post<ApiResponse<ChatShareLinkData>>(
      `/im/chats/${encodeURIComponent(data.chatId)}/link`,
      { validityPeriod: data.validityPeriod || 'week' }
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  }
};