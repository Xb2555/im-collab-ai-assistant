// src/components/chat/PptPreviewCard.tsx
import React, { useState } from 'react';
import { Presentation, ExternalLink, Loader2, StopCircle, RefreshCw, Wand2,AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { browserLauncher } from '@/services/os/launcher/browser';
import { isMobileNative } from '@/services/api/client'; // ✨ 引入移动端环境判断

// 1. 修改接口定义 (大概在第 14 行)
interface PptPreviewCardProps {
  status: 'EXECUTING' | 'COMPLETED' | 'FAILED' | string;
  pptUrl?: string;
  pptTitle?: string;
  canReplan?: boolean;
  isReplanning?: boolean;
  onInterrupt?: () => void;
  // ✨ 这里改成了 EDIT_EXISTING
  onReplan?: (feedback: string, policy: 'KEEP_EXISTING_CREATE_NEW' | 'EDIT_EXISTING') => void; 
}

export const PptPreviewCard = React.memo(function PptPreviewCard({ 
  status, 
  pptUrl, 
  pptTitle = '飞书幻灯片', 
  canReplan = false,
  isReplanning = false,
  onInterrupt,
  onReplan
}: PptPreviewCardProps) {
  const [feedback, setFeedback] = useState('');

  const handleOpenFeishu = async () => {
    if (pptUrl) {
      await browserLauncher.openUrl(pptUrl);
    }
  };

  // 2. 修改点击事件 (大概在第 35 行)
  const handleEditPPT = () => {
    if (!feedback.trim() || !onReplan) return;
    // ✨ 这里也改成 EDIT_EXISTING
    onReplan(feedback, 'EDIT_EXISTING');
    setFeedback('');
  };

  return (
    <div className="mt-4 border border-orange-200 rounded-xl overflow-hidden bg-white shadow-sm flex flex-col animate-in fade-in zoom-in-95 duration-300 relative">
      
      <div className="flex items-center justify-between px-3 py-2 bg-orange-50/50 border-b border-orange-100 shrink-0">
        <div className="flex items-center gap-2">
          <div className={`p-1.5 rounded-md ${status === 'COMPLETED' ? 'bg-orange-100 text-orange-600' : 'bg-orange-100 text-orange-500'}`}>
            <Presentation className="h-4 w-4" />
          </div>
          <span className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
            {pptTitle}
            {(status === 'EXECUTING' || isReplanning) && <Loader2 className="h-3 w-3 animate-spin text-orange-500" />}
          </span>
        </div>
        
        <div className="flex items-center gap-2">
          {status === 'EXECUTING' && onInterrupt && (
            <Button variant="ghost" size="sm" onClick={onInterrupt} className="h-7 text-xs text-red-500 hover:text-red-600 hover:bg-red-50">
              <StopCircle className="h-3.5 w-3.5 mr-1" /> 停止
            </Button>
          )}
          <Button variant="ghost" size="sm" className="h-7 text-xs text-zinc-500 hover:text-orange-600" disabled={!pptUrl} onClick={handleOpenFeishu}>
            <ExternalLink className="h-3.5 w-3.5 mr-1" /> 在飞书中打开
          </Button>
        </div>
      </div>

     {/* 将桌面端高度提升至 500px，触发飞书引擎自动放大缩放比例 */}
<div className="relative w-full h-[300px] lg:h-[520px] bg-zinc-50 flex flex-col items-center justify-center shrink-0 transform-gpu overflow-hidden">
        {(status === 'EXECUTING' || isReplanning) && !pptUrl ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-400 space-y-3 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px]">
            <Loader2 className="h-6 w-6 animate-spin text-orange-400" />
            <p className="text-xs">{isReplanning ? 'Agent 正在为您修改幻灯片...' : 'Agent 正在排版幻灯片...'}</p>
          </div>
        ) : isMobileNative ? (
          // ✨ 移动端原生环境：用优雅的引导按钮替代必定踩坑的 Iframe
          <div className="absolute inset-0 flex flex-col items-center justify-center bg-orange-50/30 space-y-3 p-6 text-center border-y border-orange-100/50">
            <div className="h-12 w-12 bg-orange-100 text-orange-500 rounded-full flex items-center justify-center shadow-sm">
              <Presentation className="h-6 w-6" />
            </div>
            <p className="text-xs text-zinc-500 font-medium leading-relaxed">受限于移动端系统安全策略<br/>请直接前往飞书 App 预览和编辑完整幻灯片</p>
            <Button size="sm" onClick={handleOpenFeishu} className="bg-orange-500 hover:bg-orange-600 text-white shadow-sm mt-2 rounded-full px-5">
               <ExternalLink className="h-3.5 w-3.5 mr-1.5" /> 唤起飞书 App
            </Button>
          </div>
        ) : (
          // 桌面端保持原样渲染 Iframe
          <iframe 
            src={pptUrl ? `${pptUrl}?from=embed` : 'about:blank'} 
            className="w-full h-full border-none bg-white transform-gpu" 
            title="Feishu PPT Preview" 
            loading="lazy" 
            sandbox="allow-scripts allow-same-origin allow-popups allow-forms"
          />
        )}
      </div>

      {['COMPLETED', 'CANCELLED', 'ABORTED', 'FAILED'].includes(status) && canReplan && (
        <div className="p-3 bg-zinc-50 border-t border-zinc-200 shrink-0 z-20">
          
          <div className="flex items-start gap-1.5 mb-2.5 text-[11px] text-amber-600 bg-amber-50/80 p-2 rounded-md border border-amber-200/60 leading-relaxed shadow-sm">
            <AlertCircle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
            <span><strong>严谨修改模式：</strong>请务必明确指明<strong className="text-amber-700 font-bold mx-0.5">幻灯片页码或具体标题</strong>（如：“把【第2页】的标题改为...”），以免破坏整份演示文稿结构。</span>
          </div>

          <div className="flex items-end gap-2">
            <textarea
              className="flex-1 text-sm bg-white border border-zinc-300 rounded-lg px-3 py-2 outline-none focus:ring-2 focus:ring-orange-500/20 focus:border-orange-500 transition-all placeholder:text-zinc-400 resize-none h-[52px]"
              placeholder="请明确指明页码..."
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  handleEditPPT();
                }
              }}
              disabled={isReplanning}
            />
            <Button 
              size="sm" 
              onClick={handleEditPPT} 
              disabled={!feedback.trim() || isReplanning}
              className="bg-orange-500 hover:bg-orange-600 text-white shadow-sm h-10 px-4 transition-all shrink-0"
            >
              {isReplanning ? <Loader2 className="h-4 w-4 mr-1 animate-spin" /> : <Wand2 className="h-4 w-4 mr-1" />}
              修改 PPT
            </Button>
          </div>
        </div>
      )}

    </div>
  );
});