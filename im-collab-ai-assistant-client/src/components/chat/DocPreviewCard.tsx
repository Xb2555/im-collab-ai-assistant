// src/components/chat/DocPreviewCard.tsx
import React from 'react'; // ✨ 新增：引入 React
import { FileText, ExternalLink, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { browserLauncher } from '@/services/os/launcher/browser';

interface DocPreviewCardProps {
  status: 'GENERATING' | 'COMPLETED' | 'ABORTED';
  docUrl?: string;
  docTitle?: string;
}

// ✨ 核心修复：使用 React.memo 包裹组件，切断不必要的重渲染传染
export const DocPreviewCard = React.memo(function DocPreviewCard({ 
  status, 
  docUrl, 
  docTitle = '飞书云文档生成中...' 
}: DocPreviewCardProps) {
  
  const embedUrl = docUrl ? (docUrl.includes('?') ? `${docUrl}&from=embed` : `${docUrl}?from=embed`) : '';

  const handleOpenFeishu = async () => {
    if (docUrl) {
      await browserLauncher.openUrl(docUrl);
    }
  };

  return (
    <div className="mt-4 border border-zinc-200 rounded-xl overflow-hidden bg-white shadow-sm flex flex-col animate-in fade-in zoom-in-95 duration-300">
      <div className="flex items-center justify-between px-3 py-2 bg-zinc-50 border-b border-zinc-200">
        <div className="flex items-center gap-2">
          <div className={`p-1.5 rounded-md ${status === 'COMPLETED' ? 'bg-green-100 text-green-600' : 'bg-blue-100 text-blue-600'}`}>
            <FileText className="h-4 w-4" />
          </div>
          <span className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
            {docTitle}
            {status === 'GENERATING' && <Loader2 className="h-3 w-3 animate-spin text-blue-500" />}
          </span>
        </div>
        
        <Button 
          variant="ghost" 
          size="sm" 
          className="h-7 text-xs text-zinc-500 hover:text-blue-600" 
          disabled={!docUrl}
          onClick={handleOpenFeishu}
        >
          <ExternalLink className="h-3.5 w-3.5 mr-1" /> 在飞书中打开
        </Button>
      </div>

      <div className="relative w-full h-[300px] bg-zinc-100/50">
        {status === 'GENERATING' && !docUrl ? (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-zinc-400 space-y-3 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px]">
            <Loader2 className="h-6 w-6 animate-spin text-blue-400" />
            <p className="text-xs">Agent 正在努力撰写文档区块...</p>
            <div className="w-3/4 space-y-2 mt-4 opacity-50">
              <div className="h-2 bg-zinc-300 rounded w-full"></div>
              <div className="h-2 bg-zinc-300 rounded w-5/6"></div>
              <div className="h-2 bg-zinc-300 rounded w-4/6"></div>
            </div>
          </div>
        ) : (
          <iframe 
            src={embedUrl || 'about:blank'} 
            className="w-full h-full border-none"
            title="Feishu Doc Preview"
            loading="lazy" // ✨ 性能优化：懒加载
          />
        )}
      </div>
    </div>
  );
}); // ✨ 闭合 React.memo