// src/components/chat/PptPreviewCard.tsx
import React from 'react'; // ✨ 新增：引入 React
import { Presentation, ExternalLink, Loader2, PlayCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { browserLauncher } from '@/services/os/launcher/browser';

interface PptPreviewCardProps {
  status: 'PENDING' | 'EXECUTING' | 'COMPLETED' | 'FAILED' | 'ABORTED';
  pptUrl?: string;
  pptTitle?: string;
  slideCount?: number;
  onInterrupt?: () => void;
}

// ✨ 核心修复：使用 React.memo 包裹组件
export const PptPreviewCard = React.memo(function PptPreviewCard({ 
  status, 
  pptUrl, 
  pptTitle = '飞书汇报演示文稿生成中...',
  slideCount = 0,
  onInterrupt 
}: PptPreviewCardProps) {
  
  if (status === 'PENDING') return null;

  const handleOpenFeishu = async () => {
    if (pptUrl) {
      await browserLauncher.openUrl(pptUrl);
    }
  };

  return (
    <div className="mt-4 border border-zinc-200 rounded-xl overflow-hidden bg-white shadow-sm flex flex-col animate-in fade-in zoom-in-95 duration-300">
      <div className="flex items-center justify-between px-3 py-2 bg-indigo-50 border-b border-indigo-100">
        <div className="flex items-center gap-2">
          <div className={`p-1.5 rounded-md ${status === 'COMPLETED' ? 'bg-indigo-100 text-indigo-600' : 'bg-blue-100 text-blue-600'}`}>
            <Presentation className="h-4 w-4" />
          </div>
          <div className="flex flex-col">
            <span className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
              {pptTitle}
              {status === 'EXECUTING' && <Loader2 className="h-3 w-3 animate-spin text-blue-500" />}
            </span>
            {slideCount > 0 && <span className="text-[10px] text-indigo-500">共 {slideCount} 页幻灯片</span>}
          </div>
        </div>
        
        <div className="flex items-center gap-2">
          {status === 'EXECUTING' && onInterrupt && (
            <Button variant="outline" size="sm" className="h-7 text-xs text-red-600 hover:text-red-700 hover:bg-red-50 border-red-200" onClick={onInterrupt}>
              停止生成
            </Button>
          )}
          {status === 'COMPLETED' && (
            <Button variant="default" size="sm" className="h-7 text-xs bg-indigo-600 hover:bg-indigo-700">
              <PlayCircle className="h-3.5 w-3.5 mr-1" /> 演练模式
            </Button>
          )}
        </div>
      </div>

      <div className="relative w-full aspect-video bg-zinc-800">
        {status === 'EXECUTING' && !pptUrl ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-400 space-y-4">
            <Loader2 className="h-8 w-8 animate-spin text-indigo-400" />
            <p className="text-sm font-medium text-zinc-300">Agent 正在排版幻灯片...</p>
            <div className="w-2/3 space-y-4 mt-4 opacity-50 flex flex-col items-center">
              <div className="h-4 bg-zinc-600 rounded w-3/4"></div>
              <div className="h-2 bg-zinc-600 rounded w-1/2"></div>
            </div>
          </div>
        ) : (
          <iframe 
            src={pptUrl || 'about:blank'} 
            className="w-full h-full border-none bg-white"
            title="Feishu PPT Preview"
            loading="lazy" // ✨ 性能优化：懒加载
          />
        )}
      </div>

      {status === 'COMPLETED' && (
        <div className="bg-zinc-50 px-3 py-2 border-t border-zinc-200 flex justify-end">
          <Button 
            variant="ghost" 
            size="sm" 
            className="h-7 text-xs text-zinc-500 hover:text-indigo-600" 
            disabled={!pptUrl} 
            onClick={handleOpenFeishu}
          >
            <ExternalLink className="h-3.5 w-3.5 mr-1" /> 在飞书幻灯片中打开
          </Button>
        </div>
      )}
    </div>
  );
}); // ✨ 闭合 React.memo