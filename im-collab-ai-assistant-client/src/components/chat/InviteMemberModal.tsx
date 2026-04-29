// src/components/chat/InviteMemberModal.tsx
import { useState } from 'react';
import { useRequest } from 'ahooks';
import { imApi } from '@/services/api/im';
import type { UserItem } from '@/services/api/im';
import { Button } from '@/components/ui/button';
import { Search, X, Check, UserPlus } from 'lucide-react';

interface InviteMemberModalProps {
  isOpen: boolean;
  onClose: () => void;
  chatId: string; // 必须传入当前活跃的 chatId
}

export function InviteMemberModal({ isOpen, onClose, chatId }: InviteMemberModalProps) {
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedUsers, setSelectedUsers] = useState<UserItem[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // 搜索组织内用户
  const { data: searchResults, loading: isSearching } = useRequest(
    () => imApi.searchUsers(searchKeyword),
    { debounceWait: 500, refreshDeps: [searchKeyword] }
  );

  const handleClose = () => {
    setSearchKeyword('');
    setSelectedUsers([]);
    onClose();
  };

  const toggleUser = (user: UserItem) => {
    if (selectedUsers.find(u => u.openId === user.openId)) {
      setSelectedUsers(selectedUsers.filter(u => u.openId !== user.openId));
    } else {
      setSelectedUsers([...selectedUsers, user]);
    }
  };

  const handleInvite = async () => {
    if (selectedUsers.length === 0) return;
    try {
      setIsSubmitting(true);
      const result = await imApi.invite({
        chatId: chatId,
        userOpenIds: selectedUsers.map(u => u.openId)
      });

      // 根据后端返回的契约处理反馈 [cite: 550-555]
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

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/40 backdrop-blur-sm">
      <div className="w-[500px] flex flex-col bg-white rounded-xl shadow-2xl border border-zinc-200">
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-100">
          <h2 className="text-base font-semibold text-zinc-800 flex items-center gap-2">
            <UserPlus className="h-4 w-4" /> 邀请新成员加入
          </h2>
          <Button variant="ghost" size="icon" onClick={handleClose} className="h-8 w-8">
            <X className="h-4 w-4" />
          </Button>
        </div>
        
        <div className="p-6">
          <div className="relative mb-4">
            <Search className="absolute left-3 top-2.5 h-4 w-4 text-zinc-400" />
            <input 
              value={searchKeyword} 
              onChange={e => setSearchKeyword(e.target.value)}
              placeholder="搜索飞书同事姓名..." 
              className="w-full bg-zinc-50 border border-zinc-200 rounded-lg pl-10 pr-4 py-2 text-sm outline-none focus:border-blue-500"
            />
          </div>

          <div className="h-60 overflow-y-auto border border-zinc-100 rounded-lg p-2 space-y-1">
            {isSearching ? <div className="text-center text-xs py-10">搜索中...</div> : 
             searchResults?.map(user => (
              <div key={user.openId} onClick={() => toggleUser(user)} className="flex items-center justify-between p-2 hover:bg-zinc-50 rounded-md cursor-pointer">
                <div className="flex items-center gap-2">
                  <div className="h-6 w-6 bg-zinc-200 rounded-full flex items-center justify-center text-[10px]">{user.name.charAt(0)}</div>
                  <span className="text-sm">{user.name}</span>
                </div>
                {selectedUsers.find(u => u.openId === user.openId) && <Check className="h-4 w-4 text-blue-600" />}
              </div>
            ))}
          </div>

          {selectedUsers.length > 0 && (
            <div className="mt-4 flex flex-wrap gap-2">
              {selectedUsers.map(u => (
                <span key={u.openId} className="px-2 py-1 bg-blue-50 text-blue-700 text-xs rounded-md border border-blue-100">
                  {u.name}
                </span>
              ))}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-3 px-6 py-4 border-t border-zinc-100 bg-zinc-50/50 rounded-b-xl">
          <Button variant="outline" onClick={handleClose}>取消</Button>
          <Button onClick={handleInvite} disabled={selectedUsers.length === 0 || isSubmitting} className="bg-blue-600 text-white hover:bg-blue-700">
            {isSubmitting ? '正在邀请...' : `确认邀请 (${selectedUsers.length})`}
          </Button>
        </div>
      </div>
    </div>
  );
}