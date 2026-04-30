// src/pages/Dashboard.tsx
import { useState, useEffect, useRef } from 'react'; //  补充了 useEffect 和 useRef
import { useAuthStore } from '@/store/useAuthStore';
import { useChatStore } from '@/store/useChatStore';
import { authApi } from '@/services/api/auth';
import { imApi } from '@/services/api/im';
import { useRequest } from 'ahooks';
import { Button } from '@/components/ui/button';
import { MessageSquare, LayoutTemplate, Bot, Plus, Search, LogOut, Settings, PanelRightClose, Loader2,UserPlus, User } from 'lucide-react';
import { CreateChatModal } from '@/components/chat/CreateChatModal';
import { InviteMemberModal } from '@/components/chat/InviteMemberModal';
import { fetchEventSource } from '@microsoft/fetch-event-source';

// ✨ 飞书消息内容智能解析器
const parseFeishuContent = (rawContent: string | null | undefined, msgType?: string) => {
  if (!rawContent) return '';
  try {
    // 尝试解析普通文本消息的 {"text": "你好"}
    const json = JSON.parse(rawContent);
    if (json.text) return json.text;
    
    // 如果师兄后续又把系统消息改回 JSON 了，这里留个兼容兜底
    if (json.template) return `[系统消息] ${json.template.replace(/\{.*?\}/g, '某人')}`;
    
    return '[未知 JSON 格式]';
  } catch (e) {
    // 走到这里说明 rawContent 是纯字符串（比如师兄处理过的 system 消息）
    let text = rawContent;
    
    if (msgType === 'system') {
      // 帮师兄把英文做一下前端的汉化替换
      if (text.includes('started the group chat')) {
        text = text.replace('started the group chat, assigned', '创建了群聊，指定')
                   .replace('as the group owner, and invited', '为群主，并邀请')
                   .replace('to the group.', '入群。');
      }
      if (text.includes('added') && text.includes('to group administrators')) {
        text = text.replace('added', '将').replace('to group administrators.', '设为了群管理员。');
      }
      if (text.includes('Welcome to')) {
        text = text.replace('Welcome to', '欢迎来到');
      }
      return `[系统] ${text}`;
    }
    
    // 纯文本直接返回
    return text;
  }
};

export default function Dashboard() {
  const { user, clearAuth } = useAuthStore();
  const { activeChatId, setActiveChatId } = useChatStore();
  const { data: chatData, loading: isChatLoading, runAsync: fetchChatsAsync } = useRequest(imApi.getJoinedChats);

  //  弹窗控制状态
  const [isModalOpen, setIsModalOpen] = useState(false);

  const [inputText, setInputText] = useState('');
  const [isSending, setIsSending] = useState(false);
  // ✨ 新增状态：用来记录哪个群正在后台努力同步中
  const [syncingChatId, setSyncingChatId] = useState<string | null>(null);
  //  SSE 消息流状态
  const [messages, setMessages] = useState<any[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false); // 新增：用于历史消息加载状态
  const abortControllerRef = useRef<AbortController | null>(null);
  const [isInviteModalOpen, setIsInviteModalOpen] = useState(false); // 新增弹窗状态
  

  //  核心逻辑：监听 activeChatId 变化，建立 SSE 订阅
  useEffect(() => {
    if (!activeChatId) return;

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;
    
    const token = useAuthStore.getState().accessToken;

    const connectSSE = async () => {
      setMessages([]); // 清空旧消息
      setIsLoadingHistory(true); // ✨ 开始加载历史消息

      // ✨ 1. 先拉取历史消息补偿盲区
      try {
        const historyRes = await imApi.getChatHistory({
          containerIdType: 'chat',
          containerId: activeChatId,
          sortType: 'ByCreateTimeDesc',
          pageSize: 20
        });
        
        // 飞书返回的是倒序（最新在前），需要 reverse 让旧消息在上面
        const formattedHistory = historyRes.items.reverse().map((item: any) => {
          return {
            eventId: item.messageId, 
            senderOpenId: item.senderId,
            senderType: item.senderType,
            // ✨ 核心修复 1：必须把后端传来的头像和名字“接住”！
            senderName: item.senderName,
            senderAvatar: item.senderAvatar,
            // 使用专门的解析器处理 content 和 msgType
            content: parseFeishuContent(item.content, item.msgType),
            createTime: item.createTime
          };
        });
        setMessages(formattedHistory);
      } catch (err) {
        console.warn('拉取历史消息失败', err);
      } finally {
        setIsLoadingHistory(false);
      }

      // 如果在拉取历史消息时，用户切换了群聊，直接终止后续 SSE 连接
      if (ctrl.signal.aborted) return; 

      //  2. 继续原有的 SSE 长连接逻辑
      try {
        await fetchEventSource(`/api/im/chats/${activeChatId}/messages/stream`, {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
          },
          signal: ctrl.signal,
          async onopen(response) {
            if (!response.ok) throw new Error(`SSE 建立失败: ${response.status}`);
          },
          onmessage(event) {
            if (event.event === 'message') {
              try {
                const msgData = JSON.parse(event.data);
                if (msgData.state === 'connected') return; 
                
//  核心优化：去重并解析新消息
                setMessages(prev => {
                  if (prev.some(m => m.eventId === msgData.eventId || m.messageId === msgData.messageId)) return prev;
                  
                  // 解析新到来的实时消息
                  const parsedMsg = {
                    ...msgData,
                    content: parseFeishuContent(msgData.content, msgData.messageType || msgData.msgType)
                  };
                  return [...prev, parsedMsg];
                });
              } catch (parseError) {
                console.debug('JSON解析失败:', parseError);
              }
            }
          },
          onerror(err) {
            console.error('SSE 发生异常:', err);
            throw err;
          }
        });
      } catch (err: unknown) {
        if (err instanceof Error && err.name !== 'AbortError') console.error('SSE 流断开:', err);
      }
    };

    connectSSE();

    return () => {
      ctrl.abort();
    };
  }, [activeChatId]);
  


  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch (e) {
      console.warn('后端登出失败，前端强制清理', e);
    } finally {
      clearAuth();
    }
  };

// ✨ 发送消息逻辑
  const handleSendMessage = async () => {
    if (!inputText.trim() || !activeChatId) return;
    try {
      setIsSending(true);
      await imApi.sendMessage({
        chatId: activeChatId,
        text: inputText.trim(), // 发送的内容
        idempotencyKey: crypto.randomUUID() // 每次发送生成新的防重 UUID
      });
      setInputText(''); // 发送成功后清空输入框
      // 注意：发送成功后，消息会通过刚才连通的 SSE 流自己推回来，所以这里不用手动塞入 messages
    } catch (e: any) {
      alert('发送失败: ' + e.message);
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div className="flex h-screen w-full flex-col bg-zinc-50 overflow-hidden">
      
      {/* --- 顶部导航栏 --- */}
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-zinc-200 bg-white px-4 shadow-sm z-10">
        <div className="flex items-center space-x-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600 shadow-inner">
            <Bot className="h-5 w-5 text-white" />
          </div>
          <span className="text-lg font-bold tracking-tight text-zinc-900">Agent Pilot</span>
          <span className="rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-medium text-zinc-500 border border-zinc-200">
            Workspace
          </span>
        </div>

        <div className="flex items-center space-x-4">
          <div className="hidden sm:flex items-center space-x-2 text-sm text-zinc-600 pr-4 border-r border-zinc-200">
            <div className="h-2 w-2 rounded-full bg-green-500 animate-pulse" />
            <span>系统已连接</span>
          </div>
          
          <div className="flex items-center space-x-3">
            <span className="text-sm font-medium text-zinc-700">{user?.name}</span>
            <div className="h-8 w-8 overflow-hidden rounded-full border border-zinc-200 shadow-sm">
              {user?.avatarUrl ? (
                <img src={user?.avatarUrl} alt="avatar" className="h-full w-full object-cover" />
              ) : (
                <div className="h-full w-full bg-zinc-200" />
              )}
            </div>
            <Button variant="ghost" size="icon" className="text-zinc-500 hover:text-red-600" onClick={handleLogout} title="退出登录">
              <LogOut className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </header>

      <main className="flex flex-1 overflow-hidden">
        
        {/* --- 左栏：群聊列表 --- */}
        <aside className="w-72 shrink-0 flex-col border-r border-zinc-200 bg-zinc-50/50 hidden md:flex">
          <div className="flex h-12 items-center justify-between px-4 border-b border-zinc-200/50">
            <h2 className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
              <MessageSquare className="h-4 w-4" />
              协作群聊
            </h2>
            {/*  点击 + 号也打开建群弹窗  */}
            <Button variant="ghost" size="icon" className="h-7 w-7 text-zinc-500" onClick={() => setIsModalOpen(true)}>
              <Plus className="h-4 w-4" />
            </Button>
          </div>
          <div className="p-3 border-b border-zinc-200/50">
            <div className="relative">
              <Search className="absolute left-2.5 top-2 h-4 w-4 text-zinc-400" />
              <input 
                type="text" 
                placeholder="搜索群聊..." 
                className="w-full rounded-md border border-zinc-200 bg-white pl-9 pr-3 py-1.5 text-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500 transition-all"
              />
            </div>
          </div>
          
          <div className="flex-1 overflow-y-auto p-2 space-y-1">
            {isChatLoading ? (
              <>
                <div className="animate-pulse flex items-center space-x-3 rounded-lg bg-zinc-200/50 p-2">
                  <div className="h-8 w-8 rounded-md bg-zinc-300" />
                  <div className="flex-1 space-y-2">
                    <div className="h-3 w-24 rounded bg-zinc-300" />
                    <div className="h-2 w-32 rounded bg-zinc-200" />
                  </div>
                </div>
              </>
            ) : chatData?.items && chatData.items.length > 0 ? (
              <>
                {/* 🚀 核心 UX 优化：如果正在后台同步，在最上面显示一个专属的 Loading 条 */}
                {syncingChatId && (
                  <div className="flex animate-pulse cursor-wait items-center space-x-3 rounded-lg p-2 bg-blue-50/50 border border-blue-100">
                    <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-blue-200 text-blue-600">
                      <Loader2 className="h-4 w-4 animate-spin" />
                    </div>
                    <div className="flex-1 truncate text-sm font-medium text-blue-700">
                      正在同步新群聊...
                    </div>
                  </div>
                )}

                {/* 👇 循环渲染真实的群聊列表 👇 */}
                {chatData.items.map((chat) => (
                  <div
                    key={chat.chatId}
                    onClick={() => setActiveChatId(chat.chatId)}
                    className={`flex cursor-pointer items-center space-x-3 rounded-lg p-2 transition-colors ${
                      activeChatId === chat.chatId
                        ? 'bg-blue-100/50 text-blue-700 shadow-sm border border-blue-200/50'
                        : 'hover:bg-zinc-100 text-zinc-700 border border-transparent'
                    }`}
                  >
                    <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-md font-bold ${
                      activeChatId === chat.chatId ? 'bg-blue-600 text-white' : 'bg-zinc-200 text-zinc-500'
                    }`}>
                      {chat.name.charAt(0)}
                    </div>
                    <div className="flex-1 truncate text-sm font-medium">
                      {chat.name}
                    </div>
                  </div>
                ))}
              </>
            ) : (
              <div className="text-center pt-10 space-y-2">
                <p className="text-xs text-zinc-400">当前没有被 Agent 赋能的群聊</p>
                {/* ✨ 点击发起新群聊打开弹窗 ✨ */}
                <Button 
                  variant="outline" 
                  size="sm" 
                  className="text-xs h-7"
                  onClick={() => setIsModalOpen(true)}
                >
                  发起新群聊
                </Button>
              </div>
            )}
          </div>
        </aside>

{/* 中栏：IM 投影区（小屏隐藏，中屏以上显示并限制最大宽度，给右侧让路） */}
<section className="hidden lg:flex flex-col flex-1 xl:max-w-[400px] border-r border-zinc-200 bg-white relative">
          <div className="absolute inset-0 z-0 bg-[radial-gradient(#e5e7eb_1px,transparent_1px)] [background-size:16px_16px] opacity-30 pointer-events-none"></div>
          
          <div className="z-10 flex h-12 items-center justify-between border-b border-zinc-200 px-6 bg-white/80 backdrop-blur-sm">
            <h2 className="text-sm font-semibold text-zinc-800">当前会话投影</h2>
            <div className="flex items-center gap-2">
              {/* ✨ 新增：邀请按钮，只有在选中群聊时才显示 */}
              {activeChatId && (
                <Button 
                  variant="ghost" 
                  size="sm" 
                  className="h-8 text-zinc-500 gap-1.5 hover:text-blue-600 hover:bg-blue-50"
                  onClick={() => setIsInviteModalOpen(true)}
                >
                  <UserPlus className="h-4 w-4" />
                  <span className="hidden sm:inline">邀请成员</span>
                </Button>
              )}
              <Button variant="ghost" size="icon" className="h-8 w-8 lg:hidden">
                <PanelRightClose className="h-4 w-4" />
              </Button>
            </div>
          </div>
          
          <div className="z-10 flex-1 overflow-y-auto p-6 space-y-4 flex flex-col">
{/* 🚀 动态渲染消息流 🚀 */}
            {!activeChatId ? (
              <div className="m-auto text-zinc-400 text-sm">请在左侧选择一个协作群聊以加载消息流</div>
            ) : isLoadingHistory ? (
              <div className="m-auto text-zinc-400 text-sm flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" /> 正在拉取历史消息...
              </div>
            ) : messages.length === 0 ? (
              <div className="m-auto text-zinc-400 text-sm">暂无消息记录</div>
            ) : (
messages.map((msg, index) => {
                const isBot = msg.senderType === 'app' || msg.senderOpenId?.includes('bot');
                
                // ✨ 核心修复 2：准确判断“哪句话是我说的”
                // 终极方案应该是 msg.senderOpenId === user?.openId，但在后端还没给 openId 前，
                // 我们临时用名字判断（如果这条消息的发送者名字和当前登录用户的名字一样，就是我）
                const isMe = msg.senderName ? msg.senderName === user?.name : false; 
                
                // 判断最终显示的名称
                const displayName = isBot ? 'Agent Pilot' : (msg.senderName || '系统通知');
                
                return (
                  <div key={index} className={`flex w-full ${isMe ? 'justify-end' : 'justify-start'}`}>
                    <div className={`flex max-w-[85%] gap-2 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                      
                      {/* 头像区域 */}
                      <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full shadow-sm overflow-hidden ${
                        isBot ? 'bg-blue-600 text-white' : 'bg-zinc-200 text-zinc-600'
                      }`}>
                        {isBot ? (
                          <Bot className="h-4 w-4" />
                        ) : msg.senderAvatar ? (
                          <img src={msg.senderAvatar} alt="avatar" className="h-full w-full object-cover" />
                        ) : (
                          <User className="h-4 w-4" />
                        )}
                      </div>

                      {/* 气泡与名字区域 */}
                      <div className={`flex flex-col space-y-1 ${isMe ? 'items-end' : 'items-start'}`}>
                        <span className="text-[11px] font-medium text-zinc-400 px-1">
                          {displayName}
                        </span>
                        <div className={`rounded-2xl px-3.5 py-2 text-sm shadow-sm leading-relaxed whitespace-pre-wrap break-words ${
                          isMe ? 'bg-zinc-800 text-white rounded-tr-sm' 
                               : 'bg-white border border-zinc-200 text-zinc-800 rounded-tl-sm'
                        }`}>
                          {msg.content}
                        </div>
                      </div>
                      
                    </div>
                  </div>
                );
              })
            )}
          </div>

          <div className="z-10 border-t border-zinc-200 p-4 bg-zinc-50">
            <div className="relative flex items-end gap-2">
             <textarea 
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleSendMessage();
                  }
                }}
                className="max-h-32 min-h-[44px] w-full resize-none rounded-xl border border-zinc-300 bg-white p-3 pr-12 text-sm shadow-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500" 
                placeholder="输入消息或指令唤醒 Agent (按 Enter 发送)..."
                rows={1}
                disabled={!activeChatId || isSending}
              />
              <Button 
                onClick={handleSendMessage}
                disabled={!activeChatId || !inputText.trim() || isSending} 
                className="absolute right-2 bottom-1.5 h-8 bg-blue-600 hover:bg-blue-700 text-white rounded-lg px-4 shadow-sm"
              >
                {isSending ? '发送中...' : '发送'}
              </Button>
            </div>
          </div>
        </section>

 {/* 右栏：Agent 任务工作台（所有屏幕下都是核心 flex-1 占据绝大部分空间） */}
<aside className="flex flex-1 flex-col bg-zinc-50 shadow-[-4px_0_15px_-3px_rgba(0,0,0,0.02)] relative z-10">
          <div className="flex h-12 items-center justify-between px-4 border-b border-zinc-200 bg-white">
            <h2 className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
              <LayoutTemplate className="h-4 w-4 text-blue-600" />
              任务工作台
            </h2>
            <Button variant="ghost" size="icon" className="h-7 w-7 text-zinc-500">
              <Settings className="h-4 w-4" />
            </Button>
          </div>
          <div className="flex-1 overflow-y-auto p-4">
            <div className="flex flex-col items-center justify-center h-48 space-y-3 text-center">
              <div className="rounded-full bg-zinc-200/50 p-3">
                <Bot className="h-6 w-6 text-zinc-400" />
              </div>
              <p className="text-sm text-zinc-500">
                当前暂无活跃任务<br/>
                <span className="text-xs text-zinc-400">在对话框输入指令即可触发 Agent</span>
              </p>
            </div>
          </div>
        </aside>

      </main>

      {/* 🚀 挂载建群弹窗 🚀 */}
      <CreateChatModal 
        isOpen={isModalOpen} 
        // onClose={() => setIsModalOpen(false)} // 之前是直接关
        onClose={() => setIsModalOpen(false)} 
        onSuccess={async (newChatId) => {
          // 注意：这时候弹窗里的按钮还在转圈（因为还没执行 onClose）
          let maxRetries = 5; 
          while (maxRetries > 0) {
            await new Promise(resolve => setTimeout(resolve, 1500)); 
            try {
              const freshData = await fetchChatsAsync(); 
              const isSyncCompleted = freshData?.items.some(chat => chat.chatId === newChatId);
              if (isSyncCompleted) {
                setActiveChatId(newChatId); // 选中新群
                setIsModalOpen(false); // ✨ 核心优化：查到数据了，再关闭弹窗！
                break; 
              }
            } catch (e) {
              console.warn('轮询拉取失败...');
            }
            maxRetries--;
          }
          // 如果 5 次都没查到，也把弹窗关了，免得卡死
          setIsModalOpen(false);
        }} 
      />

      {/* ✨ 新增：挂载邀请弹窗 */}
      {activeChatId && (
        <InviteMemberModal 
          isOpen={isInviteModalOpen}
          onClose={() => setIsInviteModalOpen(false)}
          chatId={activeChatId}
        />
      )}                                                                      
    </div>
  );
}