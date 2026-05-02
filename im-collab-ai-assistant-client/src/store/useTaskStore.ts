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

  setActiveTaskId: (id) => set({ activeTaskId: id }),
  setPlanPreview: (preview) => set({ planPreview: preview }),
  setTaskRuntime: (runtime) => set({ taskRuntime: runtime }),

  appendThinkingText: (text) => set((state) => ({ aiThinkingText: state.aiThinkingText + text })),
  clearThinkingText: () => set({ aiThinkingText: '' }),
  setIsStreaming: (status) => set({ isStreaming: status }),

  clearTask: () => set({
    activeTaskId: null,
    planPreview: null,
    taskRuntime: null,
    aiThinkingText: '',
    isStreaming: false,
  }),
}));
