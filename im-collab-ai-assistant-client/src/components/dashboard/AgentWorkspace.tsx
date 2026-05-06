// src/components/dashboard/AgentWorkspace.tsx
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  Bot, Loader2, X, AlertCircle, RefreshCw, CheckCircle2, Play,
  CircleDashed, Check, History, Send, TerminalSquare, ChevronDown, ChevronUp, StopCircle
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

const isRuntimeStepRunning = (status?: string) =>
  status === 'IN_PROGRESS' || status === 'EXECUTING' || status === 'RUNNING';

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
    planPreview,
    taskRuntime,
    setPlanPreview,
    setTaskRuntime,
    clearTask,
    isPlanning, // 👈 从 Store 中获取规划状态
  } = useTaskStore();

  const [retryFeedback, setRetryFeedback] = useState('');
  const [clarifyAnswer, setClarifyAnswer] = useState('');
  const [isTerminalExpanded, setIsTerminalExpanded] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [isReplanningMode, setIsReplanningMode] = useState(false);
  const [replanFeedback, setReplanFeedback] = useState('');
  const [isActionLoading, setIsActionLoading] = useState(false);
const [isDelivering, setIsDelivering] = useState(false);
  const runtimeTask = taskRuntime?.task;
  const runtimeActions = taskRuntime?.actions;
  const runtimeSteps = taskRuntime?.steps ?? [];
  const runtimeArtifacts = taskRuntime?.artifacts ?? [];

  const handleCommand = async (
    action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL' | 'RETRY_FAILED' | 'RESUME', 
    customFeedback?: string,
    extraPayload?: { artifactPolicy?: 'AUTO' | 'EDIT_EXISTING' | 'CREATE_NEW' | 'KEEP_EXISTING_CREATE_NEW', targetArtifactId?: string }
  ) => {
    if (!activeTaskId || isActionLoading) return;
    setIsActionLoading(true);
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
      const deliverText = `🎉 【Agent-Pilot 工作汇报】任务已完成！\n\n📝 项目文档：${docLink || '暂无'}\n📊 汇报演示：${pptLink || '暂无'}\n\n💡 您可以通过上方的链接直接预览或编辑。如有新需求，请随时在群内唤醒我。`;
      await imApi.sendMessage({ chatId: activeChatId, text: deliverText, idempotencyKey: crypto.randomUUID() });
      toast.success('🎉 总结与交付成功！', { description: 'Agent 已将成果同步至飞书协作群。' });
      setTimeout(() => clearTask(), 500);
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
        // 👈 把你心心念念的骨架屏完整接回来了！
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
          <div className="flex flex-col items-center justify-center h-full space-y-3 text-center text-zinc-400 mt-32">
            <Bot className="h-8 w-8 opacity-50" />
            <p className="text-sm">在左侧输入指令，唤醒 Agent</p>
          </div>
        )
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
                  {(runtimeActions?.canCancel || ['PLANNING', 'CLARIFYING', 'WAITING_APPROVAL'].includes(runtimeTask.status)) && (
                    <Button variant="ghost" size="icon" className="h-6 w-6 text-zinc-400 hover:text-red-600 hover:bg-red-50 rounded-full ml-1" 
                      onClick={() => runtimeActions?.canCancel ? handleCommand('CANCEL') : (clearTask(), toast.info('任务已强制关闭'))} disabled={isCancelling} >
                      {isCancelling ? <Loader2 className="w-3.5 h-3.5 animate-spin text-red-500" /> : <X className="w-4 h-4" />}
                    </Button>
                  )}
                </div>
                <span className="text-[9px] font-mono text-zinc-400">{runtimeTask.updatedAt ? new Date(runtimeTask.updatedAt).toLocaleTimeString() : new Date().toLocaleTimeString()}</span>
              </div>
            </div>
            <p className="text-xs text-zinc-500 mt-2 leading-relaxed">{runtimeTask.goal || planPreview?.summary || '已收到您的意图，正在执行'}</p>
            
              {/* ✨ 新增：针对 PLANNING 状态的无缝衔接动画（对齐移动端） */}
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
                  {planPreview?.capabilityHints && planPreview.capabilityHints.length > 0 && (
                    <div className="text-[11px] text-blue-600/70 bg-blue-100/40 p-2 rounded-md border border-blue-100/50 leading-relaxed">
                      <span className="font-bold block mb-0.5 text-blue-500">💡 Agent 提示：</span>{planPreview.capabilityHints.join(' ')}
                    </div>
                  )}
                  <textarea value={clarifyAnswer} onChange={(e) => setClarifyAnswer(e.target.value)} placeholder="请输入回答..." className="w-full text-xs p-3 rounded-lg border border-blue-200 outline-none resize-none bg-white focus:border-blue-500" rows={2} disabled={isActionLoading} />
                  <Button size="sm" className="bg-blue-600 hover:bg-blue-700 text-white w-full transition-all relative overflow-hidden" disabled={!clarifyAnswer.trim() || isActionLoading} onClick={() => { toast.success('已收到您的补充', { description: 'Agent 正在为您重新推演执行计划...' }); handleCommand('RESUME', clarifyAnswer); }}>
                    {isActionLoading ? <span className="flex items-center gap-2"><Loader2 className="animate-spin h-4 w-4" /> 🤖 正在火速规划中...</span> : '提交回答'}
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
              <h4 className="text-[11px] font-bold text-zinc-400 uppercase tracking-wider mb-4">Execution Steps</h4>
              <div className="space-y-4 relative before:absolute before:inset-0 before:ml-[11px] before:h-full before:w-[2px] before:bg-zinc-100">
                {runtimeSteps.map((step: RuntimeStepVO, idx: number) => {
                  const isTaskCompleted = runtimeTask.status === 'COMPLETED';
                  const displayStatus = isTaskCompleted ? 'COMPLETED' : step.status;
                  const isRunning = isRuntimeStepRunning(displayStatus);
                  const duration = getDuration(step.startedAt, step.endedAt || (isTaskCompleted ? runtimeTask.updatedAt : null));
                  return (
                    <div key={step.stepId || idx} className="relative flex items-start gap-3">
                      <div className={`flex items-center justify-center w-6 h-6 rounded-full border-[3px] border-white z-10 mt-0.5 ${displayStatus === 'COMPLETED' ? 'bg-zinc-800 text-white' : isRunning ? 'bg-blue-500 text-white' : displayStatus === 'FAILED' ? 'bg-red-500 text-white' : 'bg-zinc-200 text-zinc-400'}`}>
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
                          <div className="mt-3 p-3 bg-blue-50/50 border border-blue-100 rounded-lg relative overflow-hidden group">
                            <div className="flex items-start justify-between mb-2">
                              <span className="text-[11px] font-medium text-blue-700 flex items-center gap-1.5 leading-relaxed">
                                <Bot className="w-4 h-4 animate-bounce text-blue-500"/> 
                                {step.type === 'PPT_CREATE' || step.type === 'PPT' ? '正在为您精雕细琢每一页幻灯片，大概需要1分钟，喝口水休息一下吧 ☕' : 'Agent 正在飞速执行作业中，请稍候...'}
                              </span>
                              <span className="text-[10px] text-blue-400 font-mono shrink-0 ml-2 mt-0.5">预计 60s</span>
                            </div>
                            <div className="w-full h-1.5 bg-blue-200/50 rounded-full overflow-hidden shadow-inner mb-3">
                              <motion.div className="h-full bg-blue-500 relative" initial={{ width: `${step.progress || 0}%` }} animate={{ width: step.progress >= 90 ? `${step.progress}%` : "90%" }} transition={{ duration: step.progress >= 100 ? 0.5 : 60, ease: "easeOut" }}>
                                <div className="absolute top-0 left-0 w-full h-full bg-white/20 animate-[shimmer_1s_infinite]" />
                              </motion.div>
                            </div>
                            <div className="flex items-end justify-between mt-2">
                              <div className="flex-1 space-y-1.5 opacity-40 mr-4">
                                <div className="h-1.5 bg-blue-300 rounded w-full animate-pulse"></div>
                                <div className="h-1.5 bg-blue-300 rounded w-5/6 animate-pulse" style={{ animationDelay: '150ms' }}></div>
                              </div>
                              <Button variant="ghost" size="sm" disabled={isCancelling} onClick={() => handleCommand('CANCEL')} className="h-6 text-[10px] px-2 text-red-500 hover:text-red-600 hover:bg-red-100 border border-transparent hover:border-red-200 transition-colors">
                                {isCancelling ? <Loader2 className="w-3 h-3 mr-1 animate-spin" /> : <StopCircle className="w-3 h-3 mr-1" />} 停止执行
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

          {runtimeArtifacts.length > 0 && (
            <div className="flex flex-col gap-3">
              {runtimeArtifacts.map((artifact: RuntimeArtifactVO) => {
                if (artifact.type === 'DOC') return <DocPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' || artifact.status === 'UPDATED' ? 'COMPLETED' : 'GENERATING'} docUrl={artifact.url} docTitle={artifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(feedback, policy) => handleCommand('REPLAN', feedback, { artifactPolicy: policy, targetArtifactId: artifact.artifactId })} />;
                if (artifact.type === 'PPT') return <PptPreviewCard key={artifact.artifactId} status={artifact.status === 'CREATED' || artifact.status === 'UPDATED' ? 'COMPLETED' : 'EXECUTING'} pptUrl={artifact.url} pptTitle={artifact.title} canReplan={runtimeActions?.canReplan} isReplanning={isActionLoading} onReplan={(feedback, policy) => handleCommand('REPLAN', feedback, { artifactPolicy: policy, targetArtifactId: artifact.artifactId })} onInterrupt={() => handleCommand('CANCEL')} />;
                return null;
              })}
            </div>
          )}

          {runtimeTask.status === 'COMPLETED' && (
            <Button 
              className="w-full h-10 font-bold bg-zinc-900 hover:bg-zinc-800 text-white shadow-md animate-in zoom-in duration-500 disabled:opacity-50" 
              onClick={handleDeliver}
              disabled={isDelivering}
            >
              {isDelivering ? <Loader2 className="h-4 w-4 mr-2 animate-spin" /> : <Send className="h-4 w-4 mr-2" />} 
              {isDelivering ? '正在同步...' : '总结与交付群聊'}
            </Button>
          )}

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