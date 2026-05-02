// src/pages/Dashboard.tsx
import { useState, useEffect, useRef } from 'react'; //  补充了 useEffect 和 useRef
import { useAuthStore } from '@/store/useAuthStore';
import { useChatStore } from '@/store/useChatStore';
import { authApi } from '@/services/api/auth';
import { imApi } from '@/services/api/im';
import { useRequest } from 'ahooks';
import { Button } from '@/components/ui/button';
import { 
  MessageSquare, LayoutTemplate, Bot, Plus, Search, LogOut, 
  Settings, PanelRightClose, Loader2, UserPlus, User,
  Play, Square, RefreshCw, CheckCircle2, CircleDashed, Check, AlertCircle,
  X, StopCircle // ✨ 关键修复：补全这两个缺失的图标
} from 'lucide-react';
import { CreateChatModal } from '@/components/chat/CreateChatModal';
import { InviteMemberModal } from '@/components/chat/InviteMemberModal';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { plannerApi } from '@/services/api/planner';
import { useTaskStore } from '@/store/useTaskStore';

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
  // ✨ 引入刚才写的 Task Store
  const { activeTaskId, planPreview, setActiveTaskId, setPlanPreview, clearTask } = useTaskStore();
  const [isPlanning, setIsPlanning] = useState(false);
  // ✨ 新增：用于处理“重新规划”的表单状态
  const [isReplanningMode, setIsReplanningMode] = useState(false);
  const [replanFeedback, setReplanFeedback] = useState('');
const [resumeFeedback, setResumeFeedback] = useState(''); // ✨ 新增：记录用户对追问的回答
  const [isResuming, setIsResuming] = useState(false);

  // ✨ 新增：提交追问回答，恢复任务执行
  const handleResumeTask = async () => {
    if (!activeTaskId || !resumeFeedback.trim()) return;
    try {
      setIsResuming(true);
      const newPreview = await plannerApi.resumeTask(activeTaskId, {
        feedback: resumeFeedback.trim(),
        replanFromRoot: false // 只是回答问题，不需要从头重规
      });
      setPlanPreview(newPreview); // 更新预览状态（通常会进入 PLANNING 动画）
      setResumeFeedback(''); // 清空输入
    } catch (e: any) {
      alert('提交回答失败: ' + e.message);
    } finally {
      setIsResuming(false);
    }
  };
  // ✨ 新增：唤醒 Agent 发起规划任务
  const handleStartAgentPlan = async () => {
    if (!inputText.trim() || !activeChatId) return;
    try {
      setIsPlanning(true);
      // 调用创建规划接口
      const preview = await plannerApi.createPlan({
        rawInstruction: inputText.trim(),
        workspaceContext: {
          chatId: activeChatId,
          selectionType: 'MESSAGE'
        }
      });
      // 将返回的 taskId 和 初始预览状态存入 Zustand
      setActiveTaskId(preview.taskId || null);
      setPlanPreview(preview);
      setInputText(''); // 清空输入框
    } catch (e: any) {
      alert('唤醒 Agent 失败: ' + e.message);
    } finally {
      setIsPlanning(false);
    }
  };

  //  新增：操作控制按钮 (确认、取消等)
//  修复：支持传入 feedback
  const handleCommand = async (action: 'CONFIRM_EXECUTE' | 'REPLAN' | 'CANCEL') => {
    if (!activeTaskId) return;
    try {
      const newPreview = await plannerApi.executeCommand(activeTaskId, {
        action,
        feedback: action === 'REPLAN' ? replanFeedback.trim() : undefined, // 重新规划时带上意见
        // version: 1 
      });
      setPlanPreview(newPreview);
      
      // 成功后重置重新规划的表单状态
      if (action === 'REPLAN') {
        setIsReplanningMode(false);
        setReplanFeedback('');
      }
    } catch (e: any) {
      if (e.message === 'VERSION_CONFLICT') {
        alert('多端冲突：操作已在其他端完成，请等待界面刷新！');
      } else {
        alert('操作失败: ' + e.message);
      }
    }
  };

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
      setIsLoadingHistory(true); //  开始加载历史消息

      //  1. 先拉取历史消息补偿盲区
      //  1. 先拉取历史消息补偿盲区
      try {
        const historyRes = await imApi.getChatHistory({
          containerIdType: 'chat',
          containerId: activeChatId,
          sortType: 'ByCreateTimeDesc',
          pageSize: 20,
          signal: ctrl.signal // ✨ 新增：传入中断信号
        });
        
        // ✨ 新增防线：如果请求期间用户切换了群聊导致被中断，绝对不要更新状态！
        if (ctrl.signal.aborted) return;
        
        // 飞书返回的是倒序（最新在前），需要 reverse 让旧消息在上面
        const formattedHistory = historyRes.items.reverse().map((item: any) => {
          return {
            eventId: item.messageId, 
            senderOpenId: item.senderId,
            senderType: item.senderType,
            //  核心修复 1：必须把后端传来的头像和名字“接住”！
            senderName: item.senderName,
            senderAvatar: item.senderAvatar,
            // 使用专门的解析器处理 content 和 msgType
            content: parseFeishuContent(item.content, item.msgType),
            createTime: item.createTime
          };
        });
        setMessages(formattedHistory);
      } catch (err: any) {
        // ✨ 新增判断：被我们主动 abort 的请求不算是业务报错，不需要打印警告
        if (err.name !== 'CanceledError' && err.message !== 'canceled') {
          console.warn('拉取历史消息失败', err);
        }
      } finally {
        if (!ctrl.signal.aborted) {
          setIsLoadingHistory(false);
        }
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
                {/*  核心 UX 优化：如果正在后台同步，在最上面显示一个专属的 Loading 条 */}
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
{/* 动态渲染消息流 */}
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
                
                //  核心修复 2：准确判断“哪句话是我说的”
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
            {/* 改为垂直 flex 排布，把按钮挪到输入框下方，清爽不遮挡 */}
            <div className="flex flex-col gap-2">
             <textarea 
                value={inputText}
                onChange={(e) => setInputText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleSendMessage();
                  }
                }}
                className="max-h-32 min-h-[44px] w-full resize-none rounded-xl border border-zinc-300 bg-white p-3 text-sm shadow-sm outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500" 
                placeholder="输入消息或指令唤醒 Agent (按 Enter 发送)..."
                rows={2}
                disabled={!activeChatId || isSending}
              />
              <div className="flex justify-end items-center gap-2">
                <Button 
                  onClick={handleSendMessage}
                  disabled={!activeChatId || !inputText.trim() || isSending || isPlanning} 
                  variant="outline"
                  className="h-8 rounded-lg px-4 shadow-sm text-zinc-600"
                >
                  {isSending ? '发送中' : '发送到群'}
                </Button>
                
                <Button 
                  onClick={handleStartAgentPlan}
                  disabled={!activeChatId || !inputText.trim() || isSending || isPlanning} 
                  className="h-8 bg-blue-600 hover:bg-blue-700 text-white rounded-lg px-4 shadow-sm flex items-center gap-1.5"
                >
                  <Bot className="h-4 w-4" />
                  {isPlanning ? 'Agent 规划中...' : '交由 Agent 办理'}
                </Button>
              </div>
            </div>
          </div>
        </section>

 {/* 右栏：Agent 任务工作台（所有屏幕下都是核心 flex-1 占据绝大部分空间） */}
{/* 右栏：Agent 任务工作台 */}
 <aside className="flex flex-1 lg:w-[360px] flex-col bg-zinc-50 shadow-[-4px_0_15px_-3px_rgba(0,0,0,0.02)] relative z-10 border-l border-zinc-200">
    <div className="flex h-12 items-center justify-between px-4 border-b border-zinc-200 bg-white shrink-0">
      <h2 className="text-sm font-semibold text-zinc-800 flex items-center gap-2">
        <LayoutTemplate className="h-4 w-4 text-blue-600" />
        任务工作台
      </h2>
      <Button variant="ghost" size="icon" className="h-7 w-7 text-zinc-500" onClick={clearTask}>
        <X className="h-4 w-4" />
      </Button>
    </div>

    <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-4">
      {!activeTaskId || !planPreview ? (
        // 空状态
        <div className="flex flex-col items-center justify-center h-48 space-y-3 text-center mt-10">
          <div className="rounded-full bg-zinc-200/50 p-3">
            <Bot className="h-6 w-6 text-zinc-400" />
          </div>
          <p className="text-sm text-zinc-500">
            当前暂无活跃任务<br/>
            <span className="text-xs text-zinc-400">在输入框描述意图并点击"交由 Agent 办理"</span>
          </p>
        </div>
      ) : (
        // 任务详情与步骤条 (Stepper)
        <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
          
          {/* 1. 任务头部卡片 & 追问历史回显 */}
          <div className="bg-white border border-zinc-200 rounded-xl p-4 shadow-sm mb-4">
            <div className="flex items-center justify-between mb-1">
              <div className="flex items-center gap-2">
                <span className="h-2 w-2 rounded-full bg-blue-500 animate-pulse" />
                <h3 className="text-sm font-bold text-zinc-900">{planPreview.title || 'Agent 任务规划'}</h3>
              </div>
              <span className="text-[10px] font-medium text-zinc-400 bg-zinc-100 px-2 py-0.5 rounded-full">
                {planPreview.planningPhase}
              </span>
            </div>
            <p className="text-xs text-zinc-500 mb-3">{planPreview.summary || '已收到您的意图，生成以下执行步骤'}</p>
            
            {/* ✨ 新增细节：如果有之前的追问回答，展示出来作为前情提要 */}
            {planPreview.clarificationAnswers && planPreview.clarificationAnswers.length > 0 && (
               <div className="mb-4 p-2 bg-zinc-50 rounded-md border border-zinc-100 text-xs text-zinc-500 flex gap-2">
                 <span className="font-semibold text-zinc-700 shrink-0">您的意图补充:</span>
                 <span className="truncate">"{planPreview.clarificationAnswers.join(', ')}"</span>
               </div>
            )}
            
            {/* 步骤条核心区 */}
            <div className="space-y-4 relative before:absolute before:inset-0 before:ml-2 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-gradient-to-b before:from-transparent before:via-zinc-200 before:to-transparent">
              {planPreview.cards?.map((card, idx) => (
                <div key={card.cardId || idx} className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active">
                  {/* 状态图标 */}
                  <div className="flex items-center justify-center w-5 h-5 rounded-full border border-white bg-zinc-100 text-zinc-500 shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow-sm z-10">
                    {card.status === 'COMPLETED' || card.status === 'DONE' ? (
                      <CheckCircle2 className="w-3.5 h-3.5 text-green-500" />
                    ) : card.status === 'IN_PROGRESS' || card.status === 'EXECUTING' ? (
                      <Loader2 className="w-3.5 h-3.5 text-blue-500 animate-spin" />
                    ) : (
                      <CircleDashed className="w-3.5 h-3.5" />
                    )}
                  </div>
                  {/* 卡片内容 */}
                  <div className="w-[calc(100%-2rem)] md:w-[calc(50%-1.5rem)] bg-white p-3 rounded-lg border border-zinc-200 shadow-sm relative">
                    {/* ✨ 新增细节：渲染任务类型 Tag */}
                    {card.type && (
                      <span className="absolute top-3 right-3 text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wider bg-indigo-50 text-indigo-600 border border-indigo-100">
                        {card.type}
                      </span>
                    )}
                    <h4 className="text-xs font-semibold text-zinc-800 pr-12">{card.title}</h4>
                    {card.description && <p className="text-[10px] text-zinc-500 mt-1 line-clamp-2">{card.description}</p>}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* 审查态/操作干预区 */}
          {planPreview.actions && (
            <div className="bg-zinc-100 rounded-xl p-3 flex flex-col gap-3 border border-zinc-200 border-dashed transition-all">
              <span className="text-xs text-zinc-500 font-medium text-center">人工干预控制台</span>
              
              {/* ✨ 新增细节：展开的重新规划输入框 */}
              {isReplanningMode ? (
                <div className="flex flex-col gap-2 animate-in slide-in-from-top-2">
                  <textarea 
                    value={replanFeedback}
                    onChange={(e) => setReplanFeedback(e.target.value)}
                    placeholder="请输入调整意见，例如：不需要总结，直接写大纲..."
                    className="w-full text-xs p-2 rounded-md border border-zinc-300 focus:border-blue-500 outline-none resize-none"
                    rows={2}
                  />
                  <div className="flex gap-2">
                    <Button size="sm" variant="outline" className="flex-1" onClick={() => setIsReplanningMode(false)}>
                      取消
                    </Button>
                    <Button size="sm" className="flex-1 bg-blue-600 hover:bg-blue-700 text-white" disabled={!replanFeedback.trim()} onClick={() => handleCommand('REPLAN')}>
                      <RefreshCw className="h-3.5 w-3.5 mr-1" /> 提交调整
                    </Button>
                  </div>
                </div>
              ) : (
                <div className="grid grid-cols-2 gap-2">
                  {planPreview.actions.canConfirm && (
                    <Button size="sm" className="bg-zinc-900 hover:bg-zinc-800 text-white" onClick={() => handleCommand('CONFIRM_EXECUTE')}>
                      <Play className="h-3.5 w-3.5 mr-1" /> 确认执行
                    </Button>
                  )}
                  {/* ✨ 改动：点击重新规划，先展开表单而不是直接发请求 */}
                  {planPreview.actions.canReplan && (
                    <Button size="sm" variant="outline" className="text-zinc-700 hover:bg-zinc-200" onClick={() => setIsReplanningMode(true)}>
                      <RefreshCw className="h-3.5 w-3.5 mr-1" /> 重新规划
                    </Button>
                  )}
                  {planPreview.actions.canCancel && (
                    <Button size="sm" variant="outline" className="text-red-600 hover:bg-red-50 hover:text-red-700 col-span-2 mt-1" onClick={() => handleCommand('CANCEL')}>
                      <X className="h-3.5 w-3.5 mr-1" /> 取消任务
                    </Button>
                  )}
                  {planPreview.actions.canInterrupt && (
                    <Button size="sm" variant="destructive" className="col-span-2 mt-1" onClick={() => handleCommand('CANCEL')}>
                      <StopCircle className="h-3.5 w-3.5 mr-1" /> 停止生成
                    </Button>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  </aside>

      </main>

      {/* 挂载建群弹窗 */}
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
                setIsModalOpen(false); // 核心优化：查到数据了，再关闭弹窗！
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

      {/* 新增：挂载邀请弹窗 */}
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