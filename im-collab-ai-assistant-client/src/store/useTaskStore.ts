// src/store/useTaskStore.ts
import { create } from 'zustand';
import type { PlanPreviewVO, TaskRuntimeVO } from '@/types/api';

interface TaskState {
  activeTaskId: string | null;
  planPreview: PlanPreviewVO | null;
  taskRuntime: TaskRuntimeVO | null;

  // ✨ 新增：用于存储打字机效果的增量文本
  aiThinkingText: string;
  // ✨ 新增：标记当前是否正在接收 SSE 流
  isStreaming: boolean;
  isPlanning: boolean;
  setIsPlanning: (status: boolean) => void;
  setActiveTaskId: (id: string | null) => void;
  setPlanPreview: (preview: PlanPreviewVO | null) => void;
  setTaskRuntime: (runtime: TaskRuntimeVO | null) => void;

  // ✨ 新增：更新思考文本
  appendThinkingText: (text: string) => void;
  clearThinkingText: () => void;
  setIsStreaming: (status: boolean) => void;

  clearTask: () => void;

}

export const useTaskStore = create<TaskState>((set) => ({
  activeTaskId: null,
  planPreview: null,
  taskRuntime: null,
  aiThinkingText: '',
  isStreaming: false,
isPlanning: false, // 👈 新增初始状态
  setActiveTaskId: (id) => set({ activeTaskId: id }),
  setPlanPreview: (preview) => set({ planPreview: preview }),
  setTaskRuntime: (runtime) => set({ taskRuntime: runtime }),

  appendThinkingText: (text) => set((state) => ({ aiThinkingText: state.aiThinkingText + text })),
  clearThinkingText: () => set({ aiThinkingText: '' }),
  setIsStreaming: (status) => set({ isStreaming: status }),
setIsPlanning: (status) => set({ isPlanning: status }), // 👈 新增方法
  clearTask: () => set((state) => {
    // ✨ 核心修复：只要是用户手动点 X 关闭的任务，统统加入黑名单！拒绝任何状态（包括 CLARIFYING）的自动弹脸
    if (state.activeTaskId) {
      const closed = JSON.parse(localStorage.getItem('closed_tasks') || '[]');
      if (!closed.includes(state.activeTaskId)) {
        closed.push(state.activeTaskId);
        // 仅保留最近关闭的50个记录
        localStorage.setItem('closed_tasks', JSON.stringify(closed.slice(-50)));
      }
    }

    return {
      activeTaskId: null,
      planPreview: null,
      taskRuntime: null,
      aiThinkingText: '',
      isStreaming: false,
      isPlanning: false, // 👈 清理任务时，也顺手重置
    };
  }),
}));
