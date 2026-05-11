// src/components/dashboard/AgentWorkspaceMobile.tsx
import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import {
  Bot, Loader2, X, AlertCircle, RefreshCw, CheckCircle2, Play,
  CircleDashed, Check, History, Send, TerminalSquare, ChevronDown, ChevronUp, StopCircle,
  FileText, Presentation, ChevronRight, UserCircle, Wand2, Sparkles,Lightbulb, ArrowRight
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
import { motion, AnimatePresence } from 'framer-motion';

const getGreeting = () => {
  const hour = new Date().getHours();
  if (hour >= 6 && hour < 12) return { text: "早上好！Agent Pilot 已就绪，今天我们要搞定哪些大项目？", icon: "🌞" };
  if (hour >= 12 && hour < 14) return { text: "中午好！先去吃个好饭歇一歇，繁琐的任务随时交给我。", icon: "🍱" };
  if (hour >= 14 && hour < 18) return { text: "下午好！把枯燥的排版和梳理交给我，您可以喝杯咖啡啦~", icon: "☕" };
  if (hour >= 18 && hour < 24) return { text: "晚上好！还在忙碌吗？夜深了，让我来帮你加速收尾吧！", icon: "🌙" };
  return { text: "夜深了！哇，深夜还在奋斗，Agent 陪您一起随时待命。", icon: "🦉" };
};

const isRuntimeStepRunning = (status?: string) => ['IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(status || '');

const getStatusDisplay = (status?: string) => {
  const s = status?.toUpperCase() || '';
  if (['INTAKE', 'INTENT_READY', 'PLANNING'].includes(s)) return { label: '规划中', style: 'bg-indigo-50 text-indigo-600 border-indigo-100' };
  
  // ✨ 新增中断/重规划移动端样式
  if (['INTERRUPTING'].includes(s)) return { label: '中断中', style: 'bg-amber-100 text-amber-600 border-amber-200 animate-pulse' };
  if (['REPLANNING'].includes(s)) return { label: '重新推演中', style: 'bg-indigo-100 text-indigo-600 border-indigo-200 animate-pulse' };

  if (['ASK_USER', 'CLARIFYING'].includes(s)) return { label: '待补充', style: 'bg-rose-50 text-rose-600 border-rose-100' };
  if (['PLAN_READY', 'WAITING_APPROVAL'].includes(s)) return { label: '待确认', style: 'bg-blue-600 text-white border-blue-600' };
  if (['IN_PROGRESS', 'EXECUTING', 'RUNNING'].includes(s)) return { label: '执行中', style: 'bg-blue-500 text-white border-blue-500 animate-pulse' };
  if (['COMPLETED', 'DONE', 'READY', 'CREATED'].includes(s)) return { label: '已完成', style: 'bg-emerald-50 text-emerald-700 border-emerald-100' };
  if (['FAILED'].includes(s)) return { label: '已失败', style: 'bg-red-50 text-red-700 border-red-100' };
  if (['ABORTED', 'CANCELLED'].includes(s)) return { label: '已取消', style: 'bg-zinc-100 text-zinc-500 border-zinc-200' };
  return { label: '准备中', style: 'bg-zinc-100 text-zinc-500 border-zinc-200' };
};

const getDuration = (start?: string | null, end?: string | null) => {
  if (!start || !end) return null;
  const diff = Math.max(0, new Date(end).getTime() - new Date(start).getTime());
  return diff < 1000 ? `${diff}ms` : `${(diff / 1000).toFixed(1)}s`;
};

export function AgentWorkspaceMobile() {
  const { activeChatId } = useChatStore();
  const { activeTaskId, planPreview, taskRuntime, setPlanPreview, setTaskRuntime, clearTask, isPlanning } = useTaskStore();

  const [retryFeedback, setRetryFeedback] = useState('');
  const [clarifyAnswer, setClarifyAnswer] = useState('');
  const [isTerminalExpanded, setIsTerminalExpanded] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [isReplanningMode, setIsReplanningMode] = useState(false);
  const [replanFeedback, setReplanFeedback] = useState('');
  const [isActionLoading, setIsActionLoading] = useState(false);
  const [previewArtifactId, setPreviewArtifactId] = useState<string | null>(null);
  const [isDelivering, setIsDelivering] = useState(false);
  
  // ✨ 新增：移动端也同步记录时间戳
  const [lastDeliveredTime, setLastDeliveredTime] = useState<string | null>(null);
  const [prevTaskId, setPrevTaskId] = useState(activeTaskId);

  if (activeTaskId !== prevTaskId) {
    setPrevTaskId(activeTaskId);
    setLastDeliveredTime(null);
  }
  // ✨ 中断控制态
  const [isInterruptingMode, setIsInterruptingMode] = useState(false);
  const [interruptFeedback, setInterruptFeedback] = useState('');

  const runtimeTask = taskRuntime?.task;
  const runtimeActions = taskRuntime?.actions;
  const runtimeSteps = taskRuntime?.steps ?? [];
  const runtimeArtifacts = taskRuntime?.artifacts ?? [];
  const runtimeEvents = taskRuntime?.events ?? [];
// ✨ 新增：推荐执行状态
  const [executingRecId, setExecutingRecId] = useState<string | null>(null);

  // ✨ 新增：处理推荐点击的专门函数
  const handleExecuteRecommendation = async (recId: string) => {
    if (!activeTaskId || !runtimeTask?.version) return;
    setExecutingRecId(recId);
    try {
      const newPreview = await plannerApi.executeRecommendation(activeTaskId, recId, { version: runtimeTask.version });
      setPlanPreview(newPreview); // 切换状态
      
      // 隐式刷新底层数据
      try {
        const freshRuntime = await plannerApi.getTaskRuntime(activeTaskId);
        setTaskRuntime(freshRuntime);
      } catch (_e) {}
    } catch (e: any) {
      // 按契约处理特定错误码
      if (e.code === 40900) {
        toast.warning('任务已被更新', { description: '正在为您刷新最新状态' });
      } else if (e.code === 50001) {
        toast.warning('该推荐已失效', { description: '正在为您刷新当前任务' });
      } else {
        toast.error('执行失败', { description: e.message || '未知错误' });
      }
      // 出错时兜底刷新一次
      plannerApi.getTaskRuntime(activeTaskId).then(setTaskRuntime).catch(console.error);
    } finally {
      setExecutingRecId(null);
    }
  };
  // ✨ 更新 handleCommand 以支持 INTERRUPT_REPLAN
  const handleCommand = async (action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL' | 'RETRY_FAILED' | 'RESUME' | 'INTERRUPT_REPLAN', customFeedback?: string, extraPayload?: any) => {
    if (!activeTaskId || isActionLoading) return;
    setIsActionLoading(true);
    if (action === 'CANCEL') setIsCancelling(true);
    
    let finalFeedback = customFeedback;
    if (action === 'REPLAN') finalFeedback = replanFeedback.trim();
    if (action === 'INTERRUPT_REPLAN') finalFeedback = interruptFeedback.trim();

    try {
      const newPreview = await plannerApi.executeCommand(activeTaskId, {
        action, feedback: finalFeedback,
        version: runtimeTask?.version ?? planPreview?.version, ...extraPayload
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
    } catch (e: any) {
      toast.error('操作失败', { description: e.message });
    } finally {
      if (action === 'CANCEL') setIsCancelling(false);
      setIsActionLoading(false);
    }
  };

  const handleDeliver = async () => {
    if (!activeTaskId || !activeChatId || isDelivering) return;
    setIsDelivering(true);
    confetti({ particleCount: 150, spread: 70, origin: { y: 0.6 } });
    try {
      // 1. 提取各个产物
      const docLink = taskRuntime?.artifacts.find(a => a.type === 'DOC')?.url;
      const pptLink = taskRuntime?.artifacts.find(a => a.type === 'PPT')?.url;
      const summaryContent = taskRuntime?.artifacts.find(a => a.type === 'SUMMARY')?.preview;
      
      // 2. 组装文案（分段式拼接）
      let deliverText = `🎉 本次任务已由 Agent 顺利完成，成果如下：\n\n`;
      
      if (summaryContent) {
        deliverText += `💡 【核心摘要】\n${summaryContent.trim()}\n\n`;
      }
      
      deliverText += `🔗 【产物链接】\n`;
      deliverText += `📝 项目文档：${docLink || '暂无'}\n`;
      deliverText += `📊 汇报演示：${pptLink || '暂无'}\n\n`;
      deliverText += `💡 温馨提示：您可以直接查阅以上内容。如需微调，请随时@我或在群内回复。`;

      // 3. 发送
      await imApi.sendMessage({ 
        chatId: activeChatId, 
        text: deliverText, 
        idempotencyKey: crypto.randomUUID() 
      });
      
      toast.success('🎉 总结与交付成功！');
      setLastDeliveredTime(runtimeTask?.updatedAt || null); 
    } catch (e: any) {
      toast.error('交付推送失败', { description: e.message });
    } finally {
      setIsActionLoading(false); // 确保状态重置
      setIsDelivering(false);
    }
  };

  let clarificationQuestions: string[] = [];
  if (runtimeTask?.status === 'CLARIFYING') {
    const event = runtimeEvents.slice().reverse().find(e => e.type === 'CLARIFICATION_REQUIRED');
    try { clarificationQuestions = event ? JSON.parse(event.message) : []; } catch { clarificationQuestions = [event?.message || '']; }
  }

  const activePreviewArtifact = runtimeArtifacts.find(a => a.artifactId === previewArtifactId);

  return (
    <div className="flex-1 overflow-y-auto bg-[#FAFAFC] relative h-full flex flex-col p-4 gap-4">
      {(!activeTaskId || !runtimeTask) ? (
        isPlanning ? (
          <div className="flex flex-col items-center justify-center h-full gap-4 py-20">
            <Bot className="h-12 w-12 text-blue-500 animate-bounce" />
            <p className="text-sm text-zinc-500">Agent 正在为您理解意图并生成规划...</p>
            <div className="w-48 h-1.5 bg-blue-100 rounded-full overflow-hidden mt-2 shadow-inner">
              <motion.div initial={{ x: '-100%' }} animate={{ x: '100%' }} transition={{ duration: 1.5, repeat: Infinity, ease: 'linear' }} className="w-1/2 h-full bg-blue-500 rounded-full" />
            </div>
          </div>
        ) : (() => {
          const { text: greetingText, icon: greetingIcon } = getGreeting();
          const [title, subtitle] = greetingText.split('！');
          return (
            <div className="flex flex-col items-center justify-center h-full w-full px-6 animate-in fade-in zoom-in-95 duration-700 pb-20">
              <div className="relative flex items-center justify-center mb-8">
                <div className="absolute w-32 h-32 bg-blue-500/20 rounded-full blur-2xl animate-pulse" />
                <motion.div animate={{ y: [-8, 8, -8] }} transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }} className="relative z-10 bg-white p-5 rounded-3xl shadow-lg border border-zinc-100">
                  <Bot className="h-10 w-10 text-blue-600" />
                  <motion.div animate={{ rotate: 360, scale: [1, 1.2, 1] }} transition={{ duration: 3, repeat: Infinity, ease: "linear" }} className="absolute -top-2 -right-2 text-amber-400">
                     <Sparkles className="h-6 w-6 fill-amber-400" />
                  </motion.div>
                </motion.div>
              </div>
              <div className="text-center space-y-2 mb-8">
                <h2 className="text-lg font-bold text-zinc-800">{greetingIcon} {title}！</h2>
                <p className="text-xs text-zinc-500 leading-relaxed">{subtitle}</p>
              </div>
              <div className="text-[11px] font-medium text-blue-600 bg-blue-50 px-5 py-2.5 rounded-full border border-blue-100 animate-bounce">
                👉 随时在下方输入指令唤醒我
              </div>
            </div>
          );
        })()
      ) : (
        <div className="flex flex-col gap-4 pb-20">
          <div className="bg-white rounded-2xl p-4 border border-zinc-200 shadow-sm relative overflow-hidden">
            <div className="flex justify-between items-start mb-3">
              <h3 className="text-base font-bold text-zinc-900 leading-tight flex-1 pr-4">{runtimeTask.title}</h3>
              
              {/* ✨ 修改点：把状态标签和新的退出按钮(X)组合在一起 */}
              <div className="flex items-center gap-1.5 shrink-0">
                {(() => { const { label, style } = getStatusDisplay(runtimeTask.status); return <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border ${style}`}>{label}</span>; })()}
                <Button 
                  variant="ghost" 
                  size="icon" 
                  className="h-6 w-6 text-zinc-400 bg-zinc-100/50 hover:bg-zinc-200 hover:text-zinc-600 rounded-full transition-colors" 
                  onClick={() => clearTask()}
                >
                  <X className="h-3.5 w-3.5" />
                </Button>
              </div>
            </div>
            <p className="text-sm text-zinc-500 leading-relaxed mb-4">{runtimeTask.goal}</p>
            
            {runtimeTask.status === 'PLANNING' && (
              <div className="mt-2 mb-4 p-3 bg-indigo-50/50 rounded-xl border border-indigo-100">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-[11px] text-indigo-600 flex items-center gap-1.5 font-medium">
                    <Loader2 className="h-3 w-3 animate-spin" /> Agent 正在进行深度推演与路径规划...
                  </span>
                </div>
                <div className="w-full h-1.5 bg-indigo-100 rounded-full overflow-hidden">
                   <motion.div initial={{ x: '-100%' }} animate={{ x: '100%' }} transition={{ duration: 2, repeat: Infinity, ease: 'linear' }} className="w-1/3 h-full bg-indigo-500 rounded-full" />
                </div>
              </div>
            )}
            
            {runtimeTask.riskFlags && runtimeTask.riskFlags.length > 0 && (
              <div className="bg-amber-50 border border-amber-100 rounded-xl p-3 mb-4">
                <div className="text-xs font-bold text-amber-700 flex items-center gap-1.5 mb-1">
                  <AlertCircle className="h-3.5 w-3.5" /> 风险提示
                </div>
                <ul className="list-disc list-inside text-[11px] text-amber-600/80 space-y-0.5">
                  {runtimeTask.riskFlags.map((risk, i) => <li key={i}>{risk}</li>)}
                </ul>
              </div>
            )}

            <div className="flex items-center justify-between">
              <span className="text-[10px] font-mono text-zinc-400">更新于 {new Date(runtimeTask.updatedAt).toLocaleTimeString()}</span>
              {runtimeActions?.canCancel && (
                <Button variant="ghost" size="xs" className="text-zinc-400 h-6" onClick={() => handleCommand('CANCEL')} disabled={isCancelling}>
                  {isCancelling ? <Loader2 className="h-3 w-3 animate-spin" /> : '取消任务'}
                </Button>
              )}
            </div>
          </div>

          {runtimeTask.status === 'CLARIFYING' && (
            <div className="bg-blue-50 border border-blue-100 rounded-2xl p-4 flex flex-col gap-3">
              <div className="text-sm font-bold text-blue-700">🤖 需要您的进一步信息</div>
              {clarificationQuestions.map((q, i) => <p key={i} className="text-xs text-blue-900 bg-white/60 p-2 rounded-lg">{q}</p>)}
              <textarea 
                value={clarifyAnswer} onChange={e => setClarifyAnswer(e.target.value)}
                placeholder="在此输入您的补充..." 
                className="w-full text-sm p-3 rounded-xl border border-blue-200 outline-none min-h-[80px] focus:bg-white focus:border-blue-500 transition-colors"
              />
              <Button className="w-full bg-blue-600 text-white h-10 rounded-xl" onClick={() => handleCommand('RESUME', clarifyAnswer)} disabled={!clarifyAnswer.trim() || isActionLoading}>
                {isActionLoading ? <Loader2 className="animate-spin" /> : '提交反馈'}
              </Button>
            </div>
          )}

          {runtimeTask.status === 'WAITING_APPROVAL' && (
            <div className="bg-white border-2 border-blue-500/20 rounded-2xl p-4 flex flex-col gap-3 shadow-md">
              <div className="text-sm font-bold text-blue-800 flex items-center gap-1.5"><CheckCircle2 className="w-4 h-4"/>任务执行方案已就绪</div>
              {isReplanningMode ? (
                <>
                  <textarea value={replanFeedback} onChange={e => setReplanFeedback(e.target.value)} placeholder="输入您的修改建议..." className="text-sm p-3 border rounded-xl bg-zinc-50 min-h-[80px]" />
                  <div className="flex gap-2">
                    <Button variant="outline" className="flex-1 rounded-xl" onClick={() => setIsReplanningMode(false)}>取消</Button>
                    <Button className="flex-1 bg-blue-600 text-white rounded-xl" onClick={() => handleCommand('REPLAN')}>提交调整</Button>
                  </div>
                </>
              ) : (
                <div className="flex flex-col gap-2">
                  <Button className="w-full bg-blue-600 text-white h-11 rounded-xl font-bold" onClick={() => handleCommand('CONFIRM_EXECUTE')}>确认执行</Button>
                  <Button variant="ghost" className="w-full text-zinc-500 h-9" onClick={() => setIsReplanningMode(true)}>调整规划</Button>
                </div>
              )}
            </div>
          )}

          {runtimeArtifacts.length > 0 && (
            <div className="flex flex-col gap-2">
              <h4 className="text-[10px] font-bold text-zinc-400 uppercase tracking-widest pl-1">产物预览</h4>
              {runtimeArtifacts.map(a => {
                // ✨ 新增：针对 SUMMARY 的特殊移动端渲染（直接展示纯文本，不需要弹窗）
                if (a.type === 'SUMMARY') {
                  return (
                    <div key={a.artifactId} className="bg-amber-50/40 border border-amber-200/60 rounded-xl p-3 shadow-sm mb-1 animate-in fade-in">
                      <div className="flex items-center gap-1.5 mb-2 text-amber-700">
                        <Sparkles className="h-3.5 w-3.5" />
                        <span className="text-[11px] font-bold">任务执行摘要</span>
                      </div>
                      <div className="text-[11px] text-zinc-600 leading-relaxed whitespace-pre-wrap bg-white/60 p-2.5 rounded-lg border border-amber-100/30">
                        {a.preview || "摘要内容生成中..."}
                      </div>
                    </div>
                  );
                }

                // 👇 原有逻辑：DOC 和 PPT 保持原来的紧凑列表样式，点击后弹窗预览
                return (
                  <div key={a.artifactId} onClick={() => setPreviewArtifactId(a.artifactId)} className="bg-white p-3 rounded-xl border border-zinc-200 flex items-center justify-between active:bg-zinc-50">
                    <div className="flex items-center gap-3">
                      <div className={`p-2 rounded-lg ${a.type === 'PPT' ? 'bg-orange-100 text-orange-600' : 'bg-blue-100 text-blue-600'}`}>
                        {a.type === 'PPT' ? <Presentation className="h-5 w-5" /> : <FileText className="h-5 w-5" />}
                      </div>
                      <div className="flex flex-col overflow-hidden">
                        <span className="text-sm font-bold text-zinc-800 truncate">{a.title}</span>
                        <span className="text-[10px] text-zinc-400">状态: {a.status === 'CREATED' ? '已生成' : '处理中'}</span>
                      </div>
                    </div>
                    <ChevronRight className="h-4 w-4 text-zinc-300" />
                  </div>
                );
              })}
            </div>
          )}

          {runtimeSteps.length > 0 && (
            <div className="bg-white border border-zinc-200 rounded-2xl p-4 shadow-sm">
              <h4 className="text-[10px] font-bold text-blue-400/80 uppercase tracking-widest mb-4">执行详情</h4>
              <div className="space-y-6 relative before:absolute before:inset-0 before:ml-[11px] before:h-full before:w-[2px] before:bg-zinc-100">
                {runtimeSteps.map((step) => {
                  const isRunning = isRuntimeStepRunning(step.status);
                  const duration = getDuration(step.startedAt, step.endedAt || (runtimeTask.status === 'COMPLETED' ? runtimeTask.updatedAt : null));
                  return (
                    <div key={step.stepId} className="relative flex items-start gap-4">
                      <div className={`flex items-center justify-center w-6 h-6 rounded-full border-4 border-white z-10 shrink-0 transition-colors duration-300 ${
  (step.status === 'COMPLETED' || runtimeTask.status === 'COMPLETED') ? 'bg-zinc-800 text-white' : 
  isRunning ? 'bg-blue-500 text-white shadow-sm' : 
  step.status === 'FAILED' ? 'bg-red-500 text-white' : 
  'bg-white text-blue-300 ring-1 ring-inset ring-blue-200'
}`}>
                        {step.status === 'COMPLETED' || runtimeTask.status === 'COMPLETED' ? <Check className="h-3 w-3" /> : isRunning ? <Loader2 className="h-3 w-3 animate-spin" /> : <CircleDashed className="h-3 w-3" />}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex justify-between items-start">
                          <h5 className={`text-sm font-bold ${isRunning ? 'text-blue-600' : 'text-zinc-800'}`}>{step.name}</h5>
                          {duration && <span className="text-[9px] font-mono text-zinc-400">{duration}</span>}
                        </div>
                        
                        {step.assignedWorker && (
                          <div className="flex items-center gap-1 mt-1 text-[9px] text-indigo-500 font-medium">
                            <UserCircle className="h-3 w-3" /> {step.assignedWorker}
                          </div>
                        )}

                        {step.inputSummary && (
                          <div className="mt-2 p-2 bg-zinc-50 border border-zinc-100 rounded-lg text-[11px] text-zinc-500 leading-normal italic">
                            “ {step.inputSummary} ”
                          </div>
                        )}

                        {step.outputSummary && (
                          <div className="mt-1.5 p-2 bg-emerald-50 border border-emerald-100 rounded-lg text-[11px] text-emerald-700 leading-normal">
                            成果：{step.outputSummary}
                          </div>
                        )}

                        {isRunning && (
                          <div className={`mt-3 p-3 rounded-lg relative overflow-hidden transition-colors duration-300 border ${isInterruptingMode ? 'bg-amber-50/80 border-amber-200' : 'bg-blue-50/50 border-blue-100'}`}>
                            <div className="flex items-start justify-between mb-2">
                              <span className={`text-[11px] font-medium flex items-center gap-1.5 leading-relaxed ${isInterruptingMode ? 'text-amber-700' : 'text-blue-700'}`}>
                                {!isInterruptingMode ? (
                                  <Bot className="w-4 h-4 animate-bounce text-blue-500"/> 
                                ) : (
                                  <span className="text-amber-500 text-sm leading-none mr-0.5">⏸️</span>
                                )}
                                {isInterruptingMode 
                                  ? '任务已挂起，等待您的最新指示...' 
                                  : (step.type === 'PPT_CREATE' || step.type === 'PPT' ? '正在精雕细琢每一页，预计需要一小会...' : 'Agent 正在飞速执行中，请稍候...')}
                              </span>
                              {!isInterruptingMode && <span className="text-[10px] text-blue-400 font-mono shrink-0 ml-2 mt-0.5">预计 60s</span>}
                            </div>
                            <div className={`w-full h-1.5 rounded-full overflow-hidden shadow-inner mb-3 transition-colors duration-300 ${isInterruptingMode ? 'bg-amber-200/50' : 'bg-blue-200/50'}`}>
                              <motion.div 
                                className={`h-full relative transition-colors duration-300 ${isInterruptingMode ? 'bg-amber-400' : 'bg-blue-500'}`} 
                                initial={{ width: `0%` }} 
                                animate={isInterruptingMode ? {} : { width: "90%" }} 
                                transition={{ duration: isInterruptingMode ? 0 : 60, ease: "easeOut" }}
                              >
                                {!isInterruptingMode && <div className="absolute top-0 left-0 w-full h-full bg-white/20 animate-[shimmer_1s_infinite]" />}
                              </motion.div>
                            </div>
                            
                            {/* ✨ 修复点：移动端也区分了干预输入与永久取消 */}
                            {!isInterruptingMode ? (
                              <div className="flex flex-col gap-2 mt-2">
                                <div className="flex-1 space-y-1.5 opacity-40 mr-4">
                                  <div className="h-1.5 bg-blue-300 rounded w-full animate-pulse"></div>
                                  <div className="h-1.5 bg-blue-300 rounded w-5/6 animate-pulse" style={{ animationDelay: '150ms' }}></div>
                                </div>
                                <div className="flex justify-between items-center w-full mt-1 border-t border-blue-100/50 pt-2">
                                  <Button variant="ghost" size="sm" disabled={isCancelling} onClick={() => handleCommand('CANCEL')} className="h-7 text-[10px] px-1 text-red-500 hover:text-red-600 transition-colors">
                                    {isCancelling ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : <StopCircle className="w-3.5 h-3.5 mr-1" />} 永久取消
                                  </Button>
                                  {runtimeActions?.canInterruptReplan && (
                                    <Button variant="outline" size="sm" onClick={() => setIsInterruptingMode(true)} className="h-7 text-[11px] text-indigo-600 border-indigo-200 bg-white/50 shadow-sm">
                                      <Wand2 className="w-3.5 h-3.5 mr-1" /> 干预调整
                                    </Button>
                                  )}
                                </div>
                              </div>
                            ) : (
                              <div className="mt-3 flex flex-col gap-2 bg-white p-3 rounded-lg border border-indigo-200 shadow-sm">
                                 <textarea
                                   value={interruptFeedback}
                                   onChange={e => setInterruptFeedback(e.target.value)}
                                   placeholder="例如：请把第二步改为先调研竞品再输出报告..."
                                   className="w-full text-xs p-2.5 rounded-md border border-zinc-200 outline-none resize-none bg-zinc-50 focus:bg-white focus:border-indigo-400 transition-all"
                                   rows={3}
                                 />
                                 <div className="flex gap-2">
                                   <Button variant="outline" size="sm" className="flex-1 h-8 text-xs" onClick={() => {setIsInterruptingMode(false); setInterruptFeedback('');}}>放弃</Button>
                                   <Button size="sm" className="flex-1 h-8 text-xs bg-indigo-600 text-white shadow-sm" disabled={!interruptFeedback.trim() || isActionLoading} onClick={() => handleCommand('INTERRUPT_REPLAN')}>
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

          <div className="border border-zinc-200 rounded-2xl bg-[#1C1C1E] overflow-hidden">
            <button onClick={() => setIsTerminalExpanded(!isTerminalExpanded)} className="w-full px-4 py-3 flex justify-between items-center bg-zinc-800/50">
              <span className="text-[10px] font-mono text-zinc-400 flex items-center gap-2"><TerminalSquare className="h-3.5 w-3.5" /> 执行追踪器 (Logs)</span>
              {isTerminalExpanded ? <ChevronUp className="h-4 w-4 text-zinc-500" /> : <ChevronDown className="h-4 w-4 text-zinc-500" />}
            </button>
            {isTerminalExpanded && (
              <div className="p-4 max-h-60 overflow-y-auto font-mono text-[10px] space-y-2 bg-black/20">
                {runtimeEvents.map(e => (
                  <div key={e.eventId} className="flex gap-2">
                    <span className="text-zinc-600 shrink-0">[{new Date(e.createdAt).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit', second: '2-digit'})}]</span>
                    <span className={e.message.includes('ERROR') ? 'text-red-400' : 'text-zinc-400'}>{e.message}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {runtimeTask.status === 'COMPLETED' && (() => {
            const isDelivered = lastDeliveredTime === runtimeTask.updatedAt;
            
            const evaluation = taskRuntime?.evaluation;
            const showRecommendations = evaluation?.verdict === 'PASS' && (evaluation.nextStepRecommendations?.length || 0) > 0;
            const sortedRecs = [...(evaluation?.nextStepRecommendations || [])].sort((a, b) => a.priority - b.priority);

            return (
              <div className="flex flex-col gap-4 animate-in fade-in slide-in-from-bottom-2">
                {showRecommendations && (
                  <div className="bg-indigo-50/50 border border-indigo-100 rounded-2xl p-3 shadow-sm">
                    <h4 className="text-[10px] font-bold text-indigo-600 uppercase tracking-widest mb-3 flex items-center gap-1.5">
                      <Lightbulb className="w-3.5 h-3.5" /> 智能连击推荐
                    </h4>
                    <div className="flex flex-col gap-2">
                      {sortedRecs.map((rec) => (
                        <div key={rec.recommendationId} className="bg-white border border-indigo-100/60 rounded-xl p-3 flex flex-col gap-2.5">
                          <div>
                            <div className="flex items-center justify-between gap-2 mb-1">
                              <span className="text-xs font-bold text-zinc-800">{rec.title}</span>
                              {rec.targetDeliverable && (
                                <span className="text-[9px] font-bold bg-indigo-100 text-indigo-600 px-1.5 py-0.5 rounded tracking-wider shrink-0">
                                  {rec.targetDeliverable}
                                </span>
                              )}
                            </div>
                            <p className="text-[11px] text-zinc-500 leading-relaxed">{rec.reason}</p>
                          </div>
                          <Button 
                            size="sm" 
                            disabled={!rec.executable || executingRecId !== null}
                            onClick={() => handleExecuteRecommendation(rec.recommendationId)}
                            className="bg-indigo-600 hover:bg-indigo-700 text-white w-full h-8"
                          >
                            {executingRecId === rec.recommendationId ? (
                              <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" />
                            ) : (
                              <ArrowRight className="w-3.5 h-3.5 mr-1" />
                            )}
                            {rec.actionLabel}
                          </Button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                <Button 
                  disabled={isDelivering || isDelivered} 
                  className={`w-full h-12 text-white rounded-2xl font-bold shadow-lg transition-all ${isDelivered ? 'bg-emerald-500' : 'bg-zinc-900'} disabled:opacity-60 disabled:cursor-not-allowed`} 
                  onClick={handleDeliver}
                >
                  {isDelivering ? <Loader2 className="h-5 w-5 mr-2 animate-spin" /> : (isDelivered ? <CheckCircle2 className="h-5 w-5 mr-2" /> : <Send className="h-5 w-5 mr-2" />)} 
                  {isDelivering ? '正在同步...' : isDelivered ? '✅ 已同步至群聊' : lastDeliveredTime ? '重新同步最新版本' : '确认并同步交付成果'}
                </Button>
              </div>
            );
          })()}

        </div>
      )}

      <AnimatePresence>
        {previewArtifactId && activePreviewArtifact && (
          <motion.div initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }} transition={{ type: 'spring', damping: 25 }} className="fixed inset-0 z-[100] bg-zinc-50 flex flex-col">
            <div className="h-14 px-4 flex items-center justify-between bg-white border-b border-zinc-200 pt-safe-top">
              <span className="font-bold text-sm truncate">{activePreviewArtifact.title}</span>
              <Button variant="ghost" size="icon" onClick={() => setPreviewArtifactId(null)} className="rounded-full h-9 w-9 bg-zinc-100"><X className="h-5 w-5" /></Button>
            </div>
            <div className="flex-1 overflow-y-auto p-4 pb-20">
              {activePreviewArtifact.type === 'DOC' ? (
                <DocPreviewCard status="COMPLETED" docUrl={activePreviewArtifact.url} docTitle={activePreviewArtifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(f, p) => handleCommand('REPLAN', f, { artifactPolicy: p, targetArtifactId: activePreviewArtifact.artifactId })} />
              ) : (
                <PptPreviewCard status="COMPLETED" pptUrl={activePreviewArtifact.url} pptTitle={activePreviewArtifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(f, p) => handleCommand('REPLAN', f, { artifactPolicy: p, targetArtifactId: activePreviewArtifact.artifactId })} onInterrupt={() => handleCommand('CANCEL')} />
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

    </div>
  );
}