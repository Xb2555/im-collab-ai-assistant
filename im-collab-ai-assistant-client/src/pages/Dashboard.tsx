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
  X, StopCircle, Send, History, TerminalSquare, ChevronDown, ChevronUp // ✨ 新增 Chevron
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

const isRuntimeStepRunning = (status?: string) =>
  status === 'IN_PROGRESS' || status === 'EXECUTING' || status === 'RUNNING';

// ✨ 新增：判断整个任务是否处于活跃思考/执行态（包含 PLANNING）
const isTaskActive = (status?: string) =>
  ['PLANNING', 'INTENT_READY', 'IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(status || '');


// ✨ 新增：前端状态映射与小红书清新配色方案 (已严格统一为3个字)
const getStatusDisplay = (status?: string) => {
  const s = status?.toUpperCase() || '';
  if (['INTAKE', 'INTENT_READY', 'PLANNING'].includes(s)) {
    return { label: '规划中', style: 'bg-[#D5D6F2] text-[#6353AC] border-[#D5D6F2]' };
  }
  if (['ASK_USER', 'CLARIFYING'].includes(s)) {
    return { label: '待补充', style: 'bg-[#FF9BB3]/15 text-[#D04568] border-[#FF9BB3]/50' };
  }
  if (['PLAN_READY', 'WAITING_APPROVAL'].includes(s)) {
    return { label: '待确认', style: 'bg-[#9F9DF3] text-white border-[#9F9DF3] shadow-sm' };
  }
  if (['IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(s)) {
    return { label: '执行中', style: 'bg-[#6353AC] text-white border-[#6353AC] shadow-sm' };
  }
  if (['COMPLETED', 'DONE', 'READY', 'CREATED'].includes(s)) {
    return { label: '已完成', style: 'bg-[#C9EBCA] text-emerald-700 border-[#C9EBCA]' };
  }
  if (['FAILED'].includes(s)) {
    // ✨ 修复：改为"已失败"3个字。背景改淡，文字使用高对比度的深玫红，彻底解决看不清的问题
    return { label: '已失败', style: 'bg-[#FF9BB3]/25 text-[#D04568] border-[#FF9BB3]/60' };
  }
  if (['ABORTED', 'CANCELLED'].includes(s)) {
    // ✨ 修复：改为"已取消"3个字。使用极简淡紫色
    return { label: '已取消', style: 'bg-[#D5D6F2]/30 text-[#6353AC]/60 border-[#D5D6F2]/50' };
  }
  return { label: '未知态', style: 'bg-zinc-100 text-zinc-500 border-zinc-200' };
};

// ✨ 新增：计算执行耗时的工具函数
const getDuration = (start?: string | null, end?: string | null) => {
  if (!start || !end) return null;
  const s = new Date(start).getTime();
  const e = new Date(end).getTime();
  const diff = Math.max(0, e - s);
  if (diff < 1000) return `${diff}ms`;
  return `${(diff / 1000).toFixed(1)}s`;
};

export default function Dashboard() {
  const { user, accessToken,clearAuth } = useAuthStore();
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

  // ✨ 新增：重试时的用户反馈输入
  const [retryFeedback, setRetryFeedback] = useState('');
  // ✨ 新增：处理 Agent 追问时的用户回答
  const [clarifyAnswer, setClarifyAnswer] = useState('');

  // ✨ 新增：日志终端是否展开
  const [isTerminalExpanded, setIsTerminalExpanded] = useState(false);
  // ✨ 新增：乐观取消状态锁（防止重复点击，并提供反馈）
  const [isCancelling, setIsCancelling] = useState(false);
  // ✨ 新增：历史任务抽屉状态
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [historyTasks, setHistoryTasks] = useState<any[]>([]);
  const [isLoadingHistoryTasks, setIsLoadingHistoryTasks] = useState(false);

  // ✨ 新增：加载历史任务的函数
  const loadHistoryTasks = async () => {
    setIsHistoryOpen(true);
    setIsLoadingHistoryTasks(true);
    try {
      // 默认拉取最新的20条任务
      const res = await plannerApi.getTasks(undefined, 20);
      setHistoryTasks(res.tasks || []);
    } catch (e) {
      console.error('获取历史任务失败', e);
    } finally {
      setIsLoadingHistoryTasks(false);
    }
  };
// === [修复] 刷新找回任务逻辑开始 ===
  useEffect(() => {
    const recoverActiveTask = async () => {
      try {
        const res = await plannerApi.getActiveTasks(10); // 多拉几个，防止第一个是已手动关闭的
        if (res && res.tasks && res.tasks.length > 0) {
          // 排除用户在本地手动点过 X（关闭）的完成/失败任务
          // 你可以利用 localStorage 存一个 closed_tasks 数组，这里为了演示简化，直接取第一个
          const closedTasks = JSON.parse(localStorage.getItem('closed_tasks') || '[]');
const taskToRecover = res.tasks.find(t => 
  t.status !== 'CANCELLED' && !closedTasks.includes(t.taskId)
);
          
          if (taskToRecover && !activeTaskId) {
            console.log('✅ 自动找回最新活跃任务:', taskToRecover.taskId, '状态:', taskToRecover.status);
            setActiveTaskId(taskToRecover.taskId);
          }
        }
      } catch (e) {
        console.debug('获取活跃任务失败，保持初始状态');
      }
    };
    
    if (!activeTaskId) {
      recoverActiveTask();
    }
  }, [activeTaskId, setActiveTaskId]);
  // === [修复] 刷新找回任务逻辑结束 ===

  // === [新增] 全局工作台级 SSE 监听（对应文档 7.2 和后端最新修复） ===
  useEffect(() => {
    if (!accessToken) return;
    
    const ctrl = new AbortController();
    const connectGlobalSSE = async () => {
      try {
        await fetchEventSource(`/api/planner/tasks/events/stream?activeOnly=true`, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${accessToken}`,
            Accept: 'text/event-stream',
          },
          signal: ctrl.signal,
          onmessage(event) {
            if (event.event === 'heartbeat') return;
            try {
              const data = JSON.parse(event.data);
              // ✨ 修复：读取本地黑名单，不要自动吸附用户刚刚关闭的失败/完成任务
              const closedTasks = JSON.parse(localStorage.getItem('closed_tasks') || '[]');
              
              if (data.taskId && !activeTaskId && !closedTasks.includes(data.taskId)) {
                 console.log('🔗 检测到全局新任务流，自动吸附到工作台:', data.taskId);
                 setActiveTaskId(data.taskId);
              }
              // 如果当前有任务，且状态变更，触发重拉（已有 useAgentStream 处理内部状态，这里可以配合弹 Toast 等）
            } catch (e) {}
          }
        });
      } catch (e) {
        console.warn('全局工作台 SSE 监听断开', e);
      }
    };

    connectGlobalSSE();
    return () => ctrl.abort();
  }, [accessToken, activeTaskId, setActiveTaskId]);
  // === [新增] 全局工作台级 SSE 监听结束 ===

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

      // ✨ 新增契约消费：拦截大模型的瞬时闲聊 (transientReply) 或无运行时的回复
      if (preview.transientReply || !preview.runtimeAvailable) {
        // 弹出 Agent 的闲聊回复（后续如果有需要，可以直接把这句话推入聊天流 messages 里面）
        if (preview.assistantReply) {
          alert(`🤖 Agent 回复: ${preview.assistantReply}`);
        }
        // 清空输入框并中止，绝对不要把它设为活跃任务
        setInputText('');
        setSelectedMessageIds([]);
        return;
      }

      // 只有真正的生成任务，才进入右侧工作台
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

// ✨ 替换为支持带反馈的调用
  // ✨ 加入 RESUME 动作
  const handleCommand = async (action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL' | 'RETRY_FAILED' | 'RESUME', customFeedback?: string) => {
    if (!activeTaskId) return;
    // ✨ 新增乐观 UI：如果点的是取消，立刻让界面进入取消等待态
    if (action === 'CANCEL') setIsCancelling(true);
    try {
      const newPreview = await plannerApi.executeCommand(activeTaskId, {
        action,
        feedback: customFeedback || (action === 'REPLAN' ? replanFeedback.trim() : undefined),
        version: runtimeTask?.version ?? planPreview?.version,
      });
      setPlanPreview(newPreview);
      
      // 清空对应输入框
      if (action === 'REPLAN') { setIsReplanningMode(false); setReplanFeedback(''); }
      if (action === 'RETRY_FAILED') setRetryFeedback('');
      if (action === 'RESUME') setClarifyAnswer(''); // ✨ 提交后清空追问框
      
    } catch (e: any) {
      if (e.message === 'VERSION_CONFLICT') {
        alert('多端冲突：操作已在其他端完成，请等待界面刷新！');
      } else {
        alert('操作失败: ' + e.message);
      }
    }finally {
      // ✨ 请求结束后释放锁
      if (action === 'CANCEL') setIsCancelling(false);
    }
  };

  const handleDeliver = async () => {
    if (!activeTaskId) return;
    
    // 1. 触发符合我们高级紫绿主题色的撒花动画
    confetti({
      particleCount: 150,
      spread: 70,
      origin: { y: 0.6 },
      colors: ['#6353AC', '#9F9DF3', '#C9EBCA', '#D5D6F2'], // ✨ 全局主题色撒花
      zIndex: 9999,
    });

    // 2. 预留调用后端交付接口的位置
    try {
      // TODO: 等后端准备好后，这里需要发送 DELIVER 指令
      // await plannerApi.executeCommand(activeTaskId, { action: 'DELIVER_AND_ARCHIVE' });
      
      // 临时用 Toast/Alert 模拟业务结果
      setTimeout(() => {
        alert('🎉 总结与交付成功！Agent 已将所有文档链接与成果卡片同步发送至飞书群聊。');
      }, 800);
    } catch (e: any) {
      alert('交付推送失败: ' + e.message);
    }
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

// ✨ 新增消费 JSON：解析 Agent 抛出的追问问题
  let clarificationQuestions: string[] = [];
  if (taskRuntime?.task?.status === 'CLARIFYING') {
    // 找到最近的一条 CLARIFICATION_REQUIRED 事件
    const clarifyEvent = taskRuntime.events?.slice().reverse().find((e: any) => e.type === 'CLARIFICATION_REQUIRED');
    if (clarifyEvent && clarifyEvent.message) {
      try {
        const parsed = JSON.parse(clarifyEvent.message);
        clarificationQuestions = Array.isArray(parsed) ? parsed : [clarifyEvent.message];
      } catch (e) {
        clarificationQuestions = [clarifyEvent.message];
      }
    } else {
      clarificationQuestions = ['Agent 需要您提供更多的上下文或参考资料。'];
    }
  }

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
                <>
                  
                  {/* ✨ 终极修复：抛弃 shadcn 复杂状态，使用原生 button + 纯净样式，彻底抹杀黑框 */}
                  <button 
                    onClick={loadHistoryTasks}
                    className="flex items-center justify-center h-8 px-2.5 text-xs font-medium rounded-lg border border-zinc-200 bg-white text-zinc-600 gap-1.5 hover:bg-zinc-50 hover:text-zinc-900 transition-colors focus:ring-0 focus:outline-none outline-none"
                  >
                    <History className="h-4 w-4" />
                    <span className="hidden xl:inline">历史任务</span>
                  </button>
                  
                  {/* 原有的邀请成员按钮 */}
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
                        ) : (msg.senderAvatar || (isMe ? user?.avatarUrl : null)) ? (
                          <img src={msg.senderAvatar || user?.avatarUrl} alt="avatar" className="h-full w-full object-cover" />
                        ) : (
                          <div className="flex h-full w-full items-center justify-center bg-blue-600 text-white">
                            <Bot className="h-4 w-4" />
                          </div>
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
              isPlanning ? (
                /* ✨ 优化体验：规划中的骨架屏占位，缓解等待焦虑 */
                <div className="flex flex-col gap-4 animate-pulse mt-2">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="h-8 w-8 rounded-full bg-blue-100 flex items-center justify-center">
                      <Bot className="h-4 w-4 text-blue-400 animate-bounce" />
                    </div>
                    <div className="flex flex-col gap-2">
                      <div className="h-4 w-32 bg-zinc-200 rounded"></div>
                      <div className="h-2 w-24 bg-zinc-100 rounded"></div>
                    </div>
                  </div>
                  <div className="h-24 w-full bg-zinc-100/80 rounded-xl border border-zinc-100"></div>
                  <div className="h-32 w-full bg-zinc-100/50 rounded-xl border border-zinc-100"></div>
                  <div className="flex justify-center mt-4 text-xs text-blue-400">
                    <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> Agent 正在深度分析您的意图，请稍候...
                  </div>
                </div>
              ) : (
                /* 原始空状态 */
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
              )
            ) : (
              <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
                {aiThinkingText && (
                  <div className="bg-blue-50/50 border border-blue-100 rounded-xl p-4 mb-4 text-sm text-zinc-700 leading-relaxed whitespace-pre-wrap font-mono relative overflow-hidden shadow-sm">
                    <div className="absolute top-0 left-0 w-1 h-full bg-blue-500 animate-pulse"></div>
                    {aiThinkingText}
                    {isStreaming && <span className="inline-block w-2 h-4 bg-blue-600 ml-1 animate-pulse"></span>}
                  </div>
                )}

                <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm mb-4 relative overflow-hidden">
                  {/* 顶部标题区 */}
                  <div className="flex items-start justify-between mb-2 gap-2">
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="h-2 w-2 rounded-full bg-blue-500 animate-pulse shrink-0" />
                      <h3 className="text-sm font-bold text-zinc-900 leading-snug">{runtimeTask.title || planPreview?.title || 'Agent 任务规划'}</h3>
                    </div>
                    {(() => {
                      const { label, style } = getStatusDisplay(runtimeTask.status || runtimeTask.currentStage);
                      return (
                        <span className={`text-[10px] font-bold px-2.5 py-0.5 rounded-full shrink-0 border ${style}`}>
                          {label}
                        </span>
                      );
                      })()}
                      {/* ✨ 消费字段：任务最后鲜活更新时间 */}
                    <span className="text-[9px] px-1 text-zinc-400 mt-1 ml-auto">
                      更新于 {new Date(runtimeTask.updatedAt).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}
                    </span>
                  </div>
                  
                  {/* ✨ 新增消费 JSON：全任务超细进度条 (丝滑过渡) */}
                  {(runtimeTask.progress ?? 0) > 0 && (
                    <div className="w-full h-1 bg-zinc-100 rounded-full overflow-hidden mb-3">
                      <div 
                        className="h-full bg-blue-500 transition-all duration-500 ease-out" 
                        style={{ width: `${runtimeTask.progress}%` }}
                      />
                    </div>
                  )}

                  {/* 风险标识 RiskFlags (保持不变) */}
                  {runtimeTask.riskFlags && runtimeTask.riskFlags.length > 0 && (
                    <div className="mb-3 p-2.5 bg-amber-50 rounded-lg border border-amber-200 text-xs text-amber-800 flex flex-col gap-1.5 shadow-sm">
                      <div className="font-bold flex items-center gap-1.5">
                        <AlertCircle className="w-4 h-4 text-amber-600" /> 风险与关注点
                      </div>
                      <ul className="list-disc pl-5 space-y-1">
                        {runtimeTask.riskFlags.map((risk: string, idx: number) => (
                          <li key={idx} className="opacity-90 leading-relaxed">{risk}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  <p className="text-[11px] text-zinc-400 mb-4 leading-relaxed line-clamp-2">{runtimeTask.goal || planPreview?.summary || '已收到您的意图，生成以下执行步骤'}</p>

                  {/* ✨ 升级版黑客终端：永久保留，根据状态自动静默与折叠，沉淀完整思考日志 */}
                  {taskRuntime?.events && taskRuntime.events.length > 0 && (
                    <div className="bg-[#1C1C1E] rounded-xl font-mono text-xs mb-6 shadow-xl border border-zinc-800 overflow-hidden animate-in fade-in slide-in-from-top-4 duration-500 transition-all">
                      
                      {/* 终端头部 */}
                      <div className="flex items-center justify-between bg-zinc-800/60 px-4 py-3 border-b border-zinc-700/50 relative overflow-hidden">
                        {/* 只有活跃时才显示流水跑马灯 */}
                        {isTaskActive(runtimeTask.status) && <div className="absolute top-0 left-0 w-full h-[1.5px] bg-gradient-to-r from-transparent via-blue-500 to-transparent animate-pulse"></div>}
                        
                        <div className="flex items-center gap-3 relative z-10">
                          {/* 机器人头像：完成时变灰，活跃时亮蓝 */}
                          <div className={`relative flex h-7 w-7 items-center justify-center rounded-md transition-colors ${isTaskActive(runtimeTask.status) ? 'bg-blue-500/20 text-blue-400 border border-blue-500/30' : 'bg-zinc-700/50 text-zinc-500 border border-zinc-600/50'}`}>
                            <Bot className="h-4 w-4" />
                            {isTaskActive(runtimeTask.status) && (
                              <span className="absolute -top-1 -right-1 flex h-2 w-2">
                                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
                                <span className="relative inline-flex rounded-full h-2 w-2 bg-blue-500"></span>
                              </span>
                            )}
                          </div>
                          
                          <div className="flex flex-col gap-0.5">
                            {/* 标题：根据状态动态改变文字和颜色 */}
                            <span className={`text-sm font-semibold flex items-center transition-colors ${isTaskActive(runtimeTask.status) ? 'text-zinc-200' : 'text-zinc-500'}`}>
                              {isTaskActive(runtimeTask.status) 
                                ? (runtimeTask.status === 'PLANNING' || runtimeTask.status === 'INTENT_READY' ? 'Agent 正在分析意图并规划路径' : 'Agent 正在驱动套件执行任务') 
                                : (runtimeTask.status === 'COMPLETED' ? 'Agent 任务执行完毕，日志已归档' : 'Agent 任务已终止，日志已归档')}
                              
                              {/* 跳动的省略号：只有活跃时才跳动 */}
                              {isTaskActive(runtimeTask.status) && (
                                <span className="flex gap-0.5 ml-2">
                                  <span className="h-1 w-1 bg-zinc-400 rounded-full animate-bounce" style={{ animationDelay: '0ms' }}></span>
                                  <span className="h-1 w-1 bg-zinc-400 rounded-full animate-bounce" style={{ animationDelay: '150ms' }}></span>
                                  <span className="h-1 w-1 bg-zinc-400 rounded-full animate-bounce" style={{ animationDelay: '300ms' }}></span>
                                </span>
                              )}
                            </span>
                          </div>
                        </div>
                        
                        {/* 右侧控制按钮区 */}
                        <div className="relative z-10 flex items-center gap-1">
                          {/* 展开/折叠按钮 */}
                          {(taskRuntime?.events?.length || 0) > 2 && (
                            <button 
                              onClick={() => setIsTerminalExpanded(!isTerminalExpanded)}
                              className="flex items-center text-[10px] text-zinc-400 hover:text-blue-400 mr-2 transition-colors px-2 py-1 rounded bg-zinc-800/50 outline-none focus:outline-none"
                            >
                              {isTerminalExpanded ? <><ChevronUp className="w-3 h-3 mr-1"/>收起</> : <><ChevronDown className="w-3 h-3 mr-1"/>展开完整日志</>}
                            </button>
                          )}

                          {/* 取消/中断按钮（只有活跃时才会渲染） */}
                          {runtimeActions?.canCancel && !runtimeActions?.canInterrupt && (
                            <Button size="sm" variant="ghost" disabled={isCancelling} className="h-7 text-xs text-zinc-400 hover:text-red-400 hover:bg-red-500/10 transition-colors" onClick={() => handleCommand('CANCEL')}>
                              {isCancelling ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" /> : <X className="h-3.5 w-3.5 mr-1" />}
                              {isCancelling ? '中止中...' : '取消任务'}
                            </Button>
                          )}
                          {runtimeActions?.canInterrupt && (
                            <Button size="sm" variant="ghost" disabled={isCancelling} className="h-7 text-xs text-zinc-400 hover:text-red-400 hover:bg-red-500/10 transition-colors" onClick={() => handleCommand('CANCEL')}>
                              {isCancelling ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" /> : <StopCircle className="h-3.5 w-3.5 mr-1" />}
                              {isCancelling ? '中止中...' : '强制中止'}
                            </Button>
                          )}
                        </div>
                      </div>
                      
                      {/* 终端内容区 */}
                      {/* 完成时：默认折叠状态下只展示最后 2 条，活跃时展示最后 4 条 */}
                      <div className={`p-4 flex flex-col gap-2 opacity-90 overflow-y-auto transition-all duration-300 ${isTerminalExpanded ? 'max-h-[400px]' : (isTaskActive(runtimeTask.status) ? 'max-h-[160px]' : 'max-h-[100px]')}`}>
                        {(isTerminalExpanded ? taskRuntime.events : taskRuntime.events.slice(isTaskActive(runtimeTask.status) ? -4 : -2)).map((e: any) => (
                          <div key={e.eventId} className={`break-words leading-relaxed flex items-start gap-2 ${isTaskActive(runtimeTask.status) ? 'text-green-400' : 'text-zinc-500'}`}>
                            <span className={`${isTaskActive(runtimeTask.status) ? 'text-blue-400' : 'text-zinc-600'} mt-[1px]`}>➜</span>
                            {/* 用户的干预日志特殊高亮 */}
                            <span className={e.type?.includes('USER') ? 'text-amber-300 font-bold' : (isTaskActive(runtimeTask.status) ? 'text-zinc-300' : 'text-zinc-400')}>
                              {e.message}
                            </span>
                          </div>
                        ))}
                        {/* 活跃时显示跳动光标，静默时不显示 */}
                        {isTaskActive(runtimeTask.status) && !isTerminalExpanded && <div className="animate-pulse text-blue-400 mt-1">█</div>}
                      </div>
                    </div>
                  )}

                                      {/* 模块 1：人工决策与干预台 (FAILED / CLARIFYING / WAITING_APPROVAL) */}
                  {runtimeTask.status !== 'COMPLETED' && runtimeActions && (runtimeTask.status === 'FAILED' || runtimeTask.status === 'CLARIFYING' || runtimeActions.canConfirm || runtimeActions.canReplan || runtimeActions.canResume) && (
                    <div className="bg-white rounded-xl p-4 flex flex-col gap-3 border border-zinc-200 shadow-sm mt-5 mb-2 transition-all animate-in slide-in-from-bottom-2">
                      
                      {/* 1.1 失败重试专用表单 */}
                      {runtimeTask.status === 'FAILED' ? (
                        <div className="flex flex-col gap-3 relative overflow-hidden">
                          <div className="text-sm font-bold text-[#D04568] flex items-center gap-1.5 mt-1">
                            <AlertCircle className="h-4 w-4 text-[#D04568] shrink-0" /> 
                            任务执行受阻，需要您的微调
                          </div>
                          <textarea 
                            value={retryFeedback} onChange={(e) => setRetryFeedback(e.target.value)} 
                            placeholder="请描述调整建议，例如：参数错误，请跳过大纲直接生成正文..." 
                            className="w-full text-xs p-3 rounded-lg border border-zinc-200 focus:border-[#FF9BB3] focus:ring-4 focus:ring-[#FF9BB3]/20 outline-none resize-none bg-[#FF9BB3]/5 transition-all placeholder:text-[#D04568]/40" 
                            rows={3} 
                          />
                          <div className="flex gap-2 items-center justify-end mt-1">
                            {runtimeActions.canCancel && (
                              <Button size="sm" variant="ghost" disabled={isCancelling} className="text-zinc-500 hover:text-red-600 hover:bg-red-50" onClick={() => handleCommand('CANCEL')}>取消任务</Button>
                            )}
                            <Button size="sm" className="bg-[#D04568] hover:bg-[#b03a58] text-white shadow-sm px-4" onClick={() => handleCommand('RETRY_FAILED', retryFeedback)}>
                              <RefreshCw className="h-3.5 w-3.5 mr-1.5" /> 提交建议并重试
                            </Button>
                          </div>
                        </div>
                      ) : 
                      
                      /* 1.2 待补充 (CLARIFYING) 的蓝色智能交互面板 */
                      runtimeTask.status === 'CLARIFYING' ? (
                        <div className="bg-blue-50 rounded-xl p-4 flex flex-col gap-3 border border-blue-200 shadow-inner animate-in slide-in-from-bottom-4">
                          <div className="flex items-center gap-2 text-blue-700 font-bold text-sm">
                            <Bot className="h-4 w-4" /> Agent 需要您的进一步确认
                          </div>
                          {clarificationQuestions.map((q, idx) => (
                            <p key={idx} className="text-xs text-blue-900 bg-blue-100/60 p-2.5 rounded-md leading-relaxed border border-blue-200/50">
                              {q}
                            </p>
                          ))}
                          <textarea 
                            value={clarifyAnswer} onChange={(e) => setClarifyAnswer(e.target.value)} 
                            placeholder="请输入您的回答补充..." className="w-full text-xs p-3 rounded-lg border border-blue-200 focus:border-blue-500 outline-none resize-none bg-white shadow-sm transition-all" rows={3} 
                          />
                          <div className="flex gap-2 items-center mt-1">
                            {runtimeActions.canCancel && (
                              <Button size="sm" variant="outline" disabled={isCancelling} className="flex-1 text-zinc-500 hover:text-red-600 hover:bg-red-50 border-zinc-200 bg-white" onClick={() => handleCommand('CANCEL')}>取消任务</Button>
                            )}
                            {runtimeActions.canResume && (
                              <Button size="sm" className="flex-[2] bg-blue-600 hover:bg-blue-700 text-white shadow-md" disabled={!clarifyAnswer.trim() || isCancelling} onClick={() => handleCommand('RESUME', clarifyAnswer)}>
                                {isCancelling ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : <Play className="h-3.5 w-3.5 mr-1" />}
                                提交回答，继续任务
                              </Button>
                            )}
                          </div>
                        </div>
                      ) : (

                      /* 1.3 协同指挥中心 (WAITING_APPROVAL) */
                      <div className="bg-blue-50 rounded-xl p-4 flex flex-col gap-4 border border-blue-200 shadow-sm animate-in slide-in-from-bottom-4">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2 text-blue-700 font-bold text-sm">
                            <div className="p-1.5 bg-blue-100 rounded-md text-blue-600 shrink-0 border border-blue-200/50 shadow-sm"><Settings className="h-4 w-4" /></div>
                            <span>Agent 协同指挥中心</span>
                          </div>
                          <span className="text-[10px] text-blue-500 bg-blue-100/50 px-2 py-0.5 rounded-full border border-blue-200/50">等待决策</span>
                        </div>
                        <p className="text-[11px] text-blue-800/80 leading-relaxed bg-white/50 p-2.5 rounded-lg border border-blue-100">
                          主人，初步执行计划已就绪。您可以直接 <span className="text-blue-700 font-bold">确认执行</span> 开启任务，或者点击 <span className="text-zinc-600 font-bold">重新规划</span> 让我按您的新想法调整。
                        </p>
                        {isReplanningMode ? (
                          <div className="flex flex-col gap-2 animate-in fade-in duration-300">
                            <textarea value={replanFeedback} onChange={(e) => setReplanFeedback(e.target.value)} placeholder="请输入调整意见..." className="w-full text-xs p-3 rounded-lg border border-blue-200 focus:border-blue-400 focus:ring-4 focus:ring-blue-500/10 outline-none resize-none bg-white shadow-inner" rows={2} />
                            <div className="flex gap-2">
                              <Button size="sm" variant="outline" className="flex-1 border-blue-200 text-blue-600 hover:bg-blue-100 bg-white" onClick={() => setIsReplanningMode(false)}>取消</Button>
                              <Button size="sm" className="flex-1 bg-blue-600 hover:bg-blue-700 text-white shadow-md" disabled={!replanFeedback.trim()} onClick={() => handleCommand('REPLAN')}><RefreshCw className="h-3.5 w-3.5 mr-1" /> 提交调整</Button>
                            </div>
                          </div>
                        ) : (
                          <div className="flex flex-col gap-2">
                            <div className="grid grid-cols-2 gap-2">
                              {runtimeActions.canConfirm && <Button size="sm" className="bg-blue-600 hover:bg-blue-700 text-white shadow-lg py-5 transition-transform active:scale-95" onClick={() => handleCommand('CONFIRM_EXECUTE')}><Play className="h-4 w-4 mr-2" /> 确认执行</Button>}
                              {runtimeActions.canReplan && <Button size="sm" variant="outline" className="border-blue-200 bg-white text-blue-700 hover:bg-blue-50 py-5 transition-transform active:scale-95" onClick={() => setIsReplanningMode(true)}><RefreshCw className="h-4 w-4 mr-2" /> 重新规划</Button>}
                            </div>
                            {runtimeActions.canCancel && <Button size="sm" variant="ghost" disabled={isCancelling} className="w-full text-zinc-400 hover:text-red-500 hover:bg-red-50 mt-1 h-8 text-[11px]" onClick={() => handleCommand('CANCEL')}>{isCancelling ? <Loader2 className="h-3 w-3 animate-spin mr-2" /> : <X className="h-3 w-3 mr-2" />}放弃当前任务</Button>}
                          </div>
                        )}
                      </div>
                      )}
                    </div>
                  )}

                  {/* 步骤条 Stepper */}
                  <div className="space-y-4 relative before:absolute before:inset-0 before:ml-[11px] before:h-full before:w-[2px] before:bg-gradient-to-b before:from-zinc-200 before:via-zinc-200 before:to-transparent mt-2">
                    {runtimeSteps.map((step: RuntimeStepVO, idx: number) => {
                      const duration = getDuration(step.startedAt, step.endedAt); // 计算耗时
                      
                      return (
                        <div key={step.stepId || idx} className="relative flex items-start gap-3 group">
                          {/* 状态圆形图标 */}
                          <div className="flex items-center justify-center w-6 h-6 rounded-full border-2 border-white bg-zinc-50 text-zinc-400 shrink-0 z-10 mt-0.5 shadow-sm">
                            {step.status === 'COMPLETED' || step.status === 'DONE' ? (
                              <CheckCircle2 className="w-4 h-4 text-green-500" />
                            ) : isRuntimeStepRunning(step.status) ? (
                              <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />
                            ) : step.status === 'FAILED' ? (
                              <AlertCircle className="w-3.5 h-3.5 text-red-500" />
                            ) : (
                              <CircleDashed className="w-3.5 h-3.5" />
                            )}
                          </div>
                          
                          {/* 步骤内容气泡 */}
                          {/* 步骤内容气泡：增加已完成状态的卡片质感，告别折叠错觉 */}
                          <div className={`flex-1 p-3 rounded-lg relative transition-all duration-300 ${
                            step.status === 'FAILED' ? 'bg-red-50 text-red-900 border border-red-100' : 
                            isRuntimeStepRunning(step.status) ? 'bg-blue-50/40 shadow-sm border border-blue-200/60' : 
                            (step.status === 'COMPLETED' || step.status === 'DONE') ? 'bg-white shadow-sm border border-emerald-100/60' : 
                            'bg-zinc-50/80 border border-zinc-200/50'
                          }`}>
                            {step.type && (
                              <span className="absolute top-3 right-3 text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wider bg-[#D5D6F2]/40 text-[#6353AC] border border-[#9F9DF3]/30">
                                {step.type.replace('_', ' ')}
                              </span>
                            )}
                            <h4 className="text-sm font-bold text-zinc-800 pr-16 leading-tight break-words">{step.name}</h4>
                            
                            {(step.outputSummary || step.inputSummary) && (
                              <p className={`text-[11px] mt-2 leading-relaxed break-words ${
                                step.status === 'FAILED' ? 'text-red-600' : 
                                (step.status === 'COMPLETED' || step.status === 'DONE') ? 'text-emerald-700/80' : 
                                'text-zinc-500/90'
                              }`}>
                                {step.outputSummary || step.inputSummary}
                              </p>
                            )}
                            
                            {/* ✨ 新增消费 JSON：步骤级微型进度条 (当该步骤正在执行时显示) */}
                            {isRuntimeStepRunning(step.status) && step.progress > 0 && (
                              <div className="mt-3 w-full h-1 bg-zinc-100 rounded-full overflow-hidden">
                                <div 
                                  className="h-full bg-indigo-400 transition-all duration-500 ease-out" 
                                  style={{ width: `${step.progress}%` }}
                                />
                              </div>
                            )}

                            {/* ✨ 新增消费 JSON：极客指标（执行引擎与耗时） */}
                            {(step.assignedWorker || duration || step.retryCount > 0) && (
                              <div className="mt-2.5 pt-2 border-t border-zinc-100/80 flex flex-wrap items-center gap-1.5">
                                {step.assignedWorker && (
                                  <span className="text-[10px] text-zinc-400 flex items-center gap-1 font-mono">
                                    <Bot className="w-3 h-3" /> {step.assignedWorker}
                                  </span>
                                )}
                                {duration && (
                                  <span className="text-[10px] text-zinc-400 bg-zinc-50 px-1.5 rounded border border-zinc-100 font-mono">
                                    ⏱ {duration}
                                  </span>
                                )}
                                {step.retryCount > 0 && (
                                  <span className="text-[10px] text-amber-600 font-medium bg-amber-50 px-1.5 py-0.5 rounded border border-amber-100">
                                    ↺ 重试 {step.retryCount} 次
                                  </span>
                                )}
                              </div>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
                  
                {/* ====== 🚨 核心 UX 优化：把交互面板提上来，紧贴步骤条，防止被超长文档遮挡 ====== */}
                  


                  {/* 模块 2：产物预览区域 (被放到了交互操作台下方) */}
                  <div className="flex flex-col gap-4 mt-4 mb-2">
                    {Object.values(
                      runtimeArtifacts.reduce((acc, artifact) => {
                        acc[artifact.type] = artifact;
                        return acc;
                      }, {} as Record<string, RuntimeArtifactVO>)
                    ).map((artifact: RuntimeArtifactVO) => {
                      if (artifact.type === 'DOC') return <DocPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' ? 'COMPLETED' : 'GENERATING'} docUrl={artifact.url} docTitle={artifact.title} />;
                      if (artifact.type === 'PPT') return <PptPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' ? 'COMPLETED' : 'EXECUTING'} pptUrl={artifact.url} pptTitle={artifact.title} onInterrupt={() => handleCommand('CANCEL')} />;
                      return null;
                    })}
                    
                    {/* 预测性骨架卡片 */}
                    {runtimeSteps.filter(step => isRuntimeStepRunning(step.status) && (step.type === 'DOC_CREATE' || step.type === 'PPT_CREATE')).filter(step => !runtimeArtifacts.some(a => a.type === (step.type === 'DOC_CREATE' ? 'DOC' : 'PPT'))).map(step => (
                        <div key={`skeleton-${step.stepId}`} className="border border-blue-100 bg-blue-50/30 rounded-xl p-4 flex flex-col gap-3 relative overflow-hidden animate-pulse">
                          <div className="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-transparent via-blue-400 to-transparent animate-pulse" />
                          <div className="flex items-center gap-3">
                            <div className="h-10 w-10 bg-blue-100 rounded-lg flex items-center justify-center text-blue-400"><Loader2 className="h-5 w-5 animate-spin" /></div>
                            <div className="flex flex-col gap-2 flex-1"><div className="h-4 bg-zinc-200 rounded w-1/3"></div><div className="h-3 bg-zinc-100 rounded w-2/3"></div></div>
                          </div>
                        </div>
                    ))}
                  </div>

                  {/* 模块 3：任务最终完成时的交付按钮 */}
                  {runtimeTask.status === 'COMPLETED' && (
                    <div className="mt-4 pt-4 border-t border-zinc-200 pb-4">
                      <Button className="w-full h-10 font-bold bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white shadow-md animate-in zoom-in duration-500" onClick={handleDeliver}>
                        <Send className="h-4 w-4 mr-2" /> 总结与交付
                      </Button>
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
      
      {/* ✨ 这就是你漏掉的：全局历史任务侧滑抽屉 */}
      {isHistoryOpen && (
        <div className="fixed inset-0 z-[100] flex justify-end">
          {/* 遮罩层，点击关闭抽屉 */}
          <div className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm transition-opacity" onClick={() => setIsHistoryOpen(false)}></div>
          
          {/* 右侧抽屉面板 */}
          <div className="relative w-80 bg-zinc-50 h-full shadow-2xl border-l border-zinc-200 flex flex-col animate-in slide-in-from-right duration-300">
            <div className="flex items-center justify-between p-4 border-b border-zinc-200 bg-white">
              <h3 className="font-semibold text-zinc-800 flex items-center gap-2">
                <History className="h-4 w-4 text-blue-600" /> 历史任务记录
              </h3>
              <Button variant="ghost" size="icon" onClick={() => setIsHistoryOpen(false)} className="h-7 w-7 text-zinc-500 hover:text-zinc-800 hover:bg-zinc-100">
                <X className="h-4 w-4" />
              </Button>
            </div>
            
            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              {isLoadingHistoryTasks ? (
                <div className="flex flex-col items-center justify-center py-10 space-y-2 text-zinc-400">
                  <Loader2 className="h-6 w-6 animate-spin" />
                  <span className="text-xs">加载中...</span>
                </div>
              ) : historyTasks.length === 0 ? (
                <div className="text-center text-xs text-zinc-400 py-10">暂无历史记录</div>
              ) : (
                historyTasks.map(t => (
                  <div 
                    key={t.taskId} 
                    onClick={() => { 
                      setActiveTaskId(t.taskId); // 点击卡片，把任务投影到工作台
                      setIsHistoryOpen(false);   // 收起抽屉
                    }} 
                    className="bg-white p-3 rounded-xl border border-zinc-200 cursor-pointer hover:border-blue-500 hover:shadow-md transition-all"
                  >
                    <div className="relative pr-2">
                      <div className="text-sm font-bold text-zinc-800 line-clamp-2 mb-2 leading-snug">
                        {t.title}
                      </div>
                      {/* ✨ 新增消费 JSON：如果需要用户干预 (needUserAction)，显示呼吸红点唤起注意 */}
                      {t.needUserAction && (
                         <span className="absolute top-0 -right-1 flex h-2.5 w-2.5">
                           <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-rose-400 opacity-75"></span>
                           <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-rose-500"></span>
                         </span>
                      )}
                    </div>
                    <div className="flex items-center justify-between mt-1">
                      {(() => {
                        const { label, style } = getStatusDisplay(t.status);
                        return (
                          <span className={`text-[10px] font-bold px-2 py-0.5 rounded border ${style}`}>
                            {label}
                          </span>
                        );
                      })()}
                      <span className="text-[10px] text-zinc-400">
                        {new Date(t.createdAt).toLocaleDateString()}
                      </span>
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

    </div>
  );
}
