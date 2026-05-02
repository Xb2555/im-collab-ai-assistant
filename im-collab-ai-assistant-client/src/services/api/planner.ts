// src/services/api/planner.ts
import { apiClient } from './client';
import type { 
  PlanRequest, 
  PlanPreviewVO, 
  PlanCommandRequest, 
  ResumeRequest,
  ApiResponse 
} from '@/types/api';

export const plannerApi = {
  // 1. 发起任务规划 (抛入自然语言指令)
  // ✨ 修复：加上 /api 前缀
  createPlan: async (data: PlanRequest): Promise<PlanPreviewVO> => {
    const response = await apiClient.post<ApiResponse<PlanPreviewVO>>('/api/planner/tasks/plan', data);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 2. 获取任务状态快照
  // ✨ 修复：加上 /api 前缀
  getTask: async (taskId: string): Promise<PlanPreviewVO> => {
    const response = await apiClient.get<ApiResponse<PlanPreviewVO>>(`/api/planner/tasks/${taskId}`);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 3. 提交干预指令 (确认/重规划/取消) - 会触发乐观锁拦截
  // ✨ 修复：加上 /api 前缀
  executeCommand: async (taskId: string, data: PlanCommandRequest): Promise<PlanPreviewVO> => {
    const response = await apiClient.post<ApiResponse<PlanPreviewVO>>(`/api/planner/tasks/${taskId}/commands`, data);
    // 注意：如果报 40900 版本冲突，在 apiClient 拦截器里已经被拦截成 reject(Error) 了
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 4. 中断任务
  // ✨ 修复：加上 /api 前缀
  interruptTask: async (taskId: string): Promise<PlanPreviewVO> => {
    const response = await apiClient.post<ApiResponse<PlanPreviewVO>>(`/api/planner/tasks/${taskId}/interrupt`);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 5. 恢复任务 (提供追问补充信息)
  // ✨ 修复：加上 /api 前缀
  resumeTask: async (taskId: string, data: ResumeRequest): Promise<PlanPreviewVO> => {
    const response = await apiClient.post<ApiResponse<PlanPreviewVO>>(`/api/planner/tasks/${taskId}/resume`, data);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  }
};