// src/pages/Dashboard.tsx
import { useState, useEffect } from 'react';
import { useAuthStore } from '@/store/useAuthStore';
import { authApi } from '@/services/api/auth';
import { useTaskStore } from '@/store/useTaskStore';
import { plannerApi } from '@/services/api/planner';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { getBaseUrl } from '@/services/api/client';
import { Button } from '@/components/ui/button';
import { Bot, LogOut, History, X, Loader2 } from 'lucide-react';
import { ChatListSidebar } from '@/components/dashboard/ChatListSidebar';
import { ChatRoom } from '@/components/dashboard/ChatRoom';
import { AgentWorkspace } from '@/components/dashboard/AgentWorkspace'; // 👈 引入最后一块积木
import { useAgentStream } from '@/hooks/useAgentStream';

// 提取共用方法
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

export default function Dashboard() {
  const { user, accessToken, clearAuth } = useAuthStore();
  const { activeTaskId, setActiveTaskId } = useTaskStore();
  
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [historyTasks, setHistoryTasks] = useState<any[]>([]);
  const [isLoadingHistoryTasks, setIsLoadingHistoryTasks] = useState(false);

  useAgentStream();

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

  const handleLogout = async () => {
    try { await authApi.logout(); } catch (e) {} finally { clearAuth(); }
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
              {user?.avatarUrl ? <img src={user?.avatarUrl} alt="avatar" className="h-full w-full object-cover" /> : <div className="h-full w-full bg-zinc-200" />}
            </div>
            <Button variant="ghost" size="icon" className="text-zinc-500 hover:text-red-600" onClick={handleLogout} title="退出登录">
              <LogOut className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </header>

      <main className="flex flex-1 overflow-hidden">
        {/* 左侧积木：群聊列表 */}
        <aside className="w-72 shrink-0 border-r border-zinc-200 hidden md:block">
          <ChatListSidebar />
        </aside>

        {/* 中间积木：聊天室 */}
        <div className="hidden lg:flex flex-col flex-1 xl:max-w-[400px] border-r border-zinc-200 relative h-full">
          <ChatRoom onOpenHistory={loadHistoryTasks} />
        </div>

        {/* 右侧积木：智能工作台 */}
        <aside className="flex flex-1 lg:w-[360px] flex-col bg-zinc-50 shadow-[-4px_0_15px_-3px_rgba(0,0,0,0.02)] relative z-10 border-l border-zinc-200">
          <AgentWorkspace />
        </aside>
      </main>

      {/* 悬浮积木：历史抽屉 */}
      {isHistoryOpen && (
        <div className="fixed inset-0 z-[100] flex justify-end">
          <div className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm transition-opacity" onClick={() => setIsHistoryOpen(false)}></div>
          <div className="relative w-80 bg-zinc-50 h-full shadow-2xl border-l border-zinc-200 flex flex-col animate-in slide-in-from-right duration-300">
            <div className="flex items-center justify-between p-4 border-b border-zinc-200 bg-white">
              <h3 className="font-semibold text-zinc-800 flex items-center gap-2"><History className="h-4 w-4 text-blue-600" /> 历史任务记录</h3>
              <Button variant="ghost" size="icon" onClick={() => setIsHistoryOpen(false)} className="h-7 w-7 text-zinc-500 hover:text-zinc-800 hover:bg-zinc-100"><X className="h-4 w-4" /></Button>
            </div>
            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              {isLoadingHistoryTasks ? (
                <div className="flex flex-col items-center justify-center py-10 space-y-2 text-zinc-400"><Loader2 className="h-6 w-6 animate-spin" /><span className="text-xs">加载中...</span></div>
              ) : historyTasks.length === 0 ? (
                <div className="text-center text-xs text-zinc-400 py-10">暂无历史记录</div>
              ) : (
                historyTasks.map(t => (
                  <div key={t.taskId} onClick={() => { setActiveTaskId(t.taskId); setIsHistoryOpen(false); }} className="bg-white p-3 rounded-xl border border-zinc-200 cursor-pointer hover:border-blue-500 hover:shadow-md transition-all">
                    <div className="relative pr-2">
                      <div className="text-sm font-bold text-zinc-800 line-clamp-2 mb-2 leading-snug">{t.title}</div>
                      {t.needUserAction && <span className="absolute top-0 -right-1 flex h-2.5 w-2.5"><span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-rose-400 opacity-75"></span><span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-rose-500"></span></span>}
                    </div>
                    <div className="flex items-center justify-between mt-1">
                      {(() => { const { label, style } = getStatusDisplay(t.status); return <span className={`text-[10px] font-bold px-2 py-0.5 rounded border ${style}`}>{label}</span>; })()}
                      <span className="text-[10px] text-zinc-400">{new Date(t.createdAt).toLocaleDateString()}</span>
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