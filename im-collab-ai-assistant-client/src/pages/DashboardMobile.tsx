// src/pages/DashboardMobile.tsx
import { useState } from 'react';
import { useChatStore } from '@/store/useChatStore';
import { useTaskStore } from '@/store/useTaskStore';
import { ChatListSidebar } from '@/components/dashboard/ChatListSidebar';
import { ChatRoomMobile } from '@/components/dashboard/ChatRoomMobile';
import { AgentWorkspaceMobile } from '@/components/dashboard/AgentWorkspaceMobile';
import { plannerApi } from '@/services/api/planner'; // ✨ 引入 api
import { MessageSquare, Bot, ChevronLeft, Activity, History, X, Loader2, UserPlus } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { InviteMemberModal } from '@/components/chat/InviteMemberModal'; // ✨ 引入邀请弹窗
import { useEffect } from 'react';
import { useAuthStore } from '@/store/useAuthStore';
import { useAgentStream } from '@/hooks/useAgentStream';
import { getBaseUrl } from '@/services/api/client';
import { fetchEventSource } from '@microsoft/fetch-event-source';
// 提取共用的状态转换方法 (和桌面端保持一致)
const getStatusDisplay = (status?: string) => {
  const s = status?.toUpperCase() || '';
  if (['INTAKE', 'INTENT_READY', 'PLANNING'].includes(s)) return { label: '规划中', style: 'bg-[#D5D6F2] text-[#6353AC] border-[#D5D6F2]' };
  if (['ASK_USER', 'CLARIFYING'].includes(s)) return { label: '待补充', style: 'bg-[#FF9BB3]/15 text-[#D04568] border-[#FF9BB3]/50' };
  if (['PLAN_READY', 'WAITING_APPROVAL'].includes(s)) return { label: '待确认', style: 'bg-[#9F9DF3] text-white border-[#9F9DF3] shadow-sm' };
  if (['IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(s)) return { label: '执行中', style: 'bg-[#6353AC] text-white border-[#6353AC] shadow-sm' };
  if (['COMPLETED', 'DONE', 'READY', 'CREATED'].includes(s)) return { label: '已完成', style: 'bg-[#C9EBCA] text-emerald-700 border-[#C9EBCA]' };
  if (['FAILED'].includes(s)) return { label: '已失败', style: 'bg-[#FF9BB3]/25 text-[#D04568] border-[#FF9BB3]/60' };
  if (['ABORTED', 'CANCELLED'].includes(s)) return { label: '已取消', style: 'bg-[#D5D6F2]/30 text-[#6353AC]/60 border-[#D5D6F2]/50' };
  return { label: '未知态', style: 'bg-zinc-100 text-zinc-500 border-zinc-200' };
};

export default function DashboardMobile() {
  const [activeTab, setActiveTab] = useState<'chat' | 'workspace'>('chat');
  const { activeChatId, setActiveChatId } = useChatStore();
  const { activeTaskId, setActiveTaskId, taskRuntime, isPlanning } = useTaskStore();
  const { accessToken } = useAuthStore(); // ✨ 补上 token 提取
  // ===== 👇 丢失的灵魂引擎代码 开始 👇 =====

  // 1. 核心：启动当前任务的实时 SSE 流 (就是它没加导致不刷新的！)
  useAgentStream();

  // 2. 恢复活跃任务逻辑 (刷新页面时不丢失当前任务)
  useEffect(() => {
    const recoverActiveTask = async () => {
      try {
        const res = await plannerApi.getActiveTasks(10);
        if (res && res.tasks && res.tasks.length > 0) {
          const closedTasks = JSON.parse(localStorage.getItem('closed_tasks') || '[]');
          const taskToRecover = res.tasks.find(t => t.status !== 'CANCELLED' && !closedTasks.includes(t.taskId));
          if (taskToRecover && !activeTaskId) {
            setActiveTaskId(taskToRecover.taskId);
          }
        }
      } catch (e) { console.debug('获取活跃任务失败'); }
    };
    if (!activeTaskId) recoverActiveTask();
  }, [activeTaskId, setActiveTaskId]);

  // 3. 全局工作台状态监听 (自动捕获后台新建的任务)
  useEffect(() => {
    if (!accessToken) return;
    const ctrl = new AbortController();
    const connectGlobalSSE = async () => {
      try {
        await fetchEventSource(`${getBaseUrl()}/planner/tasks/events/stream?activeOnly=true`, {
          method: 'GET',
          headers: { Authorization: `Bearer ${accessToken}`, Accept: 'text/event-stream' },
          signal: ctrl.signal,
          async onopen(response) {
            if (response.status === 401 || response.status === 403) {
              throw new Error('UNAUTHORIZED');
            }
          },
          onerror(err) {
            if (err.message === 'UNAUTHORIZED') throw err; 
            console.warn('全局流网络波动，等待自动重连...'); 
          },
          onmessage(event) {
            if (event.event === 'heartbeat') return;
            try {
              const data = JSON.parse(event.data);
              const closedTasks = JSON.parse(localStorage.getItem('closed_tasks') || '[]');
              
              // 💡 核心修复：不要直接使用组件里的 activeTaskId 和 setActiveTaskId
              // 而是用 .getState() 直接穿透 React 闭包，拿 Zustand 仓库里最新鲜的值！
              const currentActiveTaskId = useTaskStore.getState().activeTaskId;
              
              if (data.taskId && !currentActiveTaskId && !closedTasks.includes(data.taskId)) {
                 console.log('🔗 检测到全局新任务流，自动吸附到工作台:', data.taskId);
                 // 💡 同样使用 getState() 来调用方法
                 useTaskStore.getState().setActiveTaskId(data.taskId);
              }
            } catch (e) {}
          }
        });
      } catch (e) {
        console.warn('全局工作台 SSE 监听断开', e);
      }
    };
    connectGlobalSSE();
    return () => ctrl.abort();
  }, [accessToken]);

  // ===== 👆 丢失的灵魂引擎代码 结束 👆 =====

  // ✨ 历史记录相关的状态
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [historyTasks, setHistoryTasks] = useState<any[]>([]);
  const [isLoadingHistoryTasks, setIsLoadingHistoryTasks] = useState(false);
// ✨ 新增邀请弹窗状态
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false);
  const isTaskRunning = isPlanning || (taskRuntime?.task?.status && ['IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(taskRuntime.task.status));

  // ✨ 拉取历史记录的方法
  const loadHistoryTasks = async () => {
    setIsHistoryOpen(true);
    setIsLoadingHistoryTasks(true);
    try {
      const res = await plannerApi.getTasks(undefined, 20);
      setHistoryTasks(res.tasks || []);
    } catch (e) {
      console.error('获取历史任务失败', e);
    } finally {
      setIsLoadingHistoryTasks(false);
    }
  };

  return (
    <div className="flex flex-col h-[100dvh] w-full bg-zinc-50 overflow-hidden relative">
      
      {/* 顶部悬浮胶囊：任务执行中提示 */}
      <AnimatePresence>
        {isTaskRunning && activeTab === 'chat' && (
          <motion.div
            initial={{ y: -50, opacity: 0 }}
            animate={{ y: 16, opacity: 1 }}
            exit={{ y: -50, opacity: 0 }}
            className="absolute top-0 left-1/2 -translate-x-1/2 z-50"
            onClick={() => setActiveTab('workspace')}
          >
            <div className="flex items-center gap-2 bg-blue-600/95 backdrop-blur-md text-white px-4 py-2 rounded-full shadow-lg cursor-pointer">
              <Activity className="h-4 w-4 animate-pulse" />
              <span className="text-xs font-medium">Agent 正在执行任务...</span>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 主体内容区 */}
      <div className="flex-1 relative overflow-hidden bg-white">
        
        {/* 协作 Tab */}
        <div className={`absolute inset-0 flex flex-col transition-opacity duration-300 ${activeTab === 'chat' ? 'opacity-100 z-10' : 'opacity-0 z-0 pointer-events-none'}`}>
          <div className="flex-1 overflow-hidden relative">
            <div className="absolute inset-0">
              <ChatListSidebar />
            </div>

            <AnimatePresence>
              {activeChatId && (
                <motion.div
                  initial={{ x: '100%' }}
                  animate={{ x: 0 }}
                  exit={{ x: '100%' }}
                  transition={{ type: 'spring', damping: 25, stiffness: 200 }}
                  className="absolute inset-0 z-20 bg-white flex flex-col shadow-xl"
                >
                  {/* ✨ 修复后的移动端群聊 Header */}
<div className="flex items-center justify-between h-14 border-b border-zinc-200 px-2 shrink-0 bg-zinc-50/80 backdrop-blur-sm">
  <div className="flex items-center">
    <button 
      onClick={() => setActiveChatId(null)}
      className="p-2 text-zinc-600 hover:bg-zinc-200 rounded-full transition-colors"
    >
      <ChevronLeft className="h-6 w-6" />
    </button>
    <span className="font-medium text-base text-zinc-800 ml-1">当前会话群聊</span>
  </div>
  
  {/* ✨ 恢复历史记录和邀请成员按钮 */}
  <div className="flex items-center gap-1 pr-2">
    <button 
      onClick={loadHistoryTasks}
      className="p-2 text-zinc-600 hover:bg-zinc-200 rounded-full transition-colors"
      title="历史任务"
    >
      <History className="h-5 w-5" />
    </button>
    <button 
      onClick={() => setIsInviteModalOpen(true)}
      className="p-2 text-zinc-600 hover:bg-zinc-200 rounded-full transition-colors"
      title="邀请成员"
    >
      <UserPlus className="h-5 w-5" />
    </button>
  </div>
</div>
                  <div className="flex-1 overflow-hidden relative">
                    {/* ✨ 修复点：将真实的拉取方法传给 ChatRoom */}
                    <ChatRoomMobile 
                      onOpenHistory={loadHistoryTasks} 
                      onSwitchToWorkspace={() => setActiveTab('workspace')} 
                    />
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>

        {/* 工作台 Tab */}
        <div className={`absolute inset-0 flex flex-col bg-[#FAFAFC] transition-opacity duration-300 ${activeTab === 'workspace' ? 'opacity-100 z-10' : 'opacity-0 z-0 pointer-events-none'}`}>
          <div className="flex items-center justify-center h-12 border-b border-zinc-200 shrink-0 bg-white">
            <span className="font-semibold text-sm text-zinc-800">Agent 工作台</span>
          </div>
          <div className="flex-1 overflow-hidden relative">
            <AgentWorkspaceMobile />
          </div>
        </div>
      </div>

      {/* 底部 Tab Bar */}
      <div className="h-[60px] pb-safe shrink-0 border-t border-zinc-200 bg-white flex items-center justify-around px-4 z-30">
        <button 
          onClick={() => setActiveTab('chat')}
          className={`flex flex-col items-center gap-1 transition-colors ${activeTab === 'chat' ? 'text-blue-600' : 'text-zinc-400'}`}
        >
          <MessageSquare className={`h-6 w-6 ${activeTab === 'chat' ? 'fill-blue-50' : ''}`} />
          <span className="text-[10px] font-medium">协作</span>
        </button>

        <button 
          onClick={() => setActiveTab('workspace')}
          className={`flex flex-col items-center gap-1 transition-colors relative ${activeTab === 'workspace' ? 'text-blue-600' : 'text-zinc-400'}`}
        >
          {activeTaskId && activeTab !== 'workspace' && (
             <span className="absolute top-0 right-1 w-2.5 h-2.5 bg-red-500 border-2 border-white rounded-full animate-pulse" />
          )}
          <Bot className={`h-6 w-6 ${activeTab === 'workspace' ? 'fill-blue-50' : ''}`} />
          <span className="text-[10px] font-medium">工作台</span>
        </button>
      </div>

      {/* ✨ 修复点：新增移动端的历史记录抽屉 */}
      <AnimatePresence>
        {isHistoryOpen && (
          <>
            {/* 遮罩层 */}
            <motion.div 
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setIsHistoryOpen(false)}
              className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm z-40"
            />
            {/* 底部弹出的抽屉 */}
            <motion.div
              initial={{ y: '100%' }}
              animate={{ y: 0 }}
              exit={{ y: '100%' }}
              transition={{ type: 'spring', damping: 25, stiffness: 200 }}
              className="absolute bottom-0 left-0 right-0 h-[80vh] bg-zinc-50 rounded-t-2xl shadow-2xl z-50 flex flex-col"
            >
              <div className="flex items-center justify-between p-4 border-b border-zinc-200 bg-white rounded-t-2xl shrink-0">
                <h3 className="font-semibold text-zinc-800 flex items-center gap-2">
                  <History className="h-4 w-4 text-blue-600" /> 历史任务
                </h3>
                <button 
                  onClick={() => setIsHistoryOpen(false)} 
                  className="p-1 text-zinc-500 hover:bg-zinc-100 rounded-full"
                >
                  <X className="h-5 w-5" />
                </button>
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
                        setActiveTaskId(t.taskId); 
                        setIsHistoryOpen(false); 
                        setActiveTab('workspace'); // 点击历史任务后，自动跳转到工作台 Tab
                      }} 
                      className="bg-white p-3 rounded-xl border border-zinc-200 cursor-pointer active:scale-[0.98] transition-all shadow-sm"
                    >
                      <div className="relative pr-2">
                        <div className="text-sm font-bold text-zinc-800 line-clamp-2 mb-2 leading-snug">{t.title}</div>
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
                          return <span className={`text-[10px] font-bold px-2 py-0.5 rounded border ${style}`}>{label}</span>; 
                        })()}
                        <span className="text-[10px] text-zinc-400">{new Date(t.createdAt).toLocaleDateString()}</span>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </motion.div>
          </>
        )}
      </AnimatePresence>
{/* ✨ 在最后挂载邀请成员的弹窗 */}
      {activeChatId && (
        <InviteMemberModal 
          isOpen={isInviteModalOpen} 
          onClose={() => setIsInviteModalOpen(false)} 
          chatId={activeChatId} 
        />
      )}
    </div>
  );
}