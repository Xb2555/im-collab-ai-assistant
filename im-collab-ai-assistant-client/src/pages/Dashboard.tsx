// src/pages/Dashboard.tsx
import { useState, useEffect, useRef } from 'react';
import { useAuthStore } from '@/store/useAuthStore';
import { useChatStore } from '@/store/useChatStore';
import { authApi } from '@/services/api/auth';
import { imApi } from '@/services/api/im';
import { useRequest } from 'ahooks';
import { Button } from '@/components/ui/button';
import {
  MessageSquare, LayoutTemplate, Bot, Plus, Search, LogOut,
  Settings, PanelRightClose, Loader2, UserPlus, User,
  Play, Square, RefreshCw, CheckCircle2, CircleDashed, Check, AlertCircle,
  X, StopCircle, Send,
} from 'lucide-react';
import confetti from 'canvas-confetti';
import { CreateChatModal } from '@/components/chat/CreateChatModal';
import { InviteMemberModal } from '@/components/chat/InviteMemberModal';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { plannerApi } from '@/services/api/planner';
import { useTaskStore } from '@/store/useTaskStore';
import { useAgentStream } from '@/hooks/useAgentStream';
import { DocPreviewCard } from '@/components/chat/DocPreviewCard';
import { PptPreviewCard } from '@/components/chat/PptPreviewCard';
import type { RuntimeArtifactVO, RuntimeStepVO } from '@/types/api';


const parseFeishuContent = (rawContent: string | null | undefined, msgType?: string) => {
  if (!rawContent) return '';
  try {
    const json = JSON.parse(rawContent);
    if (json.text) return json.text;
    if (json.template) return `[系统消息] ${json.template.replace(/\{.*?\}/g, '某人')}`;
    return '[未知 JSON 格式]';
  } catch {
    let text = rawContent;
    if (msgType === 'system') {
      if (text.includes('started the group chat')) {
        text = text.replace('started the group chat, assigned', '创建了群聊，指定')
          .replace('as the group owner, and invited', '为群主，并邀请')
          .replace('to the group.', '入群。');
      }
      if (text.includes('added') && text.includes('to group administrators')) {
        text = text.replace('added', '将').replace('to group administrators.', '设为了群管理员。');
      }
      if (text.includes('Welcome to')) {
        text = text.replace('Welcome to', '欢迎来到');
      }
      return `[系统] ${text}`;
    }
    return text;
  }
};

const isRuntimeStepRunning = (status?: string) =>
  status === 'IN_PROGRESS' || status === 'EXECUTING' || status === 'RUNNING';

export default function Dashboard() {
  const { user, clearAuth } = useAuthStore();
  const { activeChatId, setActiveChatId } = useChatStore();
  const { data: chatData, loading: isChatLoading, runAsync: fetchChatsAsync } = useRequest(imApi.getJoinedChats);


  const [isModalOpen, setIsModalOpen] = useState(false);
  const [inputText, setInputText] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [syncingChatId, setSyncingChatId] = useState<string | null>(null);
  const [messages, setMessages] = useState<any[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  const abortControllerRef = useRef<AbortController | null>(null);
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false);

  const {
    activeTaskId,
    planPreview,
    taskRuntime,
    setActiveTaskId,
    setPlanPreview,
    clearTask,
    aiThinkingText,
    isStreaming,
  } = useTaskStore();

// ===  [纯新增] 刷新找回任务逻辑开始  ===
  useEffect(() => {
    const recoverActiveTask = async () => {
      try {
        const res = await plannerApi.getActiveTasks();
        if (res && res.tasks && res.tasks.length > 0) {
          // 找到最近的一个活跃任务，自动恢复到右侧工作台
          console.log('✅ 自动找回活跃任务:', res.tasks[0].taskId);
          setActiveTaskId(res.tasks[0].taskId);
        }
      } catch (e) {
        console.debug('未发现活跃任务，保持初始状态');
      }
    };
    
    // 如果页面加载时 activeTaskId 为空，尝试从后端恢复
    if (!activeTaskId) {
      recoverActiveTask();
    }
  }, [activeTaskId, setActiveTaskId]); 
  // ===  [纯新增] 刷新找回任务逻辑结束  ===

  const [selectedMessageIds, setSelectedMessageIds] = useState<string[]>([]);
  useAgentStream();
  const [isPlanning, setIsPlanning] = useState(false);
  const [isReplanningMode, setIsReplanningMode] = useState(false);
  const [replanFeedback, setReplanFeedback] = useState('');
  const [resumeFeedback, setResumeFeedback] = useState('');
  const [isResuming, setIsResuming] = useState(false);

  const runtimeTask = taskRuntime?.task;
  const runtimeActions = taskRuntime?.actions;
  const runtimeSteps = taskRuntime?.steps ?? [];
  const runtimeArtifacts = taskRuntime?.artifacts ?? [];

  const failedStep = runtimeSteps.find((s) => s.status === 'FAILED');
  const showClarifyPanel =
    !!runtimeTask &&
    (runtimeTask.status === 'CLARIFYING' || planPreview?.planningPhase === 'ASK_USER') &&
    !!runtimeActions?.canResume &&
    !!planPreview?.clarificationQuestions?.length;

  const handleResumeTask = async () => {
    if (!activeTaskId || !resumeFeedback.trim()) return;
    try {
      setIsResuming(true);
      const newPreview = await plannerApi.resumeTask(activeTaskId, {
        feedback: resumeFeedback.trim(),
        replanFromRoot: false,
      });
      setPlanPreview(newPreview);
      setResumeFeedback('');
    } catch (e: any) {
      alert('提交回答失败: ' + e.message);
    } finally {
      setIsResuming(false);
    }
  };

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

  const handleCommand = async (action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL' | 'RETRY_FAILED') => {
    if (!activeTaskId) return;
    try {
      const newPreview = await plannerApi.executeCommand(activeTaskId, {
        action,
        feedback: action === 'REPLAN' ? replanFeedback.trim() : undefined,
        version: runtimeTask?.version ?? planPreview?.version,
      });
      setPlanPreview(newPreview);
      if (action === 'REPLAN') {
        setIsReplanningMode(false);
        setReplanFeedback('');
      }
    } catch (e: any) {
      if (e.message === 'VERSION_CONFLICT') {
        alert('多端冲突：操作已在其他端完成，请等待界面刷新！');
      } else {
        alert('操作失败: ' + e.message);
      }
    }
  };

  const handleDeliver = async () => {
    if (!activeTaskId) return;
    confetti({
      particleCount: 150,
      spread: 70,
      origin: { y: 0.6 },
      colors: ['#3b82f6', '#10b981', '#f59e0b', '#ef4444'],
      zIndex: 9999,
    });
  };

  useEffect(() => {
    if (!activeChatId) return;

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;

    const token = useAuthStore.getState().accessToken;

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
        await fetchEventSource(`/api/im/chats/${activeChatId}/messages/stream`, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
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
          onerror(err) {
            console.error('SSE 发生异常:', err);
            throw err;
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
  }, [activeChatId]);

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch (e) {
      console.warn('后端登出失败，前端强制清理', e);
    } finally {
      clearAuth();
    }
  };

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

  return (
    <div className="flex h-screen w-full flex-col bg-zinc-50 overflow-hidden">
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-zinc-200 bg-white px-4 shadow-sm z-10">
        <div className="flex items-center space-x-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600 shadow-inner">
            <Bot className="h-5 w-5 text-white" />
          </div>
          <span className="text-lg font-bold tracking-tight text-zinc-900">Agent Pilot</span>
          <span className="rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-500 border border-zinc-200">Workspace</span>
        </div>

        <div className="flex items-center space-x-4">
          <div className="hidden sm:flex items-center space-x-2 text-sm text-zinc-600 pr-4 border-r border-zinc-200">
            <div className="h-2 w-2 rounded-full bg-green-500 animate-pulse" />
            <span>系统已连接</span>
          </div>

          <div className="flex items-center space-x-3">
            <span className="text-sm font-medium text-zinc-700">{user?.name}</span>
            <div className="h-8 w-8 overflow-hidden rounded-full border border-zinc-200 shadow-sm">
              {user?.avatarUrl ? (
                <img src={user?.avatarUrl} alt="avatar" className="h-full w-full object-cover" />
              ) : (
                <div className="h-full w-full bg-zinc-200" />
              )}
            </div>
            <Button variant="ghost" size="icon" className="text-zinc-500 hover:text-red-600" onClick={handleLogout} title="退出登录">
              <LogOut className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </header>

      <main className="flex flex-1 overflow-hidden">
        <aside className="w-72 shrink-0 flex-col border-r border-zinc-200 bg-zinc-50/50 hidden md:flex">
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
        </aside>

        <section className="hidden lg:flex flex-col flex-1 xl:max-w-[400px] border-r border-zinc-200 bg-white relative">
          <div className="absolute inset-0 z-0 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px] opacity-30 pointer-events-none"></div>

          <div className="z-10 flex h-12 items-center justify-between border-b border-zinc-200 px-6 bg-white/80 backdrop-blur-sm">
            <h2 className="text-sm font-semibold text-zinc-800">当前会话投影</h2>
            <div className="flex items-center gap-2">
              {activeChatId && (
                <Button variant="ghost" size="sm" className="h-8 text-zinc-500 gap-1.5 hover:text-blue-600 hover:bg-blue-50" onClick={() => setIsInviteModalOpen(true)}>
                  <UserPlus className="h-4 w-4" />
                  <span className="hidden sm:inline">邀请成员</span>
                </Button>
              )}
              <Button variant="ghost" size="icon" className="h-8 w-8 lg:hidden">
                <PanelRightClose className="h-4 w-4" />
              </Button>
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
                const isBot = msg.senderType === 'app' || msg.senderOpenId?.includes('bot');
                const isMe = msg.senderName ? msg.senderName === user?.name : false;
                const displayName = isBot ? 'Agent Pilot' : (msg.senderName || '系统通知');

                return (
                  <div key={index} className={`flex w-full ${isMe ? 'justify-end' : 'justify-start'} group relative`}>
                    <div className={`absolute top-2 ${isMe ? '-left-6' : '-right-6'} opacity-0 group-hover:opacity-100 transition-opacity ${selectedMessageIds.includes(msg.eventId) ? 'opacity-100' : ''}`}>
                      <input
                        type="checkbox"
                        className="w-4 h-4 cursor-pointer"
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
                    <div className={`flex max-w-[85%] gap-2 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                      <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full shadow-sm overflow-hidden ${isBot ? 'bg-blue-600 text-white' : 'bg-zinc-200 text-zinc-600'}`}>
                        {isBot ? (
                          <Bot className="h-4 w-4" />
                        ) : msg.senderAvatar ? (
                          <img src={msg.senderAvatar} alt="avatar" className="h-full w-full object-cover" />
                        ) : (
                          <User className="h-4 w-4" />
                        )}
                      </div>

                      <div className={`flex flex-col space-y-1 ${isMe ? 'items-end' : 'items-start'}`}>
                        <span className="text-[11px] font-medium text-zinc-400 px-1">{displayName}</span>
                        <div className={`rounded-2xl px-3.5 py-2 text-sm shadow-sm leading-relaxed whitespace-pre-wrap break-words ${isMe ? 'bg-zinc-800 text-white rounded-tr-sm' : 'bg-white border border-zinc-200 text-zinc-800 rounded-tl-sm'}`}>
                          {msg.content}
                        </div>
                      </div>
                    </div>
                  </div>
                );
              })
            )}
          </div>

          <div className="z-10 border-t border-zinc-200 p-4 bg-zinc-50">
            <div className="flex flex-col gap-2">
              <textarea
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleSendMessage();
                  }
                }}
                className="max-h-32 min-h-[44px] w-full resize-none rounded-xl border border-zinc-300 bg-white p-3 text-sm shadow-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500"
                placeholder="输入消息或指令唤醒 Agent (按 Enter 发送)..."
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
        </section>

        <aside className="flex flex-1 lg:w-[360px] flex-col bg-zinc-50 shadow-[-4px_0_15px_-3px_rgba(0,0,0,0.02)] relative z-10 border-l border-zinc-200">
          <div className="flex h-12 items-center justify-between px-4 border-b border-zinc-200 bg-white shrink-0">
            <h2 className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
              <LayoutTemplate className="h-4 w-4 text-blue-600" />
              任务工作台
            </h2>
            <Button variant="ghost" size="icon" className="h-7 w-7 text-zinc-500" onClick={clearTask}>
              <X className="h-4 w-4" />
            </Button>
          </div>

          <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4">
            {!activeTaskId || !runtimeTask ? (
              <div className="flex flex-col items-center justify-center h-48 space-y-3 text-center mt-10">
                <div className="rounded-full bg-zinc-200/50 p-3">
                  <Bot className="h-6 w-6 text-zinc-400" />
                </div>
                <p className="text-sm text-zinc-500">
                  当前暂无活跃任务
                  <br />
                  <span className="text-xs text-zinc-400">在输入框描述意图并点击"交由 Agent 办理"</span>
                </p>
              </div>
            ) : (
              <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
                {aiThinkingText && (
                  <div className="bg-blue-50/50 border border-blue-100 rounded-xl p-4 mb-4 text-sm text-zinc-700 leading-relaxed whitespace-pre-wrap font-mono relative overflow-hidden shadow-sm">
                    <div className="absolute top-0 left-0 w-1 h-full bg-blue-500 animate-pulse"></div>
                    {aiThinkingText}
                    {isStreaming && <span className="inline-block w-2 h-4 bg-blue-600 ml-1 animate-pulse"></span>}
                  </div>
                )}

                <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm mb-4">
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center gap-2">
                      <span className="h-2 w-2 rounded-full bg-blue-500 animate-pulse" />
                      <h3 className="text-sm font-bold text-zinc-900">{runtimeTask.title || planPreview?.title || 'Agent 任务规划'}</h3>
                    </div>
                    <span className="text-[10px] font-medium text-zinc-400 bg-zinc-100 px-2 py-0.5 rounded-full">{runtimeTask.status || runtimeTask.currentStage}</span>
                  </div>
                  <p className="text-xs text-zinc-500 mb-3">{runtimeTask.goal || planPreview?.summary || '已收到您的意图，生成以下执行步骤'}</p>

                  {runtimeTask.status === 'FAILED' && (
                    <div className="mb-4 p-2 bg-red-50 rounded-md border border-red-200 text-xs text-red-700 flex gap-2 items-start">
                      <AlertCircle className="w-3.5 h-3.5 mt-0.5 shrink-0" />
                      <span>{failedStep?.outputSummary || '任务执行失败，请尝试重试或重新规划。'}</span>
                    </div>
                  )}

                  {planPreview?.clarificationAnswers && planPreview.clarificationAnswers.length > 0 && (
                    <div className="mb-4 p-2 bg-zinc-50 rounded-md border border-zinc-100 text-xs text-zinc-500 flex gap-2">
                      <span className="font-semibold text-zinc-700 shrink-0">您的意图补充:</span>
                      <span className="truncate">"{planPreview.clarificationAnswers.join(', ')}"</span>
                    </div>
                  )}

                  <div className="space-y-4 relative before:absolute before:inset-0 before:ml-2 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-gradient-to-b before:from-transparent before:via-zinc-200 before:to-transparent">
                    {runtimeSteps.map((step: RuntimeStepVO, idx: number) => (
                      <div key={step.stepId || idx} className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active">
                        <div className="flex items-center justify-center w-5 h-5 rounded-full border border-white bg-zinc-100 text-zinc-500 shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow-sm z-10">
                          {step.status === 'COMPLETED' || step.status === 'DONE' ? (
                            <CheckCircle2 className="w-3.5 h-3.5 text-green-500" />
                          ) : isRuntimeStepRunning(step.status) ? (
                            <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />
                          ) : step.status === 'FAILED' ? (
                            <AlertCircle className="w-3.5 h-3.5 text-red-500" />
                          ) : (
                            <CircleDashed className="w-3.5 h-3.5" />
                          )}
                        </div>
                        <div className="w-[calc(100%-2rem)] md:w-[calc(50%-1.5rem)] bg-white p-3 rounded-lg border border-zinc-200 shadow-sm relative">
                          {step.type && (
                            <span className="absolute top-3 right-3 text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wider bg-indigo-50 text-indigo-600 border border-indigo-100">{step.type}</span>
                          )}
                          <h4 className="text-xs font-semibold text-zinc-800 pr-12">{step.name}</h4>
                          {(step.outputSummary || step.inputSummary) && <p className="text-[10px] text-zinc-500 mt-1 line-clamp-2">{step.outputSummary || step.inputSummary}</p>}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>

                <div className="flex flex-col gap-4 mt-4 mb-6">
                  {Object.values(
  runtimeArtifacts.reduce((acc, artifact) => {
    // 始终让后面传来的同类型 artifact 覆盖前面的（保留最新状态）
    acc[artifact.type] = artifact;
    return acc;
  }, {} as Record<string, RuntimeArtifactVO>)
).map((artifact: RuntimeArtifactVO) => {
  if (artifact.type === 'DOC') {
    return (
      <DocPreviewCard 
        key={artifact.artifactId} 
        status={artifact.status === 'CREATED' ? 'COMPLETED' : 'GENERATING'} 
        docUrl={artifact.url} 
        docTitle={artifact.title} 
      />
    );
  }
  if (artifact.type === 'PPT') {
    return (
      <PptPreviewCard 
        key={artifact.artifactId} 
        status={artifact.status === 'CREATED' ? 'COMPLETED' : 'EXECUTING'} 
        pptUrl={artifact.url} 
        pptTitle={artifact.title} 
        onInterrupt={() => handleCommand('CANCEL')} 
      />
    );
  }
  return null;
})}
                </div>

                {runtimeTask.status === 'COMPLETED' ? (
                  <div className="mt-4 pt-4 border-t border-zinc-200">
                    <Button className="w-full h-10 font-bold bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white shadow-md animate-in zoom-in duration-500" onClick={handleDeliver}>
                      <Send className="h-4 w-4 mr-2" /> 总结与交付
                    </Button>
                  </div>
                ) : showClarifyPanel ? (
                  <div className="bg-blue-50 rounded-xl p-4 flex flex-col gap-3 border border-blue-200 shadow-inner animate-in slide-in-from-bottom-4">
                    <div className="flex items-center gap-2 text-blue-700 font-bold text-sm">
                      <Bot className="h-4 w-4" /> Agent 需要您的进一步确认
                    </div>

                    {planPreview?.clarificationQuestions?.map((question: string, idx: number) => (
                      <p key={idx} className="text-xs text-blue-900 bg-blue-100/60 p-2.5 rounded-md leading-relaxed border border-blue-200/50">
                        {question}
                      </p>
                    ))}

                    <textarea value={resumeFeedback} onChange={(e) => setResumeFeedback(e.target.value)} placeholder="请输入您的回答补充..." className="w-full text-xs p-3 rounded-lg border border-blue-200 focus:border-blue-500 outline-none resize-none bg-white shadow-sm transition-all" rows={3} />

                    <Button size="sm" className="w-full bg-blue-600 hover:bg-blue-700 text-white shadow-md" disabled={!resumeFeedback.trim() || isResuming} onClick={handleResumeTask}>
                      {isResuming ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : <Play className="h-3.5 w-3.5 mr-1" />}
                      提交回答，继续任务
                    </Button>
                  </div>
                ) : runtimeActions && (
                  <div className="bg-zinc-100 rounded-xl p-3 flex flex-col gap-3 border border-zinc-200 border-dashed transition-all">
                    <span className="text-xs text-zinc-500 font-medium text-center">人工干预控制台</span>

                    {isReplanningMode ? (
                      <div className="flex flex-col gap-2 animate-in slide-in-from-top-2">
                        <textarea value={replanFeedback} onChange={(e) => setReplanFeedback(e.target.value)} placeholder="请输入调整意见，例如：不需要总结，直接写大纲..." className="w-full text-xs p-2 rounded-md border border-zinc-300 focus:border-blue-500 outline-none resize-none" rows={2} />
                        <div className="flex gap-2">
                          <Button size="sm" variant="outline" className="flex-1" onClick={() => setIsReplanningMode(false)}>
                            取消
                          </Button>
                          <Button size="sm" className="flex-1 bg-blue-600 hover:bg-blue-700 text-white" disabled={!replanFeedback.trim()} onClick={() => handleCommand('REPLAN')}>
                            <RefreshCw className="h-3.5 w-3.5 mr-1" /> 提交调整
                          </Button>
                        </div>
                      </div>
                    ) : (
                      <div className="grid grid-cols-2 gap-2">
                        {runtimeActions.canConfirm && (
                          <Button size="sm" className="bg-zinc-900 hover:bg-zinc-800 text-white" onClick={() => handleCommand('CONFIRM_EXECUTE')}>
                            <Play className="h-3.5 w-3.5 mr-1" /> 确认执行
                          </Button>
                        )}
                        {runtimeActions.canReplan && (
                          <Button size="sm" variant="outline" className="text-zinc-700 hover:bg-zinc-200" onClick={() => setIsReplanningMode(true)}>
                            <RefreshCw className="h-3.5 w-3.5 mr-1" /> 重新规划
                          </Button>
                        )}
                        {runtimeActions.canRetry && (
                          <Button size="sm" variant="outline" className="text-amber-600 hover:bg-amber-50 hover:text-amber-700" onClick={() => handleCommand('RETRY_FAILED')}>
                            <RefreshCw className="h-3.5 w-3.5 mr-1" /> 重试失败
                          </Button>
                        )}
                        {runtimeActions.canCancel && (
                          <Button size="sm" variant="outline" className="text-red-600 hover:bg-red-50 hover:text-red-700 col-span-2 mt-1" onClick={() => handleCommand('CANCEL')}>
                            <X className="h-3.5 w-3.5 mr-1" /> 取消任务
                          </Button>
                        )}
                        {runtimeActions.canInterrupt && (
                          <Button size="sm" variant="destructive" className="col-span-2 mt-1" onClick={() => handleCommand('CANCEL')}>
                            <StopCircle className="h-3.5 w-3.5 mr-1" /> 停止生成
                          </Button>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        </aside>
      </main>

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

      {activeChatId && <InviteMemberModal isOpen={isInviteModalOpen} onClose={() => setIsInviteModalOpen(false)} chatId={activeChatId} />}
    </div>
  );
}
