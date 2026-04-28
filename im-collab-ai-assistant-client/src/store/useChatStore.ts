// src/store/useChatStore.ts
import { create } from 'zustand';

interface ChatState {
  activeChatId: string | null; // 当前选中的群聊 ID
  setActiveChatId: (id: string | null) => void;
}

export const useChatStore = create<ChatState>((set) => ({
  activeChatId: null,
  setActiveChatId: (id) => set({ activeChatId: id }),
}));