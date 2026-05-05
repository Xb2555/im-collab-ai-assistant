// src/components/chat/DocPreviewCard.tsx
import React, { useState } from 'react';
import { 
  FileText, ExternalLink, Loader2, Send, RefreshCw, 
  AlertCircle, Wand2, Check, X, FileEdit 
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { browserLauncher } from '@/services/os/launcher/browser';
import { useTaskStore } from '@/store/useTaskStore';
import { plannerApi } from '@/services/api/planner';
import { toast } from 'sonner';

interface DocPreviewCardProps {
  status: 'GENERATING' | 'COMPLETED' | 'ABORTED' | string;
  docUrl?: string;
  docTitle?: string;
  canReplan?: boolean;
  isReplanning?: boolean;
  onReplan?: (feedback: string, policy: 'KEEP_EXISTING_CREATE_NEW') => void;
}

// 辅助函数：将后端的 semanticAction 翻译成人类可读的动作
const translateSemanticAction = (action: string) => {
  const map: Record<string, string> = {
    'INSERT_INLINE_TEXT': '在行内插入文本',
    'INSERT_BLOCK_AFTER_ANCHOR': '在指定位置后插入段落',
    'APPEND_SECTION_TO_DOCUMENT_END': '在文档末尾追加内容',
    'REWRITE_SINGLE_BLOCK': '重写指定段落',
    'DELETE_BLOCK': '删除指定段落',
    'CONVERT_TEXT_TO_TABLE': '将文本转换为表格'
  };
  return map[action] || action.replace(/_/g, ' ');
};

export const DocPreviewCard = React.memo(function DocPreviewCard({ 
  status, 
  docUrl, 
  docTitle = '飞书云文档', 
  canReplan = false,
  isReplanning = false,
  onReplan
}: DocPreviewCardProps) {
  
  const activeTaskId = useTaskStore(state => state.activeTaskId);
  const [feedback, setFeedback] = useState('');
  
  // ✨ 新增：局部精修相关的状态
  const [isIterating, setIsIterating] = useState(false);
  const [isApproving, setIsApproving] = useState(false);
  const [iterationPlan, setIterationPlan] = useState<any | null>(null);
  
  const embedUrl = docUrl ? (docUrl.includes('?') ? `${docUrl}&from=embed` : `${docUrl}?from=embed`) : '';

  const handleOpenFeishu = async () => {
    if (docUrl) {
      await browserLauncher.openUrl(docUrl);
    }
  };

  // 🔴 宏观重跑 (打回重造)
  const handleSubmitReplan = () => {
    if (!feedback.trim() || !onReplan) return;
    onReplan(feedback, 'KEEP_EXISTING_CREATE_NEW');
    setFeedback(''); 
  };

  // ✨ 🟢 微观精修 (触发 5.1 接口获取 Diff 计划)
  const handleIterate = async () => {
    if (!feedback.trim() || !activeTaskId || !docUrl) return;
    setIsIterating(true);
    try {
      const res = await plannerApi.iterateDocument({
        taskId: activeTaskId,
        docUrl: docUrl,
        instruction: feedback
      });
      
      // 🚨 强力拦截：如果后端明明解析失败（UNRESOLVED），却还是返回了 editPlan
      if (res.editPlan && res.editPlan.anchorType === 'UNRESOLVED') {
         toast.warning('Agent 找不到修改位置', { 
           description: res.preview || res.editPlan.targetTitle || '请提供更明确的章节标题或序号（例如：“在【2.3 水果拼盘】下方插入...”）' 
         });
         setIterationPlan(null);
         return;
      }

      // 🚨 强力拦截：如果后端根本没有生成新内容
      if (res.editPlan && !res.editPlan.generatedContent?.trim()) {
         toast.warning('修改计划生成失败', { 
           description: 'Agent 未能生成有效的替换/插入文本，请调整您的指令。' 
         });
         setIterationPlan(null);
         return;
      }

      // 只有真正合法的计划，才允许弹窗
      if (res.editPlan) {
        setIterationPlan(res.editPlan);
      } else {
        toast.warning('无法生成修改计划', { description: res.summary || '请尝试换一种描述方式' });
      }
    } catch(e: any) {
      // 优雅翻译后端的 40000 报错
      const errorMsg = e.message || String(e);
      if (errorMsg.includes('未指定任何文档操作目标或位置') || errorMsg.includes('40000')) {
        toast.warning('Agent 需要更明确的坐标', { 
          description: '请指明要修改的具体标题或段落，例如：“在【2.3 水果拼盘】下方，补充预算300左右”' 
        });
      } else {
        toast.error('精修请求失败', { description: errorMsg });
      }
    } finally {
      setIsIterating(false);
    }
  };

  // ✨ 🟢 审批精修计划 (触发 5.2 接口)
  const handleApprove = async (action: 'APPROVE' | 'REJECT') => {
    if (!activeTaskId) return;
    setIsApproving(true);
    try {
      await plannerApi.approveDocumentIteration(activeTaskId, { action });
      if (action === 'APPROVE') {
        toast.success('文档已更新', { description: 'Agent 已成功将修改应用到飞书文档' });
        // 可选：触发 Iframe 刷新，或者让用户自己看飞书文档的协同刷新
      } else {
        toast.info('已拒绝修改提议');
      }
    } catch(e: any) {
      toast.error('审批操作失败', { description: e.message || String(e) });
    } finally {
      setIsApproving(false);
      setIterationPlan(null); // 关闭弹窗
      setFeedback(''); // 清空输入框
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
      e.preventDefault();
      // 默认回车触发精修（体验更好）
      handleIterate();
    }
  };

  return (
    <div className="mt-4 border border-zinc-200 rounded-xl overflow-hidden bg-white shadow-sm flex flex-col animate-in fade-in zoom-in-95 duration-300 relative">
      
      {/* 1. 顶部状态栏 */}
      <div className="flex items-center justify-between px-3 py-2 bg-zinc-50 border-b border-zinc-200">
        <div className="flex items-center gap-2">
          <div className={`p-1.5 rounded-md ${status === 'COMPLETED' ? 'bg-green-100 text-green-600' : 'bg-blue-100 text-blue-600'}`}>
            <FileText className="h-4 w-4" />
          </div>
          <span className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
            {docTitle}
            {(status === 'GENERATING' || isReplanning) && <Loader2 className="h-3 w-3 animate-spin text-blue-500" />}
          </span>
        </div>
        
        <Button variant="ghost" size="sm" className="h-7 text-xs text-zinc-500 hover:text-blue-600" disabled={!docUrl} onClick={handleOpenFeishu}>
          <ExternalLink className="h-3.5 w-3.5 mr-1" /> 在飞书中打开
        </Button>
      </div>

      {/* 2. 中间 Iframe 预览区 */}
      <div className="relative w-full h-[300px] bg-zinc-100/50">
        {(status === 'GENERATING' || isReplanning) && !docUrl ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-400 space-y-3 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px]">
            <Loader2 className="h-6 w-6 animate-spin text-blue-400" />
            <p className="text-xs">{isReplanning ? 'Agent 正在为您重新规划和撰写新版文档...' : 'Agent 正在努力撰写文档...'}</p>
          </div>
        ) : (
          <>
            <iframe src={embedUrl || 'about:blank'} className="w-full h-full border-none" title="Feishu Doc Preview" loading="lazy" />
            {isReplanning && (
               <div className="absolute inset-0 bg-white/60 backdrop-blur-[1px] flex items-center justify-center z-10 pointer-events-none">
                 <div className="bg-white px-4 py-2 rounded-full shadow-md border border-blue-100 flex items-center gap-2 text-blue-600 text-sm font-medium">
                   <RefreshCw className="h-4 w-4 animate-spin" /> 生成新版本中...
                 </div>
               </div>
            )}
          </>
        )}
      </div>

      {/* 3. 底部操作区 */}
      {/* ✨ 修改：放宽条件，允许取消(CANCELLED/ABORTED)和完成状态下都可以输入 Replan 反馈 */}
{['COMPLETED', 'CANCELLED', 'ABORTED', 'FAILED'].includes(status) && canReplan && (
        <div className="p-3 bg-white border-t border-zinc-100 relative z-20">
          <div className="flex items-center gap-2">
            <input
              type="text"
              className="flex-1 text-sm bg-zinc-50 border border-zinc-200 rounded-lg px-3 py-2 outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500 transition-all placeholder:text-zinc-400"
              // ✨ 换成强引导文案
              placeholder="请明确指出位置（如：在【2.3 水果拼盘】标题下，补充预算300元）"
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={isReplanning || isIterating}
            />
            
            {/* ✨ 核心：新增局部精修按钮 */}
            <Button 
              size="sm" 
              onClick={handleIterate} 
              disabled={!feedback.trim() || isReplanning || isIterating || isApproving}
              className="bg-indigo-600 hover:bg-indigo-700 text-white shadow-sm transition-all"
            >
              {isIterating ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : <Wand2 className="h-3.5 w-3.5 mr-1" />}
              局部精修
            </Button>
            
            {/* 降级的重跑按钮 */}
            <Button 
              size="sm" 
              variant="outline"
              onClick={handleSubmitReplan} 
              disabled={!feedback.trim() || isReplanning || isIterating}
              className="text-zinc-600"
              title="保留旧文档，完全重新生成一份新文档"
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      )}

      {/* ✨ 4. 局部精修的 Diff 浮层 (模态框) */}
      {iterationPlan && (
        <div className="absolute inset-0 z-50 flex items-center justify-center p-4 bg-zinc-900/40 backdrop-blur-[2px] animate-in fade-in duration-200">
          <div className="bg-white rounded-xl shadow-2xl border border-zinc-200 w-full max-w-[95%] max-h-[95%] flex flex-col overflow-hidden animate-in zoom-in-95 duration-200">
            
            <div className="px-4 py-3 border-b border-zinc-100 flex items-center justify-between bg-zinc-50/80">
              <h3 className="font-semibold text-zinc-800 flex items-center gap-2">
                <FileEdit className="h-4 w-4 text-indigo-600" /> Agent 修改提议
              </h3>
              <span className={`text-[10px] px-2 py-0.5 rounded-md font-bold ${
                iterationPlan.riskLevel === 'HIGH' ? 'bg-red-100 text-red-600' : 
                iterationPlan.riskLevel === 'MEDIUM' ? 'bg-amber-100 text-amber-600' : 
                'bg-green-100 text-green-600'
              }`}>
                {iterationPlan.riskLevel || 'LOW'} RISK
              </span>
            </div>

            <div className="p-4 overflow-y-auto flex-1 flex flex-col gap-4 text-sm">
              <div className="bg-indigo-50/50 border border-indigo-100 rounded-lg p-3">
                <span className="text-xs font-bold text-indigo-400 block mb-1">执行动作</span>
                <span className="text-indigo-900 font-medium">{translateSemanticAction(iterationPlan.semanticAction)}</span>
              </div>
              
              <div className="flex flex-col gap-2">
                <span className="text-xs font-bold text-zinc-400">预览修改内容：</span>
                <div className="bg-[#F8F9FA] border border-zinc-200 rounded-lg p-3 text-zinc-700 whitespace-pre-wrap font-mono text-[13px] leading-relaxed relative overflow-hidden shadow-inner">
                  <div className="absolute left-0 top-0 bottom-0 w-1 bg-green-400"></div>
                  {/* 这里不用加后备文本了，因为我们在 handleIterate 里已经拦截了空文本 */}
                  {iterationPlan.generatedContent}
                </div>
              </div>
            </div>

            <div className="p-3 bg-zinc-50 border-t border-zinc-100 flex justify-end gap-2 shrink-0">
              <Button size="sm" variant="outline" onClick={() => handleApprove('REJECT')} disabled={isApproving} className="text-zinc-600 border-zinc-300">
                <X className="h-3.5 w-3.5 mr-1" /> 拒绝
              </Button>
              <Button size="sm" onClick={() => handleApprove('APPROVE')} disabled={isApproving} className="bg-indigo-600 hover:bg-indigo-700 text-white">
                {isApproving ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : <Check className="h-3.5 w-3.5 mr-1" />}
                同意并应用到文档
              </Button>
            </div>

          </div>
        </div>
      )}

    </div>
  );
});