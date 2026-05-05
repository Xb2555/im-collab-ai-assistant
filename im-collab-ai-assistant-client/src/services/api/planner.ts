// src/services/api/planner.ts
import { apiClient } from './client';
import type { 
  PlanRequest, 
  PlanPreviewVO, 
  PlanCommandRequest, 
  ResumeRequest,
  ApiResponse,
  DocumentIterationRequest,
  DocumentIterationResponse,
  DocumentIterationApprovalRequest // ✨ 重点：把这个补进大括号里！
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
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

/**
   * [新增] 6. 执行文档细颗粒度迭代 (Doc Iteration)
   * 对应《5.1. 执行文档迭代.md》
   */
  iterateDocument: async (data: DocumentIterationRequest): Promise<DocumentIterationResponse> => {
    const response = await apiClient.post<ApiResponse<DocumentIterationResponse>>(
      '/api/planner/tasks/document-iteration', 
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 4. 中断任务
  //  修复：加上 /api 前缀
  interruptTask: async (taskId: string): Promise<PlanPreviewVO> => {
    const response = await apiClient.post<ApiResponse<PlanPreviewVO>>(`/api/planner/tasks/${taskId}/interrupt`);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

  // 5. 恢复任务 (提供追问补充信息)
  //  修复：加上 /api 前缀
  resumeTask: async (taskId: string, data: ResumeRequest): Promise<PlanPreviewVO> => {
    const response = await apiClient.post<ApiResponse<PlanPreviewVO>>(`/api/planner/tasks/${taskId}/resume`, data);
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },

//  新增/修改：获取任务运行时快照 (新的单一事实源)
  // ✨ 修复：带智能重试机制的获取任务运行时快照
  // 解决后端刚创建任务时，数据库事务未提交或主从延迟导致的短暂 404 问题
  getTaskRuntime: async (taskId: string, maxRetries = 3): Promise<any> => {
    for (let i = 0; i < maxRetries; i++) {
      try {
        const response = await apiClient.get(`/api/planner/tasks/${taskId}/runtime`, {
          params: { _t: Date.now() } // 打破浏览器缓存
        });

        // 如果后端返回 40400 且还没达到最大重试次数，则等待后重试
        if (response.data.code === 40400 && i < maxRetries - 1) {
          console.warn(`⏳ [重试 ${i + 1}/${maxRetries}] 后端任务 ${taskId} 尚未准备好，等待 800ms 后重试...`);
          await new Promise(resolve => setTimeout(resolve, 800)); // 等待 800 毫秒
          continue; // 继续下一次循环重新请求
        }

        // 如果是其他业务错误（或最后一次还是 404），直接抛出
        if (response.data.code !== 0) {
          throw new Error(response.data.message);
        }

        return response.data.data;
      } catch (e: any) {
        // 如果是网络层面的错误且没超次数，也进行重试
        if (i < maxRetries - 1) {
          console.warn(`⏳ [网络重试 ${i + 1}/${maxRetries}] 请求失败，等待 800ms 后重试...`, e);
          await new Promise(resolve => setTimeout(resolve, 800));
          continue;
        }
        throw e; // 彻底失败，向上抛出
      }
    }
  },

 /**
   * [新增] 获取我的活跃任务 (用于刷新后恢复工作台)
   * 后端等价于查询: PLANNING, CLARIFYING, WAITING_APPROVAL, EXECUTING
   */
getActiveTasks: async (limit = 20, cursor = '0'): Promise<TaskListResponse> => {
    const response = await apiClient.get('/api/planner/tasks/active', {
      params: { limit, cursor }
    });
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

  /**
   * [新增] 7. 审批文档迭代计划 (Doc Iteration Approval)
   * 对应《5.2. 审批文档迭代计划》
   */
  approveDocumentIteration: async (taskId: string, data: DocumentIterationApprovalRequest): Promise<DocumentIterationResponse> => {
    const response = await apiClient.post<ApiResponse<DocumentIterationResponse>>(
      `/api/planner/tasks/${taskId}/document-iteration/approval`,
      data
    );
    if (response.data.code !== 0) throw new Error(response.data.message);
    return response.data.data;
  },
};