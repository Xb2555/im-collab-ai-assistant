// src/components/chat/PlanReviewPanel.tsx
import React, { useEffect, useState, useCallback } from 'react';
import { plannerApi } from '@/services/api/planner';
import { DocPreviewCard } from './DocPreviewCard';
import { Loader2, CheckCircle2, XCircle, Clock } from 'lucide-react';
import { Button } from '@/components/ui/button';
// ✨ 使用新的 Sonner
import { toast } from 'sonner'; 
import type { TaskRuntimeVO, PlanCommandRequest } from '@/types/api';

interface PlanReviewPanelProps {
  taskId: string;
}

export const PlanReviewPanel: React.FC<PlanReviewPanelProps> = ({ taskId }) => {
  const [runtime, setRuntime] = useState<TaskRuntimeVO | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isActionLoading, setIsActionLoading] = useState(false);

  // 1. 核心动作：拉取/刷新运行时快照
  const fetchRuntime = useCallback(async (isSilent = false) => {
    if (!isSilent) {
      // ✨ 修复 React Hook 的同步渲染警告
      Promise.resolve().then(() => setIsLoading(true));
    }
    try {
      const data = await plannerApi.getTaskRuntime(taskId);
      setRuntime(data);
    } catch (error: unknown) { // ✨ 修复 unexpected any 报错
      const errorMsg = error instanceof Error ? error.message : String(error);
      toast.error('获取任务状态失败', {
        description: errorMsg || '请稍后重试',
      });
    } finally {
      setIsLoading(false);
    }
  }, [taskId]);

  useEffect(() => {
    fetchRuntime();
  }, [fetchRuntime]);

  // 2. 核心调度：执行命令并捕获 40900 并发冲突
  const executeTaskCommand = async (commandPayload: PlanCommandRequest) => {
    if (!runtime) return;
    setIsActionLoading(true);
    try {
      await plannerApi.executeCommand(taskId, commandPayload);
      await fetchRuntime(true);
    } catch (error: unknown) { // ✨ 修复 unexpected any 报错
      const errorMsg = error instanceof Error ? error.message : String(error);
      
      // 处理多端并发冲突 (40900)
      if (errorMsg.includes('40900') || errorMsg.includes('version conflict') || errorMsg.includes('冲突')) {
        toast.info('操作已在其他端完成', {
          description: '正在为您同步最新任务状态...',
          duration: 3000,
        });
        await fetchRuntime(true);
      } else {
        toast.error('操作失败', {
          description: errorMsg,
        });
      }
    } finally {
      setIsActionLoading(false);
    }
  };

  const handleConfirmExecute = () => {
    executeTaskCommand({
      action: 'CONFIRM_EXECUTE',
      version: runtime!.task.version,
    });
  };

  const handleCancelTask = () => {
    executeTaskCommand({
      action: 'CANCEL',
      version: runtime!.task.version,
    });
  };

  const handleArtifactReplan = (feedback: string, policy: 'KEEP_EXISTING_CREATE_NEW' | 'EDIT_EXISTING' = 'KEEP_EXISTING_CREATE_NEW') => {
    executeTaskCommand({
      action: 'REPLAN',
      feedback,
      artifactPolicy: policy,
      version: runtime!.task.version,
    });
  };

  if (isLoading && !runtime) {
    return (
      <div className="flex justify-center items-center h-40">
        <Loader2 className="animate-spin text-blue-500 h-6 w-6" />
      </div>
    );
  }

  if (!runtime) return null;

  const { task, steps, artifacts, actions } = runtime;

  return (
    <div className="flex flex-col gap-4 max-w-2xl w-full">
      {/* 模块 A：任务全局概览 */}
      <div className="p-4 border border-zinc-200 rounded-xl bg-white shadow-sm">
        <h3 className="text-lg font-semibold text-zinc-800">{task.title}</h3>
        <p className="text-sm text-zinc-500 mt-1">{task.goal}</p>
        <div className="flex items-center gap-2 mt-3">
          <span className="text-xs px-2 py-1 bg-zinc-100 rounded-md text-zinc-600 font-medium">
            版本: V{task.version}
          </span>
          <span className={`text-xs px-2 py-1 rounded-md font-medium ${
            task.status === 'COMPLETED' ? 'bg-green-100 text-green-700' :
            task.status === 'FAILED' ? 'bg-red-100 text-red-700' :
            task.status === 'WAITING_APPROVAL' ? 'bg-orange-100 text-orange-700' :
            'bg-blue-100 text-blue-700'
          }`}>
            当前状态: {task.status}
          </span>
        </div>
      </div>

      {/* 模块 B：子任务步骤条 */}
      {steps && steps.length > 0 && (
        <div className="p-4 border border-zinc-200 rounded-xl bg-white shadow-sm">
          <h4 className="text-sm font-semibold text-zinc-800 mb-3">执行计划</h4>
          <div className="space-y-3">
            {steps.map((step, idx) => (
              <div key={step.stepId} className="flex items-start gap-3">
                <div className="mt-0.5">
                  {step.status === 'COMPLETED' ? <CheckCircle2 className="h-4 w-4 text-green-500" /> :
                   step.status === 'RUNNING' ? <Loader2 className="h-4 w-4 text-blue-500 animate-spin" /> :
                   step.status === 'FAILED' ? <XCircle className="h-4 w-4 text-red-500" /> :
                   <Clock className="h-4 w-4 text-zinc-300" />}
                </div>
                <div>
                  <p className={`text-sm ${step.status === 'READY' ? 'text-zinc-500' : 'text-zinc-800 font-medium'}`}>
                    {idx + 1}. {step.name}
                  </p>
                  {step.inputSummary && <p className="text-xs text-zinc-500 mt-0.5">{step.inputSummary}</p>}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 模块 C：产物卡片渲染区 */}
      {artifacts && artifacts.length > 0 && (
        <div className="space-y-4">
          {artifacts.map(artifact => {
            if (artifact.type === 'DOC') {
              return (
                <DocPreviewCard 
                  key={artifact.artifactId}
                  status={task.status} 
                  docUrl={artifact.url}
                  docTitle={artifact.title}
                  canReplan={actions.canReplan}
                  isReplanning={isActionLoading} // ✨ 修复了组件参数绑定的错误
                  onReplan={handleArtifactReplan} 
                />
              );
            }
            return null;
          })}
        </div>
      )}

      {/* 模块 D：全局操作按钮区 */}
      {task.status === 'WAITING_APPROVAL' && (
        <div className="flex items-center gap-3 mt-2">
          {actions.canConfirm && (
            <Button 
              onClick={handleConfirmExecute} 
              disabled={isActionLoading}
              className="bg-blue-600 hover:bg-blue-700 text-white w-32"
            >
              {isActionLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : '确认执行计划'}
            </Button>
          )}
          {actions.canCancel && (
            <Button 
              variant="outline"
              onClick={handleCancelTask} 
              disabled={isActionLoading}
            >
              取消任务
            </Button>
          )}
        </div>
      )}
    </div>
  );
};