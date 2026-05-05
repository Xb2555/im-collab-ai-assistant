// src/components/dashboard/ChatListSidebar.tsx
import { useState } from 'react';
import { useRequest } from 'ahooks';
import { imApi } from '@/services/api/im';
import { useChatStore } from '@/store/useChatStore';
import { Button } from '@/components/ui/button';
import { MessageSquare, Plus, Search, Loader2 } from 'lucide-react';
import { CreateChatModal } from '@/components/chat/CreateChatModal';

export function ChatListSidebar() {
  const { activeChatId, setActiveChatId } = useChatStore();
  const { data: chatData, loading: isChatLoading, runAsync: fetchChatsAsync } = useRequest(imApi.getJoinedChats);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [syncingChatId, setSyncingChatId] = useState<string | null>(null);

  return (
    <div className="flex flex-col h-full w-full bg-zinc-50/50">
      <div className="flex h-12 items-center justify-between px-4 border-b border-zinc-200/50">
        <h2 className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
          <MessageSquare className="h-4 w-4" />
          协作群聊
        </h2>
        <Button variant="ghost" size="icon" className="h-7 w-7 text-zinc-500" onClick={() => setIsModalOpen(true)}>
          <Plus className="h-4 w-4" />
        </Button>
      </div>
      <div className="p-3 border-b border-zinc-200/50">
        <div className="relative">
          <Search className="absolute left-2.5 top-2 h-4 w-4 text-zinc-400" />
          <input type="text" placeholder="搜索群聊..." className="w-full rounded-md border border-zinc-200 bg-white pl-9 pr-3 py-1.5 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition-all" />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-2 space-y-1">
        {isChatLoading ? (
          <>
            <div className="animate-pulse flex items-center space-x-3 rounded-lg bg-zinc-200/50 p-2">
              <div className="h-8 w-8 rounded-md bg-zinc-300" />
              <div className="flex-1 space-y-2">
                <div className="h-3 w-24 rounded bg-zinc-300" />
                <div className="h-2 w-32 rounded bg-zinc-200" />
              </div>
            </div>
          </>
        ) : chatData?.items && chatData.items.length > 0 ? (
          <>
            {syncingChatId && (
              <div className="flex animate-pulse cursor-wait items-center space-x-3 rounded-lg p-2 bg-blue-50/50 border border-blue-100">
                <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-blue-200 text-blue-600">
                  <Loader2 className="h-4 w-4 animate-spin" />
                </div>
                <div className="flex-1 truncate text-sm font-medium text-blue-700">正在同步新群聊...</div>
              </div>
            )}

            {chatData.items.map((chat) => (
              <div
                key={chat.chatId}
                onClick={() => setActiveChatId(chat.chatId)}
                className={`flex cursor-pointer items-center space-x-3 rounded-lg p-2 transition-colors ${
                  activeChatId === chat.chatId
                    ? 'bg-blue-100/50 text-blue-700 shadow-sm border border-blue-200/50'
                    : 'hover:bg-zinc-100 text-zinc-700 border border-transparent'
                }`}
              >
                <div
                  className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md font-bold ${
                    activeChatId === chat.chatId ? 'bg-blue-600 text-white' : 'bg-zinc-200 text-zinc-500'
                  }`}
                >
                  {chat.name.charAt(0)}
                </div>
                <div className="flex-1 truncate text-sm font-medium">{chat.name}</div>
              </div>
            ))}
          </>
        ) : (
          <div className="text-center pt-10 space-y-2">
            <p className="text-xs text-zinc-400">当前没有被 Agent 赋能的群聊</p>
            <Button variant="outline" size="sm" className="text-xs h-7" onClick={() => setIsModalOpen(true)}>
              发起新群聊
            </Button>
          </div>
        )}
      </div>

      <CreateChatModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onSuccess={async (newChatId) => {
          let maxRetries = 5;
          while (maxRetries > 0) {
            await new Promise((resolve) => setTimeout(resolve, 1500));
            try {
              const freshData = await fetchChatsAsync();
              const isSyncCompleted = freshData?.items.some((chat) => chat.chatId === newChatId);
              if (isSyncCompleted) {
                setActiveChatId(newChatId);
                setIsModalOpen(false);
                break;
              }
            } catch (e) {
              console.warn('轮询拉取失败...');
            }
            maxRetries--;
          }
          setIsModalOpen(false);
        }}
      />
    </div>
  );
}