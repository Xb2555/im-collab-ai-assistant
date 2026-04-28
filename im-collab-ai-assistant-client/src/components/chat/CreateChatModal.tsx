// src/components/chat/CreateChatModal.tsx
import { useState } from 'react'; // 不再需要引入 useEffect
import { useRequest } from 'ahooks';
import { imApi } from '@/services/api/im';
import type { UserItem } from '@/services/api/im';
import { Button } from '@/components/ui/button';
import { Search, X, Check } from 'lucide-react';

interface CreateChatModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (chatId: string) => void;
}

export function CreateChatModal({ isOpen, onClose, onSuccess }: CreateChatModalProps) {
  const [chatName, setChatName] = useState('');
  const [searchKeyword, setSearchKeyword] = useState('');
  const [selectedUsers, setSelectedUsers] = useState<UserItem[]>([]);

  const { data: searchResults, loading: isSearching } = useRequest(
    () => imApi.searchUsers(searchKeyword),
    { debounceWait: 500, refreshDeps: [searchKeyword] }
  );

  const [isCreating, setIsCreating] = useState(false);

  // ✨ 核心修复：专门写一个关闭处理函数，用来清空所有历史数据并关闭弹窗
  const handleCloseModal = () => {
    setChatName('');
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

  const handleCreate = async () => {
    if (!chatName.trim()) return alert('请输入群聊名称');
    try {
      setIsCreating(true);
      const newChat = await imApi.createChat({
        name: chatName.trim(),
        userOpenIds: selectedUsers.map(u => u.openId),
        chatType: 'private',
        uuid: crypto.randomUUID()
      });
      
      // ✨ 成功发起后，也顺手清空一下数据
      setChatName('');
      setSearchKeyword('');
      setSelectedUsers([]);
      
      onSuccess(newChat.chatId);
      // 注意：这里不用调 handleCloseModal，因为 onSuccess 执行后，父组件的轮询机制会负责关闭弹窗
    } catch (e: any) {
      alert('建群失败: ' + e.message);
    } finally {
      setIsCreating(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-900/40 backdrop-blur-sm">
      <div className="w-[600px] flex flex-col bg-white rounded-xl shadow-2xl overflow-hidden border border-zinc-200">
        <div className="flex items-center justify-between px-6 py-4 border-b border-zinc-100 bg-zinc-50/50">
          <h2 className="text-base font-semibold text-zinc-800">发起带有 Agent 的协作群</h2>
          {/* ✨ 把原本的 onClose 替换成 handleCloseModal ✨ */}
          <Button variant="ghost" size="icon" onClick={handleCloseModal} className="h-8 w-8 text-zinc-500">
            <X className="h-4 w-4" />
          </Button>
        </div>
        
        <div className="p-6 space-y-6">
          <div className="space-y-2">
            <label className="text-sm font-medium text-zinc-700">群聊名称 <span className="text-red-500">*</span></label>
            <input 
              value={chatName} onChange={e => setChatName(e.target.value)}
              placeholder="例如：Q3 季度营销复盘" 
              className="w-full border border-zinc-300 rounded-lg px-3 py-2 text-sm outline-none focus:border-blue-500"
            />
          </div>

          <div className="flex gap-6 h-64">
            <div className="flex-1 flex flex-col border border-zinc-200 rounded-lg overflow-hidden">
              <div className="p-2 border-b border-zinc-200 bg-zinc-50 relative">
                <Search className="absolute left-4 top-4 h-4 w-4 text-zinc-400" />
                <input 
                  value={searchKeyword} onChange={e => setSearchKeyword(e.target.value)}
                  placeholder="搜索飞书同事..." 
                  className="w-full bg-white border border-zinc-200 rounded-md pl-8 pr-2 py-1.5 text-sm outline-none"
                />
              </div>
              <div className="flex-1 overflow-y-auto p-2 space-y-1">
                {isSearching ? <div className="text-center text-xs text-zinc-400 pt-4">搜索中...</div> : 
                 searchResults?.map(user => (
                  <div key={user.openId} onClick={() => toggleUser(user)} className="flex items-center justify-between p-2 hover:bg-zinc-100 rounded-md cursor-pointer group">
                    <div className="flex items-center gap-2">
                      <div className="h-6 w-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-xs font-bold">{user.name.charAt(0)}</div>
                      <span className="text-sm text-zinc-700">{user.name}</span>
                    </div>
                    {selectedUsers.find(u => u.openId === user.openId) && <Check className="h-4 w-4 text-blue-600" />}
                  </div>
                ))}
              </div>
            </div>

            <div className="w-1/3 flex flex-col border border-zinc-200 rounded-lg bg-zinc-50 overflow-hidden">
              <div className="p-2 border-b border-zinc-200 text-xs font-medium text-zinc-500">已选成员 ({selectedUsers.length})</div>
              <div className="flex-1 overflow-y-auto p-2 space-y-2">
                {selectedUsers.map(user => (
                  <div key={user.openId} className="flex items-center justify-between bg-white border border-zinc-200 p-1.5 rounded-md">
                    <span className="text-xs text-zinc-700 truncate">{user.name}</span>
                    <X className="h-3 w-3 text-zinc-400 cursor-pointer hover:text-red-500" onClick={() => toggleUser(user)} />
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        <div className="flex justify-end gap-3 px-6 py-4 border-t border-zinc-100 bg-zinc-50">
          {/* ✨ 把原本的 onClose 替换成 handleCloseModal ✨ */}
          <Button variant="outline" onClick={handleCloseModal}>取消</Button>
          <Button onClick={handleCreate} disabled={!chatName || isCreating} className="bg-blue-600 hover:bg-blue-700 text-white">
            {isCreating ? '建群中...' : '确认发起'}
          </Button>
        </div>
      </div>
    </div>
  );
}