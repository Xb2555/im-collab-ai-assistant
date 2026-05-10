// src/components/dashboard/AgentWorkspace.tsx
import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import {
  Bot, Loader2, X, AlertCircle, RefreshCw, CheckCircle2, Play,
  CircleDashed, Check, History, Send, TerminalSquare, ChevronDown, ChevronUp, StopCircle, Wand2, Sparkles
} from 'lucide-react';
import { toast } from 'sonner';
import confetti from 'canvas-confetti';
import { plannerApi } from '@/services/api/planner';
import { imApi } from '@/services/api/im';
import { useTaskStore } from '@/store/useTaskStore';
import { useChatStore } from '@/store/useChatStore';
import { DocPreviewCard } from '@/components/chat/DocPreviewCard';
import { PptPreviewCard } from '@/components/chat/PptPreviewCard';
import type { RuntimeArtifactVO, RuntimeStepVO } from '@/types/api';
import { motion } from 'framer-motion';

const getGreeting = () => {
  const hour = new Date().getHours();
  if (hour >= 6 && hour < 12) return { text: "早上好！Agent Pilot 已就绪，今天我们要搞定哪些大项目？", icon: "🌞" };
  if (hour >= 12 && hour < 14) return { text: "中午好！先去吃个好饭歇一歇，繁琐的任务随时交给我。", icon: "🍱" };
  if (hour >= 14 && hour < 18) return { text: "下午好！把枯燥的排版和梳理交给我，您可以喝杯咖啡啦~", icon: "☕" };
  if (hour >= 18 && hour < 24) return { text: "晚上好！还在忙碌吗？夜深了，让我来帮你加速收尾吧！", icon: "🌙" };
  return { text: "夜深了！哇，深夜还在奋斗，Agent 陪您一起随时待命。", icon: "🦉" };
};

const isRuntimeStepRunning = (status?: string) =>
  status === 'IN_PROGRESS' || status === 'EXECUTING' || status === 'RUNNING';

const getStatusDisplay = (status?: string) => {
  const s = status?.toUpperCase() || '';
  if (['INTAKE', 'INTENT_READY', 'PLANNING'].includes(s)) return { label: '规划中', style: 'bg-[#D5D6F2] text-[#6353AC] border-[#D5D6F2]' };
  // ✨ 新增：中断与重规划过程中的状态映射
  if (['INTERRUPTING'].includes(s)) return { label: '中断中', style: 'bg-amber-100 text-amber-600 border-amber-200 animate-pulse' };
  if (['REPLANNING'].includes(s)) return { label: '重新推演中', style: 'bg-[#D5D6F2] text-[#6353AC] border-[#D5D6F2] animate-pulse' };
  
  if (['ASK_USER', 'CLARIFYING'].includes(s)) return { label: '待补充', style: 'bg-[#FF9BB3]/15 text-[#D04568] border-[#FF9BB3]/50' };
  if (['PLAN_READY', 'WAITING_APPROVAL'].includes(s)) return { label: '待确认', style: 'bg-[#9F9DF3] text-white border-[#9F9DF3] shadow-sm' };
  if (['IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(s)) return { label: '执行中', style: 'bg-[#6353AC] text-white border-[#6353AC] shadow-sm' };
  if (['COMPLETED', 'DONE', 'READY', 'CREATED'].includes(s)) return { label: '已完成', style: 'bg-[#C9EBCA] text-emerald-700 border-[#C9EBCA]' };
  if (['FAILED'].includes(s)) return { label: '已失败', style: 'bg-[#FF9BB3]/25 text-[#D04568] border-[#FF9BB3]/60' };
  if (['ABORTED', 'CANCELLED'].includes(s)) return { label: '已取消', style: 'bg-[#D5D6F2]/30 text-[#6353AC]/60 border-[#D5D6F2]/50' };
  return { label: '未知态', style: 'bg-zinc-100 text-zinc-500 border-zinc-200' };
};

const getDuration = (start?: string | null, end?: string | null) => {
  if (!start || !end) return null;
  const s = new Date(start).getTime();
  const e = new Date(end).getTime();
  const diff = Math.max(0, e - s);
  if (diff < 1000) return `${diff}ms`;
  return `${(diff / 1000).toFixed(1)}s`;
};

export function AgentWorkspace() {
  const { activeChatId } = useChatStore();
  const {
    activeTaskId,
    setActiveTaskId, // ✨ 新增这一行：用于点击卡片切换任务
    planPreview,
    taskRuntime,
    setPlanPreview,
    setTaskRuntime,
    clearTask,
    isPlanning,
  } = useTaskStore();

  const [retryFeedback, setRetryFeedback] = useState('');
  const [clarifyAnswer, setClarifyAnswer] = useState('');
  const [isTerminalExpanded, setIsTerminalExpanded] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [isReplanningMode, setIsReplanningMode] = useState(false);
  const [replanFeedback, setReplanFeedback] = useState('');
  
  // ✨ 新增：中断并重规划的状态
  const [isInterruptingMode, setIsInterruptingMode] = useState(false);
  const [interruptFeedback, setInterruptFeedback] = useState('');
  
const [isActionLoading, setIsActionLoading] = useState(false);
  const [isDelivering, setIsDelivering] = useState(false);
  
  // ✨ 新增：记录最后交付的时间戳
  const [lastDeliveredTime, setLastDeliveredTime] = useState<string | null>(null);
  const [prevTaskId, setPrevTaskId] = useState(activeTaskId);

  // ✨ 核心逻辑 1：存放近期真实的 2 条历史任务
  const [recentTasks, setRecentTasks] = useState<any[]>([]);

  useEffect(() => {
    // 只有在空闲（待命大厅）状态下才拉取历史记录
    if (!activeTaskId && !isPlanning) {
      plannerApi.getTasks(undefined, 2).then(res => {
        setRecentTasks(res.tasks || []);
      }).catch(err => console.error("获取最近任务失败", err));
    }
  }, [activeTaskId, isPlanning]);

  // ✨ 修复 ESLint 报错：在渲染期直接重置，避免 useEffect 导致重复渲染
  if (activeTaskId !== prevTaskId) {
    setPrevTaskId(activeTaskId);
    setLastDeliveredTime(null);
  }
  const runtimeTask = taskRuntime?.task;
  const runtimeActions = taskRuntime?.actions;
  const runtimeSteps = taskRuntime?.steps ?? [];
  const runtimeArtifacts = taskRuntime?.artifacts ?? [];

  // ✨ 更新类型以支持 INTERRUPT_REPLAN
  const handleCommand = async (
    action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL' | 'RETRY_FAILED' | 'RESUME' | 'INTERRUPT_REPLAN', 
    customFeedback?: string,
    extraPayload?: { artifactPolicy?: 'AUTO' | 'EDIT_EXISTING' | 'CREATE_NEW' | 'KEEP_EXISTING_CREATE_NEW', targetArtifactId?: string, autoExecute?: boolean }
  ) => {
    if (!activeTaskId || isActionLoading) return;
    setIsActionLoading(true);
    if (action === 'CANCEL') setIsCancelling(true);
    
    // ✨ 匹配相应的反馈内容
    let finalFeedback = customFeedback;
    if (action === 'REPLAN') finalFeedback = replanFeedback.trim();
    if (action === 'INTERRUPT_REPLAN') finalFeedback = interruptFeedback.trim();

    try {
      const newPreview = await plannerApi.executeCommand(activeTaskId, {
        action,
        feedback: finalFeedback,
        version: runtimeTask?.version ?? planPreview?.version,
        ...extraPayload
      });
      setPlanPreview(newPreview);
      
      try {
        const freshRuntime = await plannerApi.getTaskRuntime(activeTaskId);
        setTaskRuntime(freshRuntime);
      } catch (err) {
        console.warn('主动同步状态失败', err);
      }
      
      if (action === 'REPLAN') { setIsReplanningMode(false); setReplanFeedback(''); }
      if (action === 'INTERRUPT_REPLAN') { setIsInterruptingMode(false); setInterruptFeedback(''); }
      if (action === 'RETRY_FAILED') setRetryFeedback('');
      if (action === 'RESUME') setClarifyAnswer('');
    } catch (e: unknown) {
      const errorMessage = e instanceof Error ? e.message : String(e);
      if (errorMessage === 'VERSION_CONFLICT') {
        toast.info('状态已更新', { description: '正在为您同步最新任务状态...' });
        try {
           const freshRuntime = await plannerApi.getTaskRuntime(activeTaskId);
           setTaskRuntime(freshRuntime);
        } catch (_ignore) {}
      } else {
        toast.error('操作失败', { description: errorMessage });
      }
    } finally {
      if (action === 'CANCEL') setIsCancelling(false);
      setIsActionLoading(false);
    }
  };

const handleDeliver = async () => {
    if (!activeTaskId || !activeChatId || !taskRuntime?.artifacts || isDelivering) return;
    setIsDelivering(true);
    confetti({ particleCount: 150, spread: 70, origin: { y: 0.6 }, colors: ['#6353AC', '#9F9DF3', '#C9EBCA', '#D5D6F2'], zIndex: 9999 });
    try {
      const docLink = taskRuntime.artifacts.find(a => a.type === 'DOC')?.url || '';
      const pptLink = taskRuntime.artifacts.find(a => a.type === 'PPT')?.url || '';
      // ✨ 修复文案：采用纯前端妥协版的第一人称视角
      const deliverText = `🎉 我已经让 Agent 完成了本次任务，这是最新的成果：\n\n📝 项目文档：${docLink || '暂无'}\n📊 汇报演示：${pptLink || '暂无'}\n\n💡 请大家查阅。如果有需要修改的地方，可以直接在群里@我，或者直接回复本条消息。`;
      await imApi.sendMessage({ chatId: activeChatId, text: deliverText, idempotencyKey: crypto.randomUUID() });
      toast.success('🎉 总结与交付成功！', { description: '成果已同步至飞书协作群。' });
      // ✨ 核心修复：不清除任务，而是记录当前任务的最后更新时间戳
      setLastDeliveredTime(runtimeTask?.updatedAt || null);
    } catch (e: any) {
      toast.error('交付推送失败', { description: e.message });
    } finally {
      setIsDelivering(false);
    }
  };

  let clarificationQuestions: string[] = [];
  if (taskRuntime?.task?.status === 'CLARIFYING') {
    const clarifyEvent = taskRuntime.events?.slice().reverse().find((e: any) => e.type === 'CLARIFICATION_REQUIRED');
    if (clarifyEvent && clarifyEvent.message) {
      try { clarificationQuestions = Array.isArray(JSON.parse(clarifyEvent.message)) ? JSON.parse(clarifyEvent.message) : [clarifyEvent.message]; }
      catch { clarificationQuestions = [clarifyEvent.message]; }
    } else { clarificationQuestions = ['Agent 需要您提供更多的上下文或参考资料。']; }
  }

  return (
    <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4 bg-[#FAFAFC] h-full">
      {!activeTaskId || !runtimeTask ? (
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
        ) : (() => {
          const { text: greetingText, icon: greetingIcon } = getGreeting();
          const [title, subtitle] = greetingText.split('！');
          return (
            <div className="flex flex-col items-center justify-center h-full w-full animate-in fade-in zoom-in-95 duration-700">
              {/* 呼吸发光的核心动效 */}
              <div className="relative flex items-center justify-center mb-8 mt-10">
                <div className="absolute w-40 h-40 bg-blue-500/20 rounded-full blur-3xl animate-pulse" />
                <div className="absolute w-24 h-24 bg-indigo-400/20 rounded-full blur-2xl animate-pulse" style={{ animationDelay: '150ms' }} />
                <motion.div animate={{ y: [-10, 10, -10] }} transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }} className="relative z-10 bg-gradient-to-b from-white to-blue-50 p-6 rounded-[2rem] shadow-[0_8px_30px_rgb(0,0,0,0.08)] border border-white/60">
                  <Bot className="h-16 w-16 text-blue-600" />
                  <motion.div animate={{ rotate: 360, scale: [1, 1.2, 1] }} transition={{ duration: 3, repeat: Infinity, ease: "linear" }} className="absolute -top-3 -right-3 text-amber-400">
                     <Sparkles className="h-8 w-8 fill-amber-400 drop-shadow-sm" />
                  </motion.div>
                </motion.div>
              </div>

              {/* 问候语 */}
              <div className="text-center space-y-3 mb-10">
                <h2 className="text-2xl font-bold text-zinc-800 tracking-tight flex items-center justify-center gap-2">
                  <span className="text-3xl">{greetingIcon}</span> <span>{title}！</span>
                </h2>
                <p className="text-sm text-zinc-500 font-medium">{subtitle}</p>
              </div>

              {/* 操作引导 */}
              <div className="flex items-center gap-2 text-sm font-medium text-blue-600 bg-blue-50/80 px-6 py-3 rounded-full border border-blue-100 shadow-sm mb-12 animate-bounce">
                👉 点击左侧任意群聊，或直接在输入框唤醒我开始工作吧！
              </div>

              {/* 快捷卡片占位 (Mock) */}
              <div className="w-full max-w-lg">
                 <div className="flex items-center justify-between mb-4 px-1">
                   <span className="text-[11px] font-bold text-zinc-400 uppercase tracking-widest">近期任务历史 (Preview)</span>
                 </div>
                 <div className="grid grid-cols-2 gap-4">
                    {recentTasks.length > 0 ? (
                      recentTasks.map(task => (
                        <div 
                          key={task.taskId}
                          onClick={() => setActiveTaskId(task.taskId)}
                          className="bg-white p-4 rounded-2xl border border-zinc-100 shadow-sm hover:shadow-md hover:border-blue-200 transition-all cursor-pointer group"
                        >
                          <div className={`w-9 h-9 rounded-xl flex items-center justify-center mb-3 group-hover:scale-110 transition-transform ${task.status === 'COMPLETED' ? 'bg-emerald-50 text-emerald-600' : 'bg-blue-50 text-blue-600'}`}>
                            <History className="h-5 w-5" />
                          </div>
                          <h4 className="text-sm font-bold text-zinc-800 mb-1 line-clamp-1" title={task.title}>{task.title}</h4>
                          <p className="text-[10px] text-zinc-400">
                            {new Date(task.updatedAt).toLocaleDateString()} • {getStatusDisplay(task.status).label}
                          </p>
                        </div>
                      ))
                    ) : (
                      <div className="col-span-2 py-8 text-center border-2 border-dashed border-zinc-100 rounded-2xl text-zinc-300 text-xs">
                        暂无近期执行记录
                      </div>
                    )}
                 </div>
              </div>
            </div>
          );
        })()
      ) : (
        <div className="animate-in fade-in slide-in-from-bottom-4 duration-500 flex flex-col gap-4">
          <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm relative overflow-hidden">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-2 flex-1">
                <h3 className="text-base font-extrabold text-zinc-800 leading-snug">{runtimeTask.title || planPreview?.title || 'Agent 任务规划'}</h3>
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
                  {/* 现在的逻辑：无论什么状态，右上角永远有一个清爽的“退出工作台”按钮 */}
<Button 
  variant="ghost" 
  size="icon" 
  className="h-6 w-6 text-zinc-400 hover:text-zinc-600 hover:bg-zinc-100 rounded-full ml-1" 
  onClick={() => {
    clearTask();
    toast.info('已回到待命大厅');
  }}
>
  <X className="w-4 h-4" />
</Button>
                </div>
                <span className="text-[9px] font-mono text-zinc-400">{runtimeTask.updatedAt ? new Date(runtimeTask.updatedAt).toLocaleTimeString() : new Date().toLocaleTimeString()}</span>
              </div>
            </div>
            <p className="text-xs text-zinc-500 mt-2 leading-relaxed">{runtimeTask.goal || planPreview?.summary || '已收到您的意图，正在执行'}</p>
            
            {runtimeTask.status === 'PLANNING' && (
              <div className="mt-3 p-3 bg-indigo-50/50 rounded-xl border border-indigo-100 overflow-hidden relative">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-[11px] text-indigo-600 flex items-center gap-1.5 font-medium">
                    <Loader2 className="h-3.5 w-3.5 animate-spin" /> Agent 正在进行深度推演与路径规划...
                  </span>
                  <span className="text-[10px] text-indigo-400 font-mono">预计 10s</span>
                </div>
                <div className="w-full h-1.5 bg-indigo-100/50 rounded-full overflow-hidden shadow-inner">
                   <motion.div 
                     initial={{ x: '-100%' }} 
                     animate={{ x: '200%' }} 
                     transition={{ duration: 1.5, repeat: Infinity, ease: 'linear' }} 
                     className="w-1/2 h-full bg-indigo-500 rounded-full" 
                   />
                </div>
              </div>
            )}

            {runtimeTask.riskFlags && runtimeTask.riskFlags.length > 0 && (
              <div className="mt-3 bg-amber-50/50 border border-amber-200/60 rounded-lg p-3">
                <div className="text-xs font-bold text-amber-600 flex items-center gap-1.5 mb-1.5"><AlertCircle className="w-3.5 h-3.5" /> 风险与关注点</div>
                <ul className="list-disc list-inside space-y-1 text-amber-700/80 text-[11px] ml-1">{runtimeTask.riskFlags.map((risk: string, i: number) => <li key={i}>{risk}</li>)}</ul>
              </div>
            )}
            {(runtimeTask.progress ?? 0) > 0 && (
              <div className="w-full h-1 bg-zinc-100 rounded-full overflow-hidden mt-4">
                <div className="h-full bg-blue-600 transition-all duration-500 ease-out" style={{ width: `${runtimeTask.progress}%` }} />
              </div>
            )}
          </div>

          {runtimeTask.status !== 'COMPLETED' && runtimeActions && (
            <div className="flex flex-col">
              {runtimeTask.status === 'FAILED' && (
                <div className="bg-[#FF9BB3]/10 border border-[#FF9BB3]/30 rounded-xl p-4 flex flex-col gap-3">
                   <div className="text-sm font-bold text-[#D04568] flex items-center gap-1.5"><AlertCircle className="h-4 w-4" /> 任务执行受阻</div>
                   <textarea value={retryFeedback} onChange={(e) => setRetryFeedback(e.target.value)} placeholder="请描述调整建议..." className="w-full text-xs p-3 rounded-lg border border-[#FF9BB3]/20 outline-none resize-none bg-white" rows={2} />
                   <Button size="sm" className="bg-[#D04568] hover:bg-[#b03a58] text-white w-full" onClick={() => handleCommand('RETRY_FAILED', retryFeedback)}><RefreshCw className="h-3.5 w-3.5 mr-1.5" /> 提交建议并重试</Button>
                </div>
              )}
              {runtimeTask.status === 'CLARIFYING' && (
                <div className="bg-blue-50/50 border border-blue-200 rounded-xl p-4 flex flex-col gap-3 shadow-sm">
                  <div className="text-sm font-bold text-blue-700 flex items-center gap-1.5"><Bot className="h-4 w-4" /> 需要您的进一步确认</div>
                  {clarificationQuestions.map((q, idx) => <p key={idx} className="text-xs text-blue-900 bg-white p-2.5 rounded-md border border-blue-100">{q}</p>)}
                  <textarea value={clarifyAnswer} onChange={(e) => setClarifyAnswer(e.target.value)} placeholder="请输入回答..." className="w-full text-xs p-3 rounded-lg border border-blue-200 outline-none resize-none bg-white focus:border-blue-500" rows={2} disabled={isActionLoading} />
                  <Button size="sm" className="bg-blue-600 hover:bg-blue-700 text-white w-full transition-all" disabled={!clarifyAnswer.trim() || isActionLoading} onClick={() => { handleCommand('RESUME', clarifyAnswer); }}>
                    {isActionLoading ? <span className="flex items-center gap-2"><Loader2 className="animate-spin h-4 w-4" /> 正在处理中...</span> : '提交回答'}
                  </Button>
                </div>
              )}
              {runtimeTask.status === 'WAITING_APPROVAL' && (
                <div className="bg-white border-2 border-blue-500/20 rounded-xl p-4 flex flex-col gap-3 shadow-sm">
                  <div className="text-sm font-bold text-blue-800 flex justify-between items-center"><span className="flex items-center gap-1.5"><CheckCircle2 className="h-4 w-4 text-blue-600" /> 计划已就绪</span></div>
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

          {runtimeSteps.length > 0 && (
            <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm relative overflow-hidden">
              <h4 className="text-[11px] font-bold text-blue-400/80 uppercase tracking-wider mb-4">Execution Steps</h4>
              <div className="space-y-4 relative before:absolute before:inset-0 before:ml-[11px] before:h-full before:w-[2px] before:bg-zinc-100">
                {runtimeSteps.map((step: RuntimeStepVO, idx: number) => {
                  const isTaskCompleted = runtimeTask.status === 'COMPLETED';
                  const displayStatus = isTaskCompleted ? 'COMPLETED' : step.status;
                  const isRunning = isRuntimeStepRunning(displayStatus);
                  const duration = getDuration(step.startedAt, step.endedAt || (isTaskCompleted ? runtimeTask.updatedAt : null));
                  return (
                    <div key={step.stepId || idx} className="relative flex items-start gap-3">
                      <div className={`flex items-center justify-center w-6 h-6 rounded-full border-[3px] border-white z-10 mt-0.5 transition-colors duration-300 ${displayStatus === 'COMPLETED' ? 'bg-zinc-800 text-white' : isRunning ? 'bg-blue-500 text-white shadow-sm' : displayStatus === 'FAILED' ? 'bg-red-500 text-white' : 'bg-white text-blue-300 ring-1 ring-inset ring-blue-200'}`}>
                        {displayStatus === 'COMPLETED' ? <Check className="w-3.5 h-3.5" /> : isRunning ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : displayStatus === 'FAILED' ? <X className="w-3.5 h-3.5" /> : <CircleDashed className="w-3.5 h-3.5" />}
                      </div>
                      <div className="flex-1 pt-1 pb-3">
                        <div className="flex items-start justify-between gap-2">
                          <h4 className={`text-sm font-bold ${isRunning ? 'text-blue-600' : 'text-zinc-800'}`}>{step.name}</h4>
                          {step.type && <span className="text-[9px] font-bold text-indigo-500 bg-indigo-50/80 border border-indigo-100 px-1.5 py-0.5 rounded tracking-widest shrink-0">{step.type.replace('_', ' ')}</span>}
                        </div>
                        <div className="flex items-center gap-3 mt-1.5 text-[10px] font-mono text-zinc-400">
                          {step.assignedWorker && <span className="flex items-center gap-1 bg-zinc-100/70 px-1.5 py-0.5 rounded text-zinc-500"><Bot className="w-3 h-3" /> {step.assignedWorker}</span>}
                          {duration && <span className="flex items-center gap-1"><History className="w-3 h-3" /> {duration}</span>}
                          {step.retryCount > 0 && <span className="flex items-center gap-1 bg-amber-50 text-amber-600 border border-amber-200 px-1.5 py-0.5 rounded"><RefreshCw className="w-3 h-3" /> 重试了 {step.retryCount} 次</span>}
                        </div>
                        {step.inputSummary && <p className="text-[11px] text-zinc-500 mt-2 leading-relaxed bg-zinc-50/50 p-2 rounded-md border border-zinc-100">{step.inputSummary}</p>}
                        {step.outputSummary && <p className="text-[11px] text-emerald-600 mt-1.5 leading-relaxed bg-emerald-50/50 p-2 rounded-md border border-emerald-100">✅ 成果：{step.outputSummary}</p>}
                        
                        {isRunning && (
                          <div className={`mt-3 p-3 rounded-lg relative overflow-hidden group border transition-colors duration-300 ${isInterruptingMode ? 'bg-amber-50/80 border-amber-200' : 'bg-blue-50/50 border-blue-100'}`}>
                            <div className="flex items-start justify-between mb-2">
                              <span className={`text-[11px] font-medium flex items-center gap-1.5 leading-relaxed ${isInterruptingMode ? 'text-amber-700' : 'text-blue-700'}`}>
                                {!isInterruptingMode ? (
                                  <Bot className="w-4 h-4 animate-bounce text-blue-500" /> 
                                ) : (
                                  <span className="text-amber-500 text-sm leading-none mr-0.5">⏸️</span>
                                )}
                                {isInterruptingMode 
                                  ? '任务已挂起，等待您的最新指示...' 
                                  : (step.type === 'PPT_CREATE' || step.type === 'PPT' ? '正在为您精雕细琢每一页幻灯片...' : 'Agent 正在飞速执行作业中，请稍候...')}
                              </span>
                              {!isInterruptingMode && <span className="text-[10px] text-blue-400 font-mono shrink-0 ml-2 mt-0.5">预计 60s</span>}
                            </div>
                            <div className={`w-full h-1.5 rounded-full overflow-hidden shadow-inner mb-3 transition-colors duration-300 ${isInterruptingMode ? 'bg-amber-200/50' : 'bg-blue-200/50'}`}>
                              <motion.div 
                                className={`h-full relative transition-colors duration-300 ${isInterruptingMode ? 'bg-amber-400' : 'bg-blue-500'}`} 
                                initial={{ width: `${step.progress || 0}%` }} 
                                animate={isInterruptingMode ? {} : { width: step.progress >= 90 ? `${step.progress}%` : "90%" }} 
                                transition={{ duration: isInterruptingMode ? 0 : (step.progress >= 100 ? 0.5 : 60), ease: "easeOut" }}
                              >
                                {!isInterruptingMode && <div className="absolute top-0 left-0 w-full h-full bg-white/20 animate-[shimmer_1s_infinite]" />}
                              </motion.div>
                            </div>
                            
                            {/* ✨ 修复点：修改了执行中的控制台操作区，引入并明确区分中断与永久取消 */}
                            {!isInterruptingMode ? (
                              <div className="flex flex-col sm:flex-row items-end sm:items-center justify-between gap-3 mt-2">
                                <div className="flex-1 space-y-1.5 opacity-40 w-full sm:w-auto">
                                  <div className="h-1.5 bg-blue-300 rounded w-full animate-pulse"></div>
                                  <div className="h-1.5 bg-blue-300 rounded w-5/6 animate-pulse" style={{ animationDelay: '150ms' }}></div>
                                </div>
                                <div className="flex gap-2 shrink-0">
                                  {runtimeActions?.canInterruptReplan && (
                                    <Button variant="outline" size="sm" onClick={() => setIsInterruptingMode(true)} className="h-7 text-xs text-indigo-600 border-indigo-200 hover:bg-indigo-50 shadow-sm transition-colors">
                                      <Wand2 className="w-3.5 h-3.5 mr-1" /> 干预并调整任务
                                    </Button>
                                  )}
                                  <Button variant="ghost" size="sm" disabled={isCancelling} onClick={() => handleCommand('CANCEL')} className="h-7 text-xs px-2 text-red-500 hover:text-red-600 hover:bg-red-50 transition-colors" title="此操作不可逆，将永久终止任务">
                                    {isCancelling ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : <StopCircle className="w-3.5 h-3.5 mr-1" />} 永久取消
                                  </Button>
                                </div>
                              </div>
                            ) : (
                              <div className="mt-3 flex flex-col gap-2 bg-white p-3 rounded-lg border border-indigo-200 shadow-sm animate-in zoom-in-95 duration-200">
                                 <div className="text-xs font-bold text-indigo-700 flex items-center gap-1"><AlertCircle className="w-3.5 h-3.5" /> 请输入调整指令，Agent 将自动重新规划</div>
                                 <textarea
                                   value={interruptFeedback}
                                   onChange={e => setInterruptFeedback(e.target.value)}
                                   placeholder="例如：请把第二步改为先调研竞品再输出报告..."
                                   className="w-full text-xs p-2.5 rounded-md border border-zinc-200 outline-none resize-none bg-zinc-50 focus:bg-white focus:border-indigo-400 focus:ring-2 focus:ring-indigo-500/20 transition-all"
                                   rows={2}
                                   autoFocus
                                 />
                                 <div className="flex gap-2 justify-end">
                                   <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => {setIsInterruptingMode(false); setInterruptFeedback('');}}>放弃</Button>
                                   <Button size="sm" className="h-7 text-xs bg-indigo-600 hover:bg-indigo-700 text-white shadow-sm" disabled={!interruptFeedback.trim() || isActionLoading} onClick={() => handleCommand('INTERRUPT_REPLAN')}>
                                     {isActionLoading ? <Loader2 className="w-3 h-3 animate-spin mr-1" /> : <RefreshCw className="w-3 h-3 mr-1" />}
                                     确认调整
                                   </Button>
                                 </div>
                              </div>
                            )}

                          </div>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {runtimeArtifacts.length > 0 && (
            <div className="flex flex-col gap-3">
              {runtimeArtifacts.map((artifact: RuntimeArtifactVO) => {
                if (artifact.type === 'DOC') return <DocPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' || artifact.status === 'UPDATED' ? 'COMPLETED' : 'GENERATING'} docUrl={artifact.url} docTitle={artifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(feedback, policy) => handleCommand('REPLAN', feedback, { artifactPolicy: policy, targetArtifactId: artifact.artifactId })} />;
                if (artifact.type === 'PPT') return <PptPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' || artifact.status === 'UPDATED' ? 'COMPLETED' : 'EXECUTING'} pptUrl={artifact.url} pptTitle={artifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(feedback, policy) => handleCommand('REPLAN', feedback, { artifactPolicy: policy, targetArtifactId: artifact.artifactId })} onInterrupt={() => handleCommand('CANCEL')} />;
                return null;
              })}
            </div>
          )}

          {runtimeTask.status === 'COMPLETED' && (() => {
            // ✨ 核心逻辑：如果记录的交付时间与当前任务更新时间一致，说明没做过新修改
            const isDelivered = lastDeliveredTime === runtimeTask.updatedAt;
            return (
              <Button 
                className={`w-full h-10 font-bold text-white shadow-md transition-all duration-300 animate-in zoom-in ${isDelivered ? 'bg-emerald-500 hover:bg-emerald-600' : 'bg-zinc-900 hover:bg-zinc-800'} disabled:opacity-60 disabled:cursor-not-allowed`}
                onClick={handleDeliver}
                disabled={isDelivering || isDelivered}
              >
                {isDelivering ? <Loader2 className="h-4 w-4 mr-2 animate-spin" /> : (isDelivered ? <CheckCircle2 className="h-4 w-4 mr-2" /> : <Send className="h-4 w-4 mr-2" />)} 
                {isDelivering ? '正在同步...' : isDelivered ? '✅ 已同步至群聊' : lastDeliveredTime ? '重新同步最新版本' : '总结与交付群聊'}
              </Button>
            );
          })()}

          {taskRuntime?.events && taskRuntime.events.length > 0 && (
            <div className="mt-4 border border-zinc-200 rounded-xl bg-[#1C1C1E] overflow-hidden shadow-inner">
              <div className="px-3 py-2 bg-zinc-800/80 flex items-center justify-between cursor-pointer hover:bg-zinc-800 transition-colors" onClick={() => setIsTerminalExpanded(!isTerminalExpanded)}>
                <div className="flex items-center gap-2 text-zinc-400 text-xs font-mono"><TerminalSquare className="w-3.5 h-3.5" /> {isTerminalExpanded ? '执行追踪器' : '最后一条日志: ' + (taskRuntime.events[taskRuntime.events.length - 1]?.message.slice(0, 25) + '...')}</div>
                {isTerminalExpanded ? <ChevronUp className="w-4 h-4 text-zinc-500" /> : <ChevronDown className="w-4 h-4 text-zinc-500" />}
              </div>
              {isTerminalExpanded && (
                <div className="p-3 max-h-48 overflow-y-auto font-mono text-[10px] space-y-1.5 bg-[#1C1C1E]">
                  {taskRuntime.events.map((e: any) => (
                    <div key={e.eventId} className="flex items-start gap-2 text-zinc-500"><span className="text-zinc-600 mt-0.5">❯</span><span className={e.message.includes('ERROR') ? 'text-red-400' : 'text-zinc-400'}>{e.message}</span></div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}