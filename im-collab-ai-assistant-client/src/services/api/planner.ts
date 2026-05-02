// src/services/api/planner.ts
import { apiClient } from './client';
import type { 
  PlanRequest, 
  PlanPreviewVO, 
  PlanCommandRequest, 
  ResumeRequest,
  ApiResponse 
} from '@/types/api';


export interface TaskListResponse {
  tasks: any[]; 
  nextCursor: string | null;
}

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
  },

  // ✨ 新增：获取任务运行时快照 (新的单一事实源)
  getTaskRuntime: async (taskId: string): Promise<any> => { // 类型先用 any 兜底，后续补全
    const response = await apiClient.get(`/api/planner/tasks/${taskId}/runtime`);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

 /**
   * [新增] 获取我的活跃任务 (用于刷新后恢复工作台)
   * 后端等价于查询: PLANNING, CLARIFYING, WAITING_APPROVAL, EXECUTING
   */
  getActiveTasks: async (limit = 20, cursor = '0'): Promise<TaskListResponse> => {
    const response = await apiClient.get('/api/planner/tasks/active', {
      params: { limit, cursor }
    });
    // 拦截业务错码
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  /**
   * [新增] 获取我的历史任务列表 (用于侧边栏展示)
   */
  getTasks: async (status?: string, limit = 20, cursor = '0'): Promise<TaskListResponse> => {
    const response = await apiClient.get('/api/planner/tasks', {
      params: { status, limit, cursor } // 若 status 不传，Axios 会自动忽略
    });
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  }, 

};