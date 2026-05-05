// src/components/chat/PptPreviewCard.tsx
import React, { useState } from 'react';
import { Presentation, ExternalLink, Loader2, StopCircle, RefreshCw, Wand2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { browserLauncher } from '@/services/os/launcher/browser';

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
    <div className="mt-4 border border-orange-200 rounded-xl overflow-hidden bg-white shadow-sm flex flex-col animate-in fade-in zoom-in-95 duration-300">
      
      <div className="flex items-center justify-between px-3 py-2 bg-orange-50/50 border-b border-orange-100">
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

      <div className="relative w-full h-[240px] bg-zinc-50 flex flex-col items-center justify-center">
        {(status === 'EXECUTING' || isReplanning) && !pptUrl ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-400 space-y-3 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px]">
            <Loader2 className="h-6 w-6 animate-spin text-orange-400" />
            <p className="text-xs">{isReplanning ? 'Agent 正在为您修改幻灯片...' : 'Agent 正在排版幻灯片...'}</p>
          </div>
        ) : (
          <iframe src={pptUrl ? `${pptUrl}?from=embed` : 'about:blank'} className="w-full h-full border-none" title="Feishu PPT Preview" loading="lazy" />
        )}
      </div>

      {/* ✨ 新增：PPT 底部操作台 (支持原地修改) */}
      {/* ✨ 修改：放宽条件，允许取消(CANCELLED/ABORTED)和完成状态下都可以输入 Replan 反馈 */}
{['COMPLETED', 'CANCELLED', 'ABORTED', 'FAILED'].includes(status) && canReplan && (
        <div className="p-3 bg-white border-t border-zinc-100">
          <div className="flex items-center gap-2">
            <input
              type="text"
              className="flex-1 text-sm bg-zinc-50 border border-zinc-200 rounded-lg px-3 py-2 outline-none focus:ring-2 focus:ring-orange-500/20 focus:border-orange-500 transition-all placeholder:text-zinc-400"
              placeholder="例如：把第2页的标题改成'年度计划'..."
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && !e.nativeEvent.isComposing && handleEditPPT()}
              disabled={isReplanning}
            />
            <Button 
              size="sm" 
              onClick={handleEditPPT} 
              disabled={!feedback.trim() || isReplanning}
              className="bg-orange-500 hover:bg-orange-600 text-white shadow-sm transition-all"
            >
              {isReplanning ? <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" /> : <Wand2 className="h-3.5 w-3.5 mr-1" />}
              修改 PPT
            </Button>
          </div>
        </div>
      )}

    </div>
  );
});