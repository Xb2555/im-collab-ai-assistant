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
  X, StopCircle, Send, History, TerminalSquare, ChevronDown, ChevronUp //  新增 Chevron
} from 'lucide-react';
import { toast } from 'sonner'; //  修复：引入新版弹窗
import confetti from 'canvas-confetti';
import { CreateChatModal } from '@/components/chat/CreateChatModal';
import { InviteMemberModal } from '@/components/chat/InviteMemberModal';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { plannerApi } from '@/services/api/planner';
import { useTaskStore } from '@/store/useTaskStore';
import { useAgentStream } from '@/hooks/useAgentStream';
import { DocPreviewCard } from '@/components/chat/DocPreviewCard';
import { PptPreviewCard } from '@/components/chat/PptPreviewCard';
import { getBaseUrl } from '@/services/api/client'; // ✨ 引入 getBaseUrl
import type { RuntimeArtifactVO, RuntimeStepVO } from '@/types/api';
import { motion } from 'framer-motion'; // ✨ 新增：用于丝滑的进度条动画

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
    setTaskRuntime,
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
// 1. 修复全局工作台级 SSE 监听
  useEffect(() => {
    if (!accessToken) return;
    
    const ctrl = new AbortController();
    const connectGlobalSSE = async () => {
      try {
        // ✨ 在这里拼接 getBaseUrl()
        await fetchEventSource(`${getBaseUrl()}/api/planner/tasks/events/stream?activeOnly=true`, {
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

// ✨ 1. 新增全局操作锁，防止手抖双击
  const [isActionLoading, setIsActionLoading] = useState(false);

  // ✨ 2. 升级版 handleCommand：加入防抖锁与自动自愈逻辑
  const handleCommand = async (
    action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL' | 'RETRY_FAILED' | 'RESUME', 
    customFeedback?: string,
    extraPayload?: { artifactPolicy?: 'AUTO' | 'EDIT_EXISTING' | 'CREATE_NEW' | 'KEEP_EXISTING_CREATE_NEW', targetArtifactId?: string }
  ) => {
    // 如果没有任务，或者正在处理中，直接拦截（防抖）
    if (!activeTaskId || isActionLoading) return;
    
    setIsActionLoading(true); // 开启锁
    if (action === 'CANCEL') setIsCancelling(true);
    
    try {
      const newPreview = await plannerApi.executeCommand(activeTaskId, {
        action,
        feedback: customFeedback || (action === 'REPLAN' ? replanFeedback.trim() : undefined),
        version: runtimeTask?.version ?? planPreview?.version,
        ...extraPayload
      });
      setPlanPreview(newPreview);
      
      if (action === 'REPLAN') { setIsReplanningMode(false); setReplanFeedback(''); }
      if (action === 'RETRY_FAILED') setRetryFeedback('');
      if (action === 'RESUME') setClarifyAnswer('');
      
} catch (e: unknown) { // ✨ 修复 1：将 any 替换为 unknown，符合 TS 规范
      const errorMessage = e instanceof Error ? e.message : String(e);
      
      if (errorMessage === 'VERSION_CONFLICT') {
        // 核心修复：遇到冲突不要只弹窗，强制拉取最新状态让 UI 自愈！
        toast.info('状态已更新', { description: '正在为您同步最新任务状态...' });
        try {
           const freshRuntime = await plannerApi.getTaskRuntime(activeTaskId);
           setTaskRuntime(freshRuntime);
        } catch (_ignore) {} // ✨ 修复 2：用下划线前缀或直接留空变量，消除未使用警告
      } else {
        toast.error('操作失败', { description: errorMessage });
      }
    } finally {
      if (action === 'CANCEL') setIsCancelling(false);
      setIsActionLoading(false); // 释放锁
    }
  };

  // ✨ 场景 F：闭环交付，推送到 IM
  const handleDeliver = async () => {
    if (!activeTaskId || !activeChatId || !taskRuntime?.artifacts) return;
    
    // 1. 撒花庆祝
    confetti({
      particleCount: 150,
      spread: 70,
      origin: { y: 0.6 },
      colors: ['#6353AC', '#9F9DF3', '#C9EBCA', '#D5D6F2'],
      zIndex: 9999,
    });

    try {
      // 2. 构造发送到群里的富文本/普通文本总结
      const docLink = taskRuntime.artifacts.find(a => a.type === 'DOC')?.url || '';
      const pptLink = taskRuntime.artifacts.find(a => a.type === 'PPT')?.url || '';
      
      const deliverText = `🎉 【Agent-Pilot 工作汇报】任务已完成！\n\n` +
        `📝 项目文档：${docLink || '暂无'}\n` +
        `📊 汇报演示：${pptLink || '暂无'}\n\n` +
        `💡 您可以通过上方的链接直接预览或编辑。如有新需求，请随时在群内唤醒我。`;

      // 3. 调 IM 接口发消息
      await imApi.sendMessage({
        chatId: activeChatId,
        text: deliverText,
        idempotencyKey: crypto.randomUUID(),
      });
      
      // 4. 清理工作台，功成身退
      setTimeout(() => {
        alert('🎉 总结与交付成功！Agent 已将成果同步至飞书协作群。');
        clearTask(); // 关闭当前任务，将工作台置于待命状态
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
        await fetchEventSource(`${getBaseUrl()}/api/im/chats/${activeChatId}/messages/stream`, {
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
                        {/* 父容器加上 min-w-0 防止 flex 子项撑破 */}
                        <div className={`flex flex-col space-y-1 min-w-0 ${isMe ? 'items-end' : 'items-start'}`}>
                          <div className={`rounded-2xl px-3.5 py-2 text-sm shadow-sm leading-relaxed whitespace-pre-wrap break-words break-all ${isMe ? 'bg-zinc-800 text-white rounded-tr-sm' : 'bg-white border border-zinc-200 text-zinc-800 rounded-tl-sm'}`}>
                            {msg.content}
                          </div>
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
{/* ============================================================== */}
          {/* 🌟 重构后的全新任务工作台 (Dashboard Right Panel)                */}
          {/* ============================================================== */}
          <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4 bg-[#FAFAFC]">
            {!activeTaskId || !runtimeTask ? (
              // 规划中的骨架屏或空状态
              isPlanning ? (
                <div className="flex flex-col gap-4 animate-pulse mt-2">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="h-8 w-8 rounded-full bg-blue-100 flex items-center justify-center">
                      <Bot className="h-4 w-4 text-blue-600 animate-bounce" />
                    </div>
                    <div className="flex flex-col gap-2 w-full">
                      <div className="h-4 w-1/3 bg-zinc-200 rounded"></div>
                      <div className="h-2 w-1/4 bg-zinc-200 rounded"></div>
                    </div>
                  </div>
                  <div className="h-32 w-full bg-zinc-100 rounded-xl"></div>
                </div>
              ) : (
                <div className="flex flex-col items-center justify-center h-full space-y-3 text-center text-zinc-400">
                  <Bot className="h-8 w-8 opacity-50" />
                  <p className="text-sm">在左侧输入指令，唤醒 Agent</p>
                </div>
              )
            ) : (
              <div className="animate-in fade-in slide-in-from-bottom-4 duration-500 flex flex-col gap-4">
                
                {/* 🔴 【第一层】：全局任务头部 (包含风险提示与更新时间) */}
                <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm relative overflow-hidden">
                  <div className="flex items-start justify-between gap-3">
                    {/* 标题与呼吸红点 */}
                    <div className="flex items-center gap-2 flex-1">
                      <h3 className="text-base font-extrabold text-zinc-800 leading-snug">
                        {runtimeTask.title || planPreview?.title || 'Agent 任务规划'}
                      </h3>
                      {/* ✨ 找回：需求用户干预时的呼吸红点 */}
                      {runtimeTask.needUserAction && (
                         <span className="relative flex h-2.5 w-2.5 shrink-0 mt-0.5">
                           <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-rose-400 opacity-75"></span>
                           <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-rose-500"></span>
                         </span>
                      )}
                    </div>
                    
                    <div className="flex flex-col items-end gap-1.5 shrink-0">
                      <div className="flex items-center gap-1.5">
                        {(() => {
                          const { label, style } = getStatusDisplay(runtimeTask.status || runtimeTask.currentStage);
                          return <span className={`text-[10px] font-bold px-2.5 py-1 rounded-full border ${style}`}>{label}</span>;
                        })()}
                        {/* ✨ 叉号就在这里！找回并加强叉号逻辑 */}
                        {(runtimeActions?.canCancel || ['PLANNING', 'CLARIFYING', 'WAITING_APPROVAL'].includes(runtimeTask.status)) && (
                          <Button 
                            variant="ghost" 
                            size="icon" 
                            className="h-6 w-6 text-zinc-400 hover:text-red-600 hover:bg-red-50 rounded-full transition-colors ml-1" 
                            onClick={() => {
                              // 如果后端支持取消，调正常接口
                              if (runtimeActions?.canCancel) {
                                handleCommand('CANCEL');
                              } else {
                                // 如果后端不支持取消（卡死了），前端强制清空并卸载工作台
                                clearTask();
                                toast.info('任务已强制关闭');
                              }
                            }}
                            disabled={isCancelling}
                            title="中止或关闭当前任务"
                          >
                            {isCancelling ? <Loader2 className="w-3.5 h-3.5 animate-spin text-red-500" /> : <X className="w-4 h-4" />}
                          </Button>
                        )}
                      </div>
                      {/* ✨ 找回：时间感 (最近更新时间) */}
                      <span className="text-[9px] font-mono text-zinc-400">
                        {runtimeTask.updatedAt ? new Date(runtimeTask.updatedAt).toLocaleTimeString() : new Date().toLocaleTimeString()}
                      </span>
                    </div>
                  </div>
                  
                  <p className="text-xs text-zinc-500 mt-2 leading-relaxed">
                    {runtimeTask.goal || planPreview?.summary || '已收到您的意图，正在执行'}
                  </p>
                  
                  {/* ✨ 找回：风险与关注点 (Agent 的免责边界) */}
                  {runtimeTask.riskFlags && runtimeTask.riskFlags.length > 0 && (
                    <div className="mt-3 bg-amber-50/50 border border-amber-200/60 rounded-lg p-3">
                      <div className="text-xs font-bold text-amber-600 flex items-center gap-1.5 mb-1.5">
                        <AlertCircle className="w-3.5 h-3.5" /> 风险与关注点
                      </div>
                      <ul className="list-disc list-inside space-y-1 text-amber-700/80 text-[11px] ml-1">
                        {runtimeTask.riskFlags.map((risk: string, i: number) => (
                          <li key={i}>{risk}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {(runtimeTask.progress ?? 0) > 0 && (
                    <div className="w-full h-1 bg-zinc-100 rounded-full overflow-hidden mt-4">
                      <div className="h-full bg-blue-600 transition-all duration-500 ease-out" style={{ width: `${runtimeTask.progress}%` }} />
                    </div>
                  )}
                </div>

                {/* 🔴 【第二层】：人工干预与决策层 (置顶，无嵌套) */}
                {runtimeTask.status !== 'COMPLETED' && runtimeActions && (
                  <div className="flex flex-col">
                    {/* 失败重试 */}
                    {runtimeTask.status === 'FAILED' && (
                      <div className="bg-[#FF9BB3]/10 border border-[#FF9BB3]/30 rounded-xl p-4 flex flex-col gap-3">
                         <div className="text-sm font-bold text-[#D04568] flex items-center gap-1.5"><AlertCircle className="h-4 w-4" /> 任务执行受阻</div>
                         <textarea value={retryFeedback} onChange={(e) => setRetryFeedback(e.target.value)} placeholder="请描述调整建议..." className="w-full text-xs p-3 rounded-lg border border-[#FF9BB3]/20 outline-none resize-none bg-white" rows={2} />
                         <Button size="sm" className="bg-[#D04568] hover:bg-[#b03a58] text-white w-full" onClick={() => handleCommand('RETRY_FAILED', retryFeedback)}><RefreshCw className="h-3.5 w-3.5 mr-1.5" /> 提交建议并重试</Button>
                      </div>
                    )}
                    
                    {/* 追问 (CLARIFYING) */}
                    {runtimeTask.status === 'CLARIFYING' && (
                      <div className="bg-blue-50/50 border border-blue-200 rounded-xl p-4 flex flex-col gap-3 shadow-sm">
                        <div className="text-sm font-bold text-blue-700 flex items-center gap-1.5"><Bot className="h-4 w-4" /> 需要您的进一步确认</div>
                        {clarificationQuestions.map((q, idx) => <p key={idx} className="text-xs text-blue-900 bg-white p-2.5 rounded-md border border-blue-100">{q}</p>)}
                        
                        {/* ✨ 找回：能力提示 (让用户知道自己能输入什么) */}
                        {/* 把原来的 taskRuntime 换成 planPreview */}
                        {planPreview?.capabilityHints && planPreview.capabilityHints.length > 0 && (
                          <div className="text-[11px] text-blue-600/70 bg-blue-100/40 p-2 rounded-md border border-blue-100/50 leading-relaxed">
                            <span className="font-bold block mb-0.5 text-blue-500">💡 Agent 提示：</span>
                            {planPreview.capabilityHints.join(' ')}
                          </div>
                        )}

                        <textarea value={clarifyAnswer} onChange={(e) => setClarifyAnswer(e.target.value)} placeholder="请输入回答..." className="w-full text-xs p-3 rounded-lg border border-blue-200 outline-none resize-none bg-white focus:border-blue-500" rows={2} disabled={isActionLoading} />
                        <Button 
  size="sm" 
  className="bg-blue-600 hover:bg-blue-700 text-white w-full transition-all duration-300 relative overflow-hidden" 
  disabled={!clarifyAnswer.trim() || isActionLoading} 
  onClick={() => {
    toast.success('已收到您的补充', { description: 'Agent 正在为您重新推演执行计划...' });
    handleCommand('RESUME', clarifyAnswer);
  }}
>
  {isActionLoading ? (
    <span className="flex items-center gap-2">
      <Loader2 className="animate-spin h-4 w-4" /> 
      🤖 正在火速规划中...
    </span>
  ) : (
    '提交回答'
  )}
  {/* 极简的扫光特效 */}
  {isActionLoading && <div className="absolute inset-0 -translate-x-full animate-[shimmer_1.5s_infinite] bg-gradient-to-r from-transparent via-white/20 to-transparent" />}
</Button>
                      </div>
                    )}

                    {/* 待确认 (WAITING_APPROVAL) */}
                    {runtimeTask.status === 'WAITING_APPROVAL' && (
                      <div className="bg-white border-2 border-blue-500/20 rounded-xl p-4 flex flex-col gap-3 shadow-sm">
                        <div className="text-sm font-bold text-blue-800 flex justify-between items-center">
                          <span className="flex items-center gap-1.5"><CheckCircle2 className="h-4 w-4 text-blue-600" /> 计划已就绪</span>
                        </div>
                        {isReplanningMode ? (
                          <>
                            <textarea value={replanFeedback} onChange={(e) => setReplanFeedback(e.target.value)} placeholder="请输入调整意见..." className="text-xs p-3 rounded-lg border border-zinc-200 outline-none bg-zinc-50" rows={2} />
                            <div className="flex gap-2">
                              <Button size="sm" variant="outline" className="flex-1" onClick={() => setIsReplanningMode(false)}>取消</Button>
                              <Button size="sm" className="flex-1 bg-blue-600 text-white" disabled={!replanFeedback.trim()} onClick={() => handleCommand('REPLAN')}>提交调整</Button>
                            </div>
                          </>
                        ) : (
                          <div className="grid grid-cols-2 gap-2">
                            {runtimeActions.canConfirm && <Button size="sm" className="bg-blue-600 hover:bg-blue-700 text-white shadow-md h-10" onClick={() => handleCommand('CONFIRM_EXECUTE')}><Play className="h-4 w-4 mr-1.5" /> 确认执行</Button>}
                            {runtimeActions.canReplan && <Button size="sm" variant="outline" className="h-10 text-blue-700 border-blue-200 hover:bg-blue-50" onClick={() => setIsReplanningMode(true)}><RefreshCw className="h-4 w-4 mr-1.5" /> 重新规划</Button>}
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                )}

                {/* 🔴 【第三层】：当前计划执行大盘 (Stepper + Loading + 丰满元数据) */}
                {runtimeSteps.length > 0 && (
                  <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm relative overflow-hidden">
                    <h4 className="text-[11px] font-bold text-zinc-400 uppercase tracking-wider mb-4">Execution Steps</h4>
                    <div className="space-y-4 relative before:absolute before:inset-0 before:ml-[11px] before:h-full before:w-[2px] before:bg-zinc-100">
                      {runtimeSteps.map((step: RuntimeStepVO, idx: number) => {
  // ✨ 终极防守逻辑：如果总任务已经完成，强制把所有步骤视为完成，无视后端的僵尸 RUNNING 状态
  const isTaskCompleted = runtimeTask.status === 'COMPLETED';
  const displayStatus = isTaskCompleted ? 'COMPLETED' : step.status;
  const isRunning = isRuntimeStepRunning(displayStatus); // 用新的状态去算
  
  // 原有的 getDuration 也要防守一下，如果没结束时间但任务完成了，用 updatedAt 兜底
  const duration = getDuration(step.startedAt, step.endedAt || (isTaskCompleted ? runtimeTask.updatedAt : null));
  
  return (
    <div key={step.stepId || idx} className="relative flex items-start gap-3">
      {/* 这里的图标颜色判定也要换成 displayStatus */}
      <div className={`flex items-center justify-center w-6 h-6 rounded-full border-[3px] border-white z-10 mt-0.5 ${displayStatus === 'COMPLETED' ? 'bg-zinc-800 text-white' : isRunning ? 'bg-blue-500 text-white' : displayStatus === 'FAILED' ? 'bg-red-500 text-white' : 'bg-zinc-200 text-zinc-400'}`}>
        {displayStatus === 'COMPLETED' ? <Check className="w-3.5 h-3.5" /> : isRunning ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : displayStatus === 'FAILED' ? <X className="w-3.5 h-3.5" /> : <CircleDashed className="w-3.5 h-3.5" />}
      </div>
                            
                            <div className="flex-1 pt-1 pb-3">
                              <div className="flex items-start justify-between gap-2">
                                <h4 className={`text-sm font-bold ${isRunning ? 'text-blue-600' : 'text-zinc-800'}`}>{step.name}</h4>
                                {/* ✨ 找回：产物类型小紫标 */}
                                {step.type && (
                                  <span className="text-[9px] font-bold text-indigo-500 bg-indigo-50/80 border border-indigo-100 px-1.5 py-0.5 rounded tracking-widest shrink-0">
                                    {step.type.replace('_', ' ')}
                                  </span>
                                )}
                              </div>
                              
                              {/* ✨ 找回：极客感十足的 Worker 和耗时 */}
                              <div className="flex items-center gap-3 mt-1.5 text-[10px] font-mono text-zinc-400">
                                {step.assignedWorker && (
                                  <span className="flex items-center gap-1 bg-zinc-100/70 px-1.5 py-0.5 rounded text-zinc-500">
                                    <Bot className="w-3 h-3" /> {step.assignedWorker}
                                  </span>
                                )}
                                {duration && (
                                  <span className="flex items-center gap-1">
                                    <History className="w-3 h-3" /> {duration}
                                  </span>
          )}
          
          {/* ✨ 新增榨干字段：重试次数 */}
  {step.retryCount > 0 && (
    <span className="flex items-center gap-1 bg-amber-50 text-amber-600 border border-amber-200 px-1.5 py-0.5 rounded">
      <RefreshCw className="w-3 h-3" /> 重试了 {step.retryCount} 次
    </span>
  )}
                              </div>

                              {/* ✨ 找回：执行摘要 (Agent 思维透明化) */}
                              {step.inputSummary && (
                                <p className="text-[11px] text-zinc-500 mt-2 leading-relaxed bg-zinc-50/50 p-2 rounded-md border border-zinc-100">
                                  {step.inputSummary}
                                </p>
        )}
        
        {/* ✨ 新增榨干字段：输出结果 (闭环体现) */}
{step.outputSummary && (
  <p className="text-[11px] text-emerald-600 mt-1.5 leading-relaxed bg-emerald-50/50 p-2 rounded-md border border-emerald-100">
    ✅ 成果：{step.outputSummary}
  </p>
)}
                              
                              {isRunning && (
  <div className="mt-3 p-3 bg-blue-50/50 border border-blue-100 rounded-lg relative overflow-hidden group">
    <div className="flex items-start justify-between mb-2">
      <span className="text-[11px] font-medium text-blue-700 flex items-center gap-1.5 leading-relaxed">
        <Bot className="w-4 h-4 animate-bounce text-blue-500"/> 
        {/* ✨ 俏皮话策略：针对 PPT 生成给足情绪价值 */}
        {step.type === 'PPT_CREATE' || step.type === 'PPT'
          ? '正在为您精雕细琢每一页幻灯片，大概需要1分钟，喝口水休息一下吧 ☕'
          : 'Agent 正在飞速执行作业中，请稍候...'}
      </span>
      <span className="text-[10px] text-blue-400 font-mono shrink-0 ml-2 mt-0.5">
        {step.progress > 0 ? `${step.progress}%` : '预计 60s'}
      </span>
    </div>

    {/* ✨ 伪进度条：利用 framer-motion 实现从 0 平滑到 90% 的长达 60秒 的动画 */}
    {/* ✨ 修复版伪进度条：无视后端的微小进度，强制缓慢平滑推进到 90%，直到后端给出 100% */}
    <div className="w-full h-1.5 bg-blue-200/50 rounded-full overflow-hidden shadow-inner mb-3">
      <motion.div 
        className="h-full bg-blue-500 relative"
        initial={{ width: `${step.progress || 0}%` }}
        // 如果后端进度大于 90 或者到达 100，直接用后端的；否则，统一缓慢跑向 90%
        animate={{ width: step.progress >= 90 ? `${step.progress}%` : "90%" }}
        // 如果已经完成，0.5秒顺滑到底；否则，不管当前在百分之几，都花 60 秒慢慢向 90% 爬升
        transition={{ duration: step.progress >= 100 ? 0.5 : 60, ease: "easeOut" }}
      >
        <div className="absolute top-0 left-0 w-full h-full bg-white/20 animate-[shimmer_1s_infinite]" />
      </motion.div>
    </div>

    {/* 下方骨架屏与中断按钮的布局 */}
    <div className="flex items-end justify-between mt-2">
      <div className="flex-1 space-y-1.5 opacity-40 mr-4">
        <div className="h-1.5 bg-blue-300 rounded w-full animate-pulse"></div>
        <div className="h-1.5 bg-blue-300 rounded w-5/6 animate-pulse" style={{ animationDelay: '150ms' }}></div>
      </div>
      
      {/* ✨ 补全缺失的“停止”按钮，并加入防误触的 Loading 锁 */}
      <Button 
        variant="ghost" 
        size="sm" 
        disabled={isCancelling}
        onClick={() => handleCommand('CANCEL')}
        className="h-6 text-[10px] px-2 text-red-500 hover:text-red-600 hover:bg-red-100 border border-transparent hover:border-red-200 transition-colors"
      >
        {isCancelling ? <Loader2 className="w-3 h-3 mr-1 animate-spin" /> : <StopCircle className="w-3 h-3 mr-1" />}
        停止执行
      </Button>
    </div>
  </div>
)}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}

                {/* 🔴 【第四层】：实质产物与迭代操作 */}
                {runtimeArtifacts.length > 0 && (
                  <div className="flex flex-col gap-3">
                    {runtimeArtifacts.map((artifact: RuntimeArtifactVO) => {
                      if (artifact.type === 'DOC') {
                        return <DocPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' || artifact.status === 'UPDATED' ? 'COMPLETED' : 'GENERATING'} docUrl={artifact.url} docTitle={artifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(feedback, policy) => handleCommand('REPLAN', feedback, { artifactPolicy: policy, targetArtifactId: artifact.artifactId })} />;
                      }
                      // 替换 Dashboard.tsx 原有的 PptPreviewCard 渲染
if (artifact.type === 'PPT') {
  return <PptPreviewCard 
    key={artifact.artifactId} 
    status={artifact.status === 'CREATED' || artifact.status === 'UPDATED' ? 'COMPLETED' : 'EXECUTING'} 
    pptUrl={artifact.url} 
    pptTitle={artifact.title} 
    canReplan={runtimeActions?.canReplan} 
    isReplanning={isActionLoading} 
    onReplan={(feedback, policy) => handleCommand('REPLAN', feedback, { artifactPolicy: policy, targetArtifactId: artifact.artifactId })}
    onInterrupt={() => handleCommand('CANCEL')} 
  />;
                      }
                      


                      return null;
                    })}
                  </div>
                )}

                {/* 交付按钮 */}
                {runtimeTask.status === 'COMPLETED' && (
                  <Button className="w-full h-10 font-bold bg-zinc-900 hover:bg-zinc-800 text-white shadow-md animate-in zoom-in duration-500" onClick={handleDeliver}>
                    <Send className="h-4 w-4 mr-2" /> 总结与交付群聊
                  </Button>
                )}

                {/* 🔴 【最底层】：被降级的极客日志 */}
                {taskRuntime?.events && taskRuntime.events.length > 0 && (
                  <div className="mt-4 border border-zinc-200 rounded-xl bg-[#1C1C1E] overflow-hidden shadow-inner">
                    <div 
                      className="px-3 py-2 bg-zinc-800/80 flex items-center justify-between cursor-pointer hover:bg-zinc-800 transition-colors"
                      onClick={() => setIsTerminalExpanded(!isTerminalExpanded)}
                    >
                      <div className="flex items-center gap-2 text-zinc-400 text-xs font-mono">
                        <TerminalSquare className="w-3.5 h-3.5" /> 
                        {isTerminalExpanded ? '执行追踪器' : '最后一条日志: ' + (taskRuntime.events[taskRuntime.events.length - 1]?.message.slice(0, 25) + '...')}
                      </div>
                      {isTerminalExpanded ? <ChevronUp className="w-4 h-4 text-zinc-500" /> : <ChevronDown className="w-4 h-4 text-zinc-500" />}
                    </div>
                    {isTerminalExpanded && (
                      <div className="p-3 max-h-48 overflow-y-auto font-mono text-[10px] space-y-1.5 bg-[#1C1C1E]">
                        {taskRuntime.events.map((e: any) => (
                          <div key={e.eventId} className="flex items-start gap-2 text-zinc-500">
                            <span className="text-zinc-600 mt-0.5">❯</span>
                            <span className={e.message.includes('ERROR') ? 'text-red-400' : 'text-zinc-400'}>{e.message}</span>
                          </div>
                        ))}
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
