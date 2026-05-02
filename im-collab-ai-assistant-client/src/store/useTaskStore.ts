// src/store/useTaskStore.ts
import { create } from 'zustand';
import type { PlanPreviewVO } from '@/types/api';

interface TaskState {
  activeTaskId: string | null;
  planPreview: PlanPreviewVO | null;
  setActiveTaskId: (id: string | null) => void;
  setPlanPreview: (preview: PlanPreviewVO | null) => void;
  clearTask: () => void;
}

export const useTaskStore = create<TaskState>((set) => ({
  activeTaskId: null,
  planPreview: null,
  setActiveTaskId: (id) => set({ activeTaskId: id }),
  setPlanPreview: (preview) => set({ planPreview: preview }),
  clearTask: () => set({ activeTaskId: null, planPreview: null }),
}));