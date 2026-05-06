// src/components/dashboard/ChatRoom.tsx
import { useState, useEffect, useRef } from 'react';
import { useAuthStore } from '@/store/useAuthStore';
import { useChatStore } from '@/store/useChatStore';
import { useTaskStore } from '@/store/useTaskStore';
import { imApi } from '@/services/api/im';
import { plannerApi } from '@/services/api/planner';
import { getBaseUrl } from '@/services/api/client';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { Button } from '@/components/ui/button';
import { Loader2, UserPlus, Bot, History } from 'lucide-react';
import { InviteMemberModal } from '@/components/chat/InviteMemberModal';

// 提取出原有的 parseFeishuContent 辅助函数
const parseFeishuContent = (rawContent: string | null | undefined, msgType?: string) => {
  if (!rawContent) return '';
  if (msgType !== 'system') {
    try {
      const json = JSON.parse(rawContent);
      if (json.text) return json.text;
      return rawContent;
    } catch {
      return rawContent;
    }
  }

  const fallbackSystemText = (template?: string) => {
    if (!template) return '[系统消息] 系统通知';
    if (template.includes('group administrators')) return '[系统消息] 系统更新了管理员设置';
    if (template.includes('started the group chat')) return '[系统消息] 系统创建了群聊';
    if (template.includes('invited') && template.includes('to the group')) return '[系统消息] 系统更新了群成员';
    if (template.includes('revoked') || template.includes('removed from the group')) return '[系统消息] 系统调整了群成员';
    if (template.includes('Welcome to')) return '[系统消息] 欢迎加入群聊';
    return '[系统消息] 系统通知';
  };

  try {
    const json = JSON.parse(rawContent);
    if (json.text) return `[系统消息] ${json.text}`;

    const template: string | undefined = json.template;
    if (!template) return '[系统消息] 系统通知';

    const variables = json.variables && typeof json.variables === 'object' ? json.variables : {};

    let hasMeaningfulValue = false;
    const rendered = template.replace(/\{([^{}]+)\}/g, (_m: string, key: string) => {
      const fromVariables = variables?.[key];
      const fromTopLevel = json?.[key];
      const rawValue = fromVariables ?? fromTopLevel;

      let values: string[] = [];
      if (Array.isArray(rawValue)) {
        values = rawValue.filter((v: unknown) => typeof v === 'string' && v.trim()).map((v: string) => v.trim());
      } else if (typeof rawValue === 'string' && rawValue.trim()) {
        values = [rawValue.trim()];
      }

      if (values.length > 0) {
        hasMeaningfulValue = true;
        return values.join('、');
      }

      if (key === 'group_type') return '群聊';
      return '某人';
    });

    if (!hasMeaningfulValue) {
      return fallbackSystemText(template);
    }

    const localized = rendered
      .replace(' invited ', ' 邀请了 ')
      .replace(' to the group. New members can view all chat history.', ' 入群，新成员可查看全部聊天记录。')
      .replace(' revoked the group invitation, ', ' 撤销了群邀请，')
      .replace(' was removed from the group chat.', ' 已被移出群聊。')
      .replace(' started the group chat and assigned ', ' 创建了群聊，并指定 ')
      .replace(' as the group owner.', ' 为群主。')
      .replace(' added ', ' 将 ')
      .replace(' to group administrators.', ' 设为了群管理员。')
      .replace('Welcome to 群聊', '欢迎加入群聊');

    return `[系统消息] ${localized}`;
  } catch {
    return `[系统] ${rawContent}`;
  }
};

interface ChatRoomProps {
  onOpenHistory: () => void;
}

export function ChatRoom({ onOpenHistory }: ChatRoomProps) {
  const { user, accessToken } = useAuthStore();
  const { activeChatId } = useChatStore();
  const { setActiveTaskId, setPlanPreview, isPlanning, setIsPlanning } = useTaskStore();

  const [inputText, setInputText] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [messages, setMessages] = useState<any[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false);
  const [selectedMessageIds, setSelectedMessageIds] = useState<string[]>([]);  
  const abortControllerRef = useRef<AbortController | null>(null);

  // 1. 发送消息
  const handleSendMessage = async () => {
    if (!inputText.trim() || !activeChatId) return;
    try {
      setIsSending(true);
      await imApi.sendMessage({
        chatId: activeChatId,
        text: inputText.trim(),
        idempotencyKey: crypto.randomUUID(),
      });
      setInputText('');
    } catch (e: any) {
      alert('发送失败: ' + e.message);
    } finally {
      setIsSending(false);
    }
  };

  // 2. 唤醒 Agent
  const handleStartAgentPlan = async () => {
    if (!inputText.trim() || !activeChatId) return;
    try {
      setIsPlanning(true);
      const selectedTexts = messages
        .filter((msg) => selectedMessageIds.includes(msg.eventId))
        .map((msg) => `${msg.senderName}: ${msg.content}`);

      const preview = await plannerApi.createPlan({
        rawInstruction: inputText.trim(),
        workspaceContext: {
          chatId: activeChatId,
          selectionType: selectedTexts.length > 0 ? 'MESSAGE' : undefined,
          selectedMessages: selectedTexts.length > 0 ? selectedTexts : undefined,
        },
      });

      if (preview.transientReply || !preview.runtimeAvailable) {
        if (preview.assistantReply) {
          alert(`🤖 Agent 回复: ${preview.assistantReply}`);
        }
        setInputText('');
        setSelectedMessageIds([]);
        return;
      }

      setActiveTaskId(preview.taskId || null);
      setPlanPreview(preview);
      setInputText('');
      setSelectedMessageIds([]);
    } catch (e: any) {
      alert('唤醒 Agent 失败: ' + e.message);
    } finally {
      setIsPlanning(false);
    }
  };

  // 3. 聊天室历史记录与 SSE 流
  useEffect(() => {
    if (!activeChatId) return;

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;

    const connectSSE = async () => {
      setMessages([]);
      setIsLoadingHistory(true);

      try {
        const historyRes = await imApi.getChatHistory({
          containerIdType: 'chat',
          containerId: activeChatId,
          sortType: 'ByCreateTimeDesc',
          pageSize: 20,
          signal: ctrl.signal,
        });

        if (ctrl.signal.aborted) return;

        const formattedHistory = historyRes.items.reverse().map((item: any) => {
          return {
            eventId: item.messageId,
            senderOpenId: item.senderId,
            senderType: item.senderType,
            senderName: item.senderName,
            senderAvatar: item.senderAvatar,
            content: parseFeishuContent(item.content, item.msgType),
            createTime: item.createTime,
          };
        });
        setMessages(formattedHistory);
      } catch (err: any) {
        if (err.name !== 'CanceledError' && err.message !== 'canceled') {
          console.warn('拉取历史消息失败', err);
        }
      } finally {
        if (!ctrl.signal.aborted) {
          setIsLoadingHistory(false);
        }
      }

      if (ctrl.signal.aborted) return;

      try {
        await fetchEventSource(`${getBaseUrl()}/api/im/chats/${activeChatId}/messages/stream`, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: 'text/event-stream',
          },
          signal: ctrl.signal,
          async onopen(response) {
            if (!response.ok) throw new Error(`SSE 建立失败: ${response.status}`);
          },
          onmessage(event) {
            if (event.event === 'message') {
              try {
                const msgData = JSON.parse(event.data);
                if (msgData.state === 'connected') return;

                setMessages((prev) => {
                  if (prev.some((m) => m.eventId === msgData.eventId || m.messageId === msgData.messageId)) return prev;

                  const parsedMsg = {
                    ...msgData,
                    content: parseFeishuContent(msgData.content, msgData.messageType || msgData.msgType),
                  };
                  return [...prev, parsedMsg];
                });
              } catch (parseError) {
                console.debug('JSON解析失败:', parseError);
              }
            }
          },
        });
      } catch (err: unknown) {
        if (err instanceof Error && err.name !== 'AbortError') console.error('SSE 流断开:', err);
      }
    };

    connectSSE();

    return () => {
      ctrl.abort();
    };
  }, [activeChatId, accessToken]);

  return (
    <section className="flex flex-col flex-1 xl:max-w-[400px] border-r border-zinc-200 bg-white relative h-full">
      <div className="absolute inset-0 z-0 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px] opacity-30 pointer-events-none"></div>

      <div className="z-10 flex h-12 items-center justify-between border-b border-zinc-200 px-6 bg-white/80 backdrop-blur-sm shrink-0">
        <h2 className="text-sm font-semibold text-zinc-800">当前会话投影</h2>
        <div className="flex items-center gap-2">
          {activeChatId && (
            <>
              <button 
                onClick={onOpenHistory}
                className="flex items-center justify-center h-8 px-2.5 text-xs font-medium rounded-lg border border-zinc-200 bg-white text-zinc-600 gap-1.5 hover:bg-zinc-50 hover:text-zinc-900 transition-colors focus:ring-0 focus:outline-none outline-none"
              >
                <History className="h-4 w-4" />
                <span className="hidden xl:inline">历史任务</span>
              </button>
              
              <Button variant="ghost" size="sm" className="h-8 text-zinc-500 gap-1.5 hover:text-blue-600 hover:bg-blue-50" onClick={() => setIsInviteModalOpen(true)}>
                <UserPlus className="h-4 w-4" />
                <span className="hidden sm:inline">邀请成员</span>
              </Button>
            </>
          )}
        </div>
      </div>

      <div className="z-10 flex-1 overflow-y-auto p-6 space-y-4 flex flex-col">
        {!activeChatId ? (
          <div className="m-auto text-zinc-400 text-sm">请在左侧选择一个协作群聊以加载消息流</div>
        ) : isLoadingHistory ? (
          <div className="m-auto text-zinc-400 text-sm flex items-center gap-2">
            <Loader2 className="h-4 w-4 animate-spin" /> 正在拉取历史消息...
          </div>
        ) : messages.length === 0 ? (
          <div className="m-auto text-zinc-400 text-sm">暂无消息记录</div>
        ) : (
          messages.map((msg, index) => {
            const messageType = (msg.messageType || msg.msgType || '').toLowerCase();
            const isSystem = messageType === 'system' || (typeof msg.content === 'string' && msg.content.startsWith('[系统消息]'));
            const isBot = msg.senderType === 'app' || msg.senderOpenId?.includes('bot');
            const senderId = msg.senderOpenId || msg.senderId;
            const currentUserOpenId = (user as any)?.openId || (user as any)?.userId || (user as any)?.id;
            const isMeById = !!senderId && !!currentUserOpenId && senderId === currentUserOpenId;
            const isMeByName = !!user?.name && !!msg.senderName && msg.senderName === user.name;
            const isMe = !isSystem && (isMeById || isMeByName);
            const resolvedName = msg.senderName || (isMe ? user?.name : null);
            const displayName = isSystem
              ? '系统通知'
              : (isBot ? 'Agent Pilot' : (resolvedName || '未知成员'));

            return (
              <div key={index} className={`flex w-full ${isMe ? 'justify-end' : 'justify-start'} group mb-4`}>
                {/* 统一的 flex 容器，通过 reverse 自动处理左右对称 */}
                <div className={`flex items-start max-w-[90%] gap-2 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                  
                  {/* 1. 头像区域 */}
                  <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full shadow-sm overflow-hidden transition-all duration-200 ${isBot ? 'bg-blue-600 text-white' : 'bg-zinc-200 text-zinc-600'} ${selectedMessageIds.includes(msg.eventId) ? 'ring-2 ring-blue-500 ring-offset-2' : ''}`}>
                    {isBot ? (
                      <Bot className="h-4 w-4" />
                    ) : (msg.senderAvatar || (isMe ? user?.avatarUrl : null)) ? (
                      <img src={msg.senderAvatar || user?.avatarUrl} alt="avatar" className="h-full w-full object-cover" />
                    ) : (
                      <div className="flex h-full w-full items-center justify-center bg-blue-600 text-white">
                        <Bot className="h-4 w-4" />
                      </div>
                    )}
                  </div>

                  {/* 2. 名字与气泡区域 */}
                  <div className={`flex flex-col space-y-1 min-w-0 ${isMe ? 'items-end' : 'items-start'}`}>
                    <span className="text-[11px] font-medium text-zinc-400 px-1">{displayName}</span>
                    <div 
                      className={`rounded-2xl px-3.5 py-2 text-sm shadow-sm leading-relaxed whitespace-pre-wrap break-words break-all transition-all duration-200 cursor-pointer ${isMe ? 'bg-zinc-800 text-white rounded-tr-sm' : 'bg-white border border-zinc-200 text-zinc-800 rounded-tl-sm'} ${selectedMessageIds.includes(msg.eventId) ? 'ring-2 ring-blue-500 ring-offset-1 scale-[0.98] opacity-90' : 'group-hover:ring-1 group-hover:ring-blue-300'}`}
                      onClick={() => {
                        if (selectedMessageIds.includes(msg.eventId)) {
                          setSelectedMessageIds(prev => prev.filter(id => id !== msg.eventId));
                        } else {
                          setSelectedMessageIds(prev => [...prev, msg.eventId]);
                        }
                      }}
                    >
                      {msg.content}
                    </div>
                  </div>

                  {/* 3. 复选框：固定在气泡内侧 */}
                  <div className={`mt-5 flex items-center justify-center shrink-0 transition-all duration-200 ${selectedMessageIds.includes(msg.eventId) ? 'opacity-100 scale-100' : 'opacity-0 scale-95 group-hover:opacity-100 group-hover:scale-100'}`}>
                    <input
                      type="checkbox"
                      className="w-4 h-4 cursor-pointer accent-blue-600 rounded-sm"
                      checked={selectedMessageIds.includes(msg.eventId)}
                      onChange={(e) => {
                        if (e.target.checked) {
                          setSelectedMessageIds((prev) => [...prev, msg.eventId]);
                        } else {
                          setSelectedMessageIds((prev) => prev.filter((id) => id !== msg.eventId));
                        }
                      }}
                    />
                  </div>

                </div>
              </div>
            );
          })
        )}
      </div>

      <div className="z-10 border-t border-zinc-200 p-4 bg-zinc-50 shrink-0">
        <div className="flex flex-col gap-2">
          
          {/* ✨ 新增：上下文选中状态显性化提示条 */}
          {selectedMessageIds.length > 0 && (
            <div className="flex items-center justify-between bg-blue-50/80 border border-blue-200 rounded-xl px-3 py-2.5 animate-in fade-in slide-in-from-bottom-2">
              <span className="text-xs text-blue-700 flex items-center gap-1.5 font-medium">
                <Bot className="w-3.5 h-3.5" />
                已圈选 {selectedMessageIds.length} 条聊天记录，将作为上下文发送给 Agent
              </span>
              <button 
                onClick={() => setSelectedMessageIds([])} 
                className="text-[10px] font-medium text-blue-500 hover:text-blue-700 underline underline-offset-2 transition-colors"
              >
                取消选中
              </button>
            </div>
          )}

          <textarea
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSendMessage();
              }
            }}
            className="max-h-32 min-h-[44px] w-full resize-none rounded-xl border border-zinc-300 bg-white p-3 text-sm shadow-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition-all"
            placeholder="输入指令唤醒 Agent (提示：点击消息即可圈选上下文)..."
            rows={2}
            disabled={!activeChatId || isSending}
          />
          <div className="flex justify-end items-center gap-2">
            <Button onClick={handleSendMessage} disabled={!activeChatId || !inputText.trim() || isSending || isPlanning} variant="outline" className="h-8 rounded-lg px-4 shadow-sm text-zinc-600">
              {isSending ? '发送中' : '发送到群'}
            </Button>

            <Button onClick={handleStartAgentPlan} disabled={!activeChatId || !inputText.trim() || isSending || isPlanning} className="h-8 bg-blue-600 hover:bg-blue-700 text-white rounded-lg px-4 shadow-sm flex items-center gap-1.5">
              <Bot className="h-4 w-4" />
              {isPlanning ? 'Agent 规划中...' : '交由 Agent 办理'}
            </Button>
          </div>
        </div>
      </div>

      {activeChatId && <InviteMemberModal isOpen={isInviteModalOpen} onClose={() => setIsInviteModalOpen(false)} chatId={activeChatId} />}
    </section>
  );
}