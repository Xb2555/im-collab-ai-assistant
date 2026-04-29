// src/components/chat/InviteMemberModal.tsx
import { useState } from 'react';
import { useRequest } from 'ahooks';
import { imApi } from '@/services/api/im';
import type { UserItem } from '@/services/api/im';
import { Button } from '@/components/ui/button';
import { Search, X, Check, UserPlus, Link as LinkIcon, Copy, Loader2 } from 'lucide-react';

interface InviteMemberModalProps {
  isOpen: boolean;
  onClose: () => void;
  chatId: string; 
}

export function InviteMemberModal({ isOpen, onClose, chatId }: InviteMemberModalProps) {
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedUsers, setSelectedUsers] = useState<UserItem[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  
  // ✨ 新增：分享链接相关状态
  const [shareLink, setShareLink] = useState<string>('');
  const [isGeneratingLink, setIsGeneratingLink] = useState(false);
  const [isCopied, setIsCopied] = useState(false);

  // 搜索组织内用户
  const { data: searchResults, loading: isSearching } = useRequest(
    () => imApi.searchUsers(searchKeyword),
    { debounceWait: 500, refreshDeps: [searchKeyword] }
  );

  const handleClose = () => {
    setSearchKeyword('');
    setSelectedUsers([]);
    setShareLink('');
    setIsCopied(false);
    onClose();
  };

  const toggleUser = (user: UserItem) => {
    if (selectedUsers.find(u => u.openId === user.openId)) {
      setSelectedUsers(selectedUsers.filter(u => u.openId !== user.openId));
    } else {
      setSelectedUsers([...selectedUsers, user]);
    }
  };

  // 1. 内部邀请逻辑
  const handleInvite = async () => {
    if (selectedUsers.length === 0) return;
    try {
      setIsSubmitting(true);
      const result = await imApi.invite({
        chatId: chatId,
        userOpenIds: selectedUsers.map(u => u.openId)
      });
      if (result.pendingApprovalOpenIds.length > 0) {
        alert('部分用户需要审批才能入群');
      } else {
        alert('邀请发送成功！');
      }
      handleClose();
    } catch (e: any) {
      alert('邀请失败: ' + e.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  // ✨ 2. 生成外部链接逻辑
  const handleGenerateLink = async () => {
    try {
      setIsGeneratingLink(true);
      const data = await imApi.createShareLink({ chatId, validityPeriod: 'week' });
      if (data.shareLink) {
        setShareLink(data.shareLink);
      }
    } catch (e: any) {
      alert('生成链接失败: ' + e.message);
    } finally {
      setIsGeneratingLink(false);
    }
  };

  // ✨ 3. 复制链接到剪贴板
  const handleCopy = () => {
    if (!shareLink) return;
    navigator.clipboard.writeText(shareLink);
    setIsCopied(true);
    setTimeout(() => setIsCopied(false), 2000);
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/40 backdrop-blur-sm">
      <div className="w-[500px] flex flex-col bg-white rounded-xl shadow-2xl border border-zinc-200">
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-100">
          <h2 className="text-base font-semibold text-zinc-800 flex items-center gap-2">
            <UserPlus className="h-4 w-4" /> 邀请新成员加入
          </h2>
          <Button variant="ghost" size="icon" onClick={handleClose} className="h-8 w-8 text-zinc-500 hover:text-zinc-800">
            <X className="h-4 w-4" />
          </Button>
        </div>
        
        <div className="p-6">
          {/* 上半部分：内部人员搜索拉入 */}
          <div className="text-sm font-medium text-zinc-700 mb-2">搜索组织内同事</div>
          <div className="relative mb-4">
            <Search className="absolute left-3 top-2.5 h-4 w-4 text-zinc-400" />
            <input 
              value={searchKeyword} 
              onChange={e => setSearchKeyword(e.target.value)}
              placeholder="输入飞书姓名拼音或汉字..." 
              className="w-full bg-zinc-50 border border-zinc-200 rounded-lg pl-10 pr-4 py-2 text-sm outline-none focus:border-blue-500 transition-colors"
            />
          </div>

          <div className="h-48 overflow-y-auto border border-zinc-100 rounded-lg p-2 space-y-1">
            {isSearching ? <div className="text-center text-xs text-zinc-400 py-10">搜索中...</div> : 
             searchResults?.map(user => (
              <div key={user.openId} onClick={() => toggleUser(user)} className="flex items-center justify-between p-2 hover:bg-zinc-50 rounded-md cursor-pointer transition-colors">
                <div className="flex items-center gap-2">
                  <div className="h-6 w-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-[10px] font-bold">
                    {user.name.charAt(0)}
                  </div>
                  <span className="text-sm text-zinc-700">{user.name}</span>
                </div>
                {selectedUsers.find(u => u.openId === user.openId) && <Check className="h-4 w-4 text-blue-600" />}
              </div>
            ))}
          </div>

          {selectedUsers.length > 0 && (
            <div className="mt-4 flex flex-wrap gap-2 p-3 bg-zinc-50 rounded-lg border border-zinc-100">
              {selectedUsers.map(u => (
                <span key={u.openId} className="px-2 py-1 bg-white text-blue-700 text-xs rounded-md border border-blue-100 shadow-sm flex items-center gap-1">
                  {u.name}
                  <X className="h-3 w-3 cursor-pointer text-zinc-400 hover:text-red-500" onClick={() => toggleUser(u)} />
                </span>
              ))}
            </div>
          )}

          {/* ✨ 下半部分：生成外部群分享链接 ✨ */}
          <div className="mt-6 pt-6 border-t border-zinc-100">
             <div className="text-sm font-medium text-zinc-700 mb-2 flex items-center justify-between">
                <span>通过群分享链接邀请（外部）</span>
                {!shareLink && (
                  <Button variant="outline" size="sm" className="h-7 text-xs" onClick={handleGenerateLink} disabled={isGeneratingLink}>
                    {isGeneratingLink ? <Loader2 className="h-3 w-3 animate-spin mr-1" /> : <LinkIcon className="h-3 w-3 mr-1" />}
                    生成链接
                  </Button>
                )}
             </div>
             
             {shareLink && (
                <div className="flex items-center gap-2 mt-2">
                  <div className="flex-1 bg-zinc-50 border border-zinc-200 rounded-lg px-3 py-2 text-xs text-zinc-500 truncate select-all">
                    {shareLink}
                  </div>
                  <Button variant={isCopied ? "default" : "outline"} size="sm" onClick={handleCopy} className={isCopied ? "bg-green-600 hover:bg-green-700" : ""}>
                    {isCopied ? <Check className="h-4 w-4 mr-1" /> : <Copy className="h-4 w-4 mr-1" />}
                    {isCopied ? "已复制" : "复制"}
                  </Button>
                </div>
             )}
          </div>
        </div>

        {/* 底部操作区 */}
        <div className="flex justify-end gap-3 px-6 py-4 border-t border-zinc-100 bg-zinc-50/80 rounded-b-xl">
          <Button variant="outline" onClick={handleClose}>取消</Button>
          <Button onClick={handleInvite} disabled={selectedUsers.length === 0 || isSubmitting} className="bg-blue-600 text-white hover:bg-blue-700 shadow-sm transition-all">
            {isSubmitting ? '正在邀请...' : `发送飞书邀请 (${selectedUsers.length})`}
          </Button>
        </div>
      </div>
    </div>
  );
}