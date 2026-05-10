// src/components/dashboard/ChatRoomMobile.tsx
import { useState, useEffect, useRef } from 'react';
import { useAuthStore } from '@/store/useAuthStore';
import { useChatStore } from '@/store/useChatStore';
import { useTaskStore } from '@/store/useTaskStore';
import { imApi } from '@/services/api/im';
import { plannerApi } from '@/services/api/planner';
import { getBaseUrl } from '@/services/api/client';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { Button } from '@/components/ui/button';
import { Loader2, Bot, CheckCircle2, Circle, X, Send, Wand2 } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

// 复用桌面端的飞书消息解析逻辑
const parseFeishuContent = (rawContent: string | null | undefined, msgType?: string) => {
  if (!rawContent) return '';
  if (msgType !== 'system') {
    try {
      const json = JSON.parse(rawContent);
      if (json.text) return json.text;
      return rawContent;
    } catch {
      return rawContent;
    }
  }

  const fallbackSystemText = (template?: string) => {
    if (!template) return '[系统消息] 系统通知';
    if (template.includes('group administrators')) return '[系统消息] 系统更新了管理员设置';
    if (template.includes('started the group chat')) return '[系统消息] 系统创建了群聊';
    if (template.includes('invited') && template.includes('to the group')) return '[系统消息] 系统更新了群成员';
    if (template.includes('revoked') || template.includes('removed from the group')) return '[系统消息] 系统调整了群成员';
    if (template.includes('Welcome to')) return '[系统消息] 欢迎加入群聊';
    return '[系统消息] 系统通知';
  };

  try {
    const json = JSON.parse(rawContent);
    if (json.text) return `[系统消息] ${json.text}`;
    const template: string | undefined = json.template;
    if (!template) return '[系统消息] 系统通知';
    const variables = json.variables && typeof json.variables === 'object' ? json.variables : {};

    let hasMeaningfulValue = false;
    const rendered = template.replace(/\{([^{}]+)\}/g, (_m: string, key: string) => {
      const fromVariables = variables?.[key];
      const fromTopLevel = json?.[key];
      const rawValue = fromVariables ?? fromTopLevel;
      let values: string[] = [];
      if (Array.isArray(rawValue)) {
        values = rawValue.filter((v: unknown) => typeof v === 'string' && v.trim()).map((v: string) => v.trim());
      } else if (typeof rawValue === 'string' && rawValue.trim()) {
        values = [rawValue.trim()];
      }
      if (values.length > 0) {
        hasMeaningfulValue = true;
        return values.join('、');
      }
      if (key === 'group_type') return '群聊';
      return '某人';
    });

    if (!hasMeaningfulValue) return fallbackSystemText(template);

    const localized = rendered
      .replace(' invited ', ' 邀请了 ')
      .replace(' to the group. New members can view all chat history.', ' 入群，新成员可查看全部聊天记录。')
      .replace(' revoked the group invitation, ', ' 撤销了群邀请，')
      .replace(' was removed from the group chat.', ' 已被移出群聊。')
      .replace(' started the group chat and assigned ', ' 创建了群聊，并指定 ')
      .replace(' as the group owner.', ' 为群主。')
      .replace(' added ', ' 将 ')
      .replace(' to group administrators.', ' 设为了群管理员。')
      .replace('Welcome to 群聊', '欢迎加入群聊');

    return `[系统消息] ${localized}`;
  } catch {
    return `[系统] ${rawContent}`;
  }
};

interface ChatRoomMobileProps {
  onOpenHistory: () => void;
  onSwitchToWorkspace?: () => void; // ✨ 新增跳转回调
}

export function ChatRoomMobile({ onOpenHistory, onSwitchToWorkspace }: ChatRoomMobileProps) {
  const { user, accessToken } = useAuthStore();
  const { activeChatId } = useChatStore();
  const { setActiveTaskId, setPlanPreview, isPlanning, setIsPlanning } = useTaskStore();

  const [messages, setMessages] = useState<any[]>([]);
  const [inputText, setInputText] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  
  // ✨ 移动端专属状态
  const [isSelectionMode, setIsSelectionMode] = useState(false);
  const [selectedMessageIds, setSelectedMessageIds] = useState<string[]>([]);
  const [isAgentDrawerOpen, setIsAgentDrawerOpen] = useState(false);
  const [agentInstruction, setAgentInstruction] = useState('');

  //  将其替换为浏览器兼容的类型：
  const abortControllerRef = useRef<AbortController | null>(null);
  // ✨ 修复：使用 ReturnType 让 TS 自动推导环境定时器类型，兼容性最好
  const longPressTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  //  ======== 新增 ======== 
  const messagesEndRef = useRef<HTMLDivElement>(null);

  const scrollToBottom = (behavior: 'smooth' | 'auto' = 'smooth') => {
    messagesEndRef.current?.scrollIntoView({ behavior });
  };

  useEffect(() => {
    scrollToBottom('smooth');
  }, [messages]);
  //  ======================= 
//  ============ 插入补拉方法 ============ 
  const loadLatestHistory = async () => {
    if (!activeChatId) return;
    try {
      const historyRes = await imApi.getChatHistory({
        containerIdType: 'chat',
        containerId: activeChatId,
        sortType: 'ByCreateTimeDesc',
        pageSize: 20,
      });
      const formattedHistory = historyRes.items.reverse().map((item: any) => ({
        eventId: item.messageId || crypto.randomUUID(),
        senderOpenId: item.senderId,
        senderType: item.senderType,
        senderName: item.senderName,
        senderAvatar: item.senderAvatar,
        content: parseFeishuContent(item.content, item.msgType),
        createTime: item.createTime,
      }));
      setMessages(prev => {
        const newMessages = [...prev];
        formattedHistory.forEach((newItem: any) => {
          if (!newMessages.some(m => m.eventId === newItem.eventId)) newMessages.push(newItem);
        });
        return newMessages.sort((a, b) => Number(a.createTime) - Number(b.createTime));
      });
    } catch (err) {
      console.warn('手动拉取历史消息失败', err);
    }
  };
  // 👆 ===================================== 👆
  // 1. 发送常规消息
// 1. 发送常规消息
  const handleSendMessage = async () => {
    if (!inputText.trim() || !activeChatId) return;
    
    const currentText = inputText.trim();
    setInputText(''); 

    const tempEventId = `temp-${crypto.randomUUID()}`;
    const optimisticMsg = {
      eventId: tempEventId,
      senderOpenId: (user as any)?.openId || 'me',
      senderType: 'user',
      senderName: user?.name || '我',
      senderAvatar: user?.avatarUrl,
      content: currentText,
      createTime: Date.now().toString(),
    };
    setMessages(prev => [...prev, optimisticMsg]);

try {
      setIsSending(true);
      // ✨ 1. 接收返回值
      const sendResult = await imApi.sendMessage({
        chatId: activeChatId,
        text: currentText,
        idempotencyKey: crypto.randomUUID(),
      });
      
      // ✨ 2. 狸猫换太子：把临时 ID 换成真实 ID
      // ✨ 核心修复：防止乐观更新与 SSE 竞态导致重复
      if (sendResult?.messageId) {
        setMessages(prev => {
          // 检查 SSE 是否已经把这条真实消息推过来了
          const alreadyExists = prev.some(m => m.eventId === sendResult.messageId && m.eventId !== tempEventId);
          if (alreadyExists) {
            // 如果 SSE 已经抢先，我们直接删掉本地假消息
            return prev.filter(m => m.eventId !== tempEventId);
          }
          // 如果 SSE 还没来，就把假消息的 ID 换成真实的
          return prev.map(m => m.eventId === tempEventId ? { ...m, eventId: sendResult.messageId } : m);
        });
      }

      // 延时触发兜底拉取
      setTimeout(() => {
        loadLatestHistory();
      }, 800);

    } catch (e: any) {
      alert('发送失败: ' + e.message);
      setMessages(prev => prev.filter(m => m.eventId !== tempEventId));
    } finally {
      setIsSending(false);
    }
  };

// ✨ 新增：不弹抽屉，直接把输入框文字发给 Agent 并跳转工作台
  const handleDirectAgentPlan = async () => {
    if (!inputText.trim() || !activeChatId) return;
    try {
      setIsPlanning(true);
      const preview = await plannerApi.createPlan({
        rawInstruction: inputText.trim(),
        workspaceContext: { chatId: activeChatId },
      });

      if (preview.transientReply || !preview.runtimeAvailable) {
        if (preview.assistantReply) alert(`🤖 Agent 回复: ${preview.assistantReply}`);
      } else {
        setActiveTaskId(preview.taskId || null);
        setPlanPreview(preview);
        onSwitchToWorkspace?.(); // 直接跳转工作台
      }
      setInputText('');
    } catch (e: any) {
      alert('唤醒 Agent 失败: ' + e.message);
    } finally {
      setIsPlanning(false);
    }
  };

  // 2. 唤醒 Agent (从抽屉提交)
  const handleStartAgentPlan = async () => {
    if (!agentInstruction.trim() || !activeChatId) return;
    try {
      setIsPlanning(true);
      setIsAgentDrawerOpen(false); // 提交后关闭抽屉
      
      const selectedTexts = messages
        .filter((msg) => selectedMessageIds.includes(msg.eventId))
        .map((msg) => `${msg.senderName}: ${msg.content}`);

      const preview = await plannerApi.createPlan({
        rawInstruction: agentInstruction.trim(),
        workspaceContext: {
          chatId: activeChatId,
          selectionType: selectedTexts.length > 0 ? 'MESSAGE' : undefined,
          selectedMessages: selectedTexts.length > 0 ? selectedTexts : undefined,
        },
      });

      if (preview.transientReply || !preview.runtimeAvailable) {
        if (preview.assistantReply) alert(`🤖 Agent 回复: ${preview.assistantReply}`);
      } else {
        setActiveTaskId(preview.taskId || null);
        setPlanPreview(preview);
        // ✨ 新增：成功唤醒后，自动跳转到工作台
        onSwitchToWorkspace?.();
      }
      
      // 清理状态
      setAgentInstruction('');
      exitSelectionMode();
    } catch (e: any) {
      alert('唤醒 Agent 失败: ' + e.message);
    } finally {
      setIsPlanning(false);
    }
  };

  const exitSelectionMode = () => {
    setIsSelectionMode(false);
    setSelectedMessageIds([]);
  };

  // ✨ 长按处理逻辑
  const handleTouchStart = (eventId: string) => {
    if (isSelectionMode) return;
    longPressTimerRef.current = setTimeout(() => {
      setIsSelectionMode(true);
      setSelectedMessageIds([eventId]);
      // 可以在这里加个震动反馈 navigator.vibrate?.(50);
    }, 500); // 500ms 触发长按
  };

  const handleTouchEnd = () => {
    if (longPressTimerRef.current) {
      clearTimeout(longPressTimerRef.current);
    }
  };

  const toggleMessageSelection = (eventId: string) => {
    setSelectedMessageIds(prev => 
      prev.includes(eventId) ? prev.filter(id => id !== eventId) : [...prev, eventId]
    );
  };

  // 3. 聊天室历史记录与 SSE 流 (与桌面端逻辑一致)
  useEffect(() => {
    if (!activeChatId || !accessToken) return;
    if (abortControllerRef.current) abortControllerRef.current.abort();
    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;

    const connectSSE = async () => {
      setMessages([]);
      setIsLoadingHistory(true);
      try {
        const historyRes = await imApi.getChatHistory({
          containerIdType: 'chat',
          containerId: activeChatId,
          sortType: 'ByCreateTimeDesc',
          pageSize: 20,
          signal: ctrl.signal,
        });
        if (ctrl.signal.aborted) return;
        const formattedHistory = historyRes.items.reverse().map((item: any) => ({
          eventId: item.messageId,
          senderOpenId: item.senderId,
          senderType: item.senderType,
          senderName: item.senderName,
          senderAvatar: item.senderAvatar,
          content: parseFeishuContent(item.content, item.msgType),
          createTime: item.createTime,
        }));
        setMessages(formattedHistory);
      } catch (err: any) {
        if (err.name !== 'CanceledError') console.warn('拉取历史消息失败', err);
      } finally {
        if (!ctrl.signal.aborted) setIsLoadingHistory(false);
      }

      if (ctrl.signal.aborted) return;

      try {
        await fetchEventSource(`${getBaseUrl()}/im/chats/${activeChatId}/messages/stream`, {
          method: 'GET',
          headers: { Authorization: `Bearer ${accessToken}`, Accept: 'text/event-stream' },
          signal: ctrl.signal,

          async onopen(response) {
            if (response.status === 401 || response.status === 403) {
              throw new Error('UNAUTHORIZED');
            }
          },

          onmessage(event) {
            console.log('[Mobile SSE 接收消息]', event.event, event.data);
            if (!event.event || event.event === 'message') {
              try {
                const msgData = JSON.parse(event.data);
                if (msgData.state === 'connected') return;
                setMessages((prev) => {
                  if (prev.some((m) => m.eventId === msgData.eventId || m.messageId === msgData.messageId)) return prev;
                  return [...prev, { ...msgData, content: parseFeishuContent(msgData.content, msgData.messageType || msgData.msgType) }];
                });
              } catch (e) {
                console.debug('JSON解析失败:', e);
              }
            }
          },

          onerror(err) {
            if (err.message === 'UNAUTHORIZED') {
              console.error('IM SSE 鉴权失败，停止重试');
              throw err;
            }
            console.warn('IM 聊天流网络波动，自动重连中...');
          }
        });
      } catch (err) {}
    };

    connectSSE();
    return () => ctrl.abort();
  }, [activeChatId, accessToken]);

  return (
    <div className="flex flex-col h-full bg-zinc-50/50 relative">
      {/* 消息列表区 */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {isLoadingHistory ? (
          <div className="flex justify-center py-4"><Loader2 className="h-5 w-5 animate-spin text-zinc-400" /></div>
        ) : messages.length === 0 ? (
          <div className="text-center text-sm text-zinc-400 mt-10">暂无消息记录</div>
        ) : (
          messages.map((msg) => {
            // ✨ 增强系统消息的识别判断
            const messageType = (msg.messageType || msg.msgType || '').toLowerCase();
            const isSystem = messageType === 'system' || (typeof msg.content === 'string' && msg.content.startsWith('[系统消息]'));
            const isBot = msg.senderType === 'app' || msg.senderOpenId?.includes('bot');
            const isMe = !isSystem && msg.senderName === user?.name;
            const isSelected = selectedMessageIds.includes(msg.eventId);

            return (
              <div 
                key={msg.eventId} 
                className="flex items-start gap-2 relative select-none" // ✨ 加了 select-none 防止长按时选中文字
                // 移动端触摸事件
                onTouchStart={() => handleTouchStart(msg.eventId)}
                onTouchEnd={handleTouchEnd}
                onTouchMove={handleTouchEnd}
                // ✨ 桌面端鼠标兼容事件（方便你在电脑上测试长按）
                onMouseDown={() => handleTouchStart(msg.eventId)}
                onMouseUp={handleTouchEnd}
                onMouseLeave={handleTouchEnd} // 鼠标移出消息区域也算中断
                
                onClick={() => isSelectionMode && toggleMessageSelection(msg.eventId)}
              >
                {/* 选择框 (仅在多选模式下显示) */}
                {isSelectionMode && (
                  <div className="flex items-center justify-center h-10 w-8 shrink-0">
                    {isSelected ? <CheckCircle2 className="h-5 w-5 text-blue-600" /> : <Circle className="h-5 w-5 text-zinc-300" />}
                  </div>
                )}

                <div className={`flex w-full ${isMe ? 'justify-end' : 'justify-start'} transition-transform ${isSelectionMode && isSelected ? 'scale-[0.98] opacity-80' : ''}`}>
                  <div className={`flex max-w-[85%] gap-2 ${isMe ? 'flex-row-reverse' : 'flex-row'}`}>
                    {/* ✨ 修复点：让 isSystem 也应用蓝色背景和 Bot 图标 */}
                    <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full shadow-sm overflow-hidden ${(isBot || isSystem) ? 'bg-blue-600 text-white' : 'bg-zinc-200 text-zinc-600'}`}>
                      {(isBot || isSystem) ? <Bot className="h-4 w-4" /> : msg.senderAvatar ? <img src={msg.senderAvatar} alt="avatar" /> : <span className="text-xs font-bold">{msg.senderName?.charAt(0)}</span>}
                    </div>
                    {/* ✨ 加上 min-w-0 防止被长文本撑爆 */}
                    <div className={`flex flex-col min-w-0 ${isMe ? 'items-end' : 'items-start'}`}>
                      <span className="text-[10px] text-zinc-400 mb-1 px-1 shrink-0">{isSystem ? '系统通知' : (isBot ? 'Agent Pilot' : msg.senderName)}</span>
                      {/* ✨ 加上 break-all 强制超长链接换行 */}
                      <div className={`px-3.5 py-2 text-sm shadow-sm leading-relaxed break-words break-all whitespace-pre-wrap ${isMe ? 'bg-blue-600 text-white rounded-2xl rounded-tr-sm' : 'bg-white border border-zinc-200 text-zinc-800 rounded-2xl rounded-tl-sm'}`}>
                        {msg.content}
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            );
          })
        )}
        {/* 👇 ============ 新增：移动端底部锚点 ============ 👇 */}
        <div ref={messagesEndRef} className="h-4 w-full shrink-0" />
      </div>

      {/* 底部输入区 / 悬浮操作栏 */}
      <div className="bg-white border-t border-zinc-200 px-3 py-2 shrink-0 pb-safe z-20 shadow-[0_-4px_15px_rgba(0,0,0,0.02)]">
        {isSelectionMode ? (
          <div className="flex items-center justify-between h-[44px]">
            <Button variant="ghost" size="sm" onClick={exitSelectionMode} className="text-zinc-500">取消</Button>
            <span className="text-sm font-medium text-zinc-800">已选择 {selectedMessageIds.length} 条</span>
            <Button 
              size="sm" 
              className="bg-blue-600 text-white shadow-sm"
              disabled={selectedMessageIds.length === 0}
              onClick={() => setIsAgentDrawerOpen(true)}
            >
              <Wand2 className="h-4 w-4 mr-1" /> 交给 Agent
            </Button>
          </div>
        ) : (
          <div className="flex items-end gap-2">
            <textarea
              value={inputText}
              onChange={(e) => setInputText(e.target.value)}
              className="flex-1 max-h-24 min-h-[40px] resize-none rounded-xl bg-zinc-100 border-transparent focus:bg-white focus:border-blue-500 focus:ring-1 focus:ring-blue-500 px-3 py-2 text-sm outline-none transition-colors"
              placeholder="长按多选，或输入..."
              rows={1}
            />
            <div className="flex gap-1.5 shrink-0 items-end mb-0.5">
              <Button 
                onClick={handleSendMessage} 
                disabled={!inputText.trim() || isSending} 
                size="icon" 
                className="h-9 w-9 rounded-full bg-blue-600 shadow-sm disabled:opacity-40 transition-opacity"
              >
                <Send className="h-4 w-4 ml-0.5 text-white" />
              </Button>
              <Button 
                onClick={() => inputText.trim() ? handleDirectAgentPlan() : setIsAgentDrawerOpen(true)} 
                disabled={isSending || isPlanning} 
                variant={inputText.trim() ? "default" : "outline"}
                size="icon" 
                className={`h-9 w-9 rounded-full shadow-sm transition-all ${
                  inputText.trim() 
                    ? 'bg-indigo-600 hover:bg-indigo-700 text-white' 
                    : 'border-blue-200 text-blue-600 bg-blue-50 hover:bg-blue-100'
                }`}
              >
                {isPlanning ? <Loader2 className="h-4 w-4 animate-spin" /> : <Bot className="h-4.5 w-4.5" />}
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* ✨ Agent 唤醒指令抽屉 (Drawer) */}
      <AnimatePresence>
        {isAgentDrawerOpen && (
          <>
            <motion.div 
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => setIsAgentDrawerOpen(false)}
              className="absolute inset-0 bg-zinc-900/40 backdrop-blur-sm z-40"
            />
            <motion.div
              initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }}
              transition={{ type: 'spring', damping: 25, stiffness: 200 }}
              className="absolute bottom-0 left-0 right-0 bg-white rounded-t-2xl shadow-2xl z-50 flex flex-col pb-safe"
            >
              <div className="p-4 border-b border-zinc-100 flex items-center justify-between">
                <div>
                  <h3 className="font-semibold text-zinc-800 flex items-center gap-2">
                    <Bot className="h-5 w-5 text-blue-600" /> 唤醒 Agent
                  </h3>
                  {selectedMessageIds.length > 0 && (
                    <p className="text-[10px] text-zinc-500 mt-1">已包含 {selectedMessageIds.length} 条选中的聊天上下文</p>
                  )}
                </div>
                <Button variant="ghost" size="icon" onClick={() => setIsAgentDrawerOpen(false)} className="h-8 w-8 text-zinc-500"><X className="h-5 w-5" /></Button>
              </div>
              <div className="p-4 space-y-4">
                <textarea
                  value={agentInstruction}
                  onChange={(e) => setAgentInstruction(e.target.value)}
                  placeholder="请输入您希望 Agent 执行的指令，例如：'帮我把以上内容总结成一份周报文档'"
                  className="w-full h-32 p-3 text-sm bg-zinc-50 border border-zinc-200 rounded-xl outline-none focus:border-blue-500 focus:bg-white transition-colors resize-none"
                  autoFocus
                />
                <Button 
                  className="w-full h-12 text-base font-medium bg-blue-600 hover:bg-blue-700 shadow-md"
                  disabled={!agentInstruction.trim() || isPlanning}
                  onClick={handleStartAgentPlan}
                >
                  {isPlanning ? <Loader2 className="h-5 w-5 animate-spin" /> : '发送给 Agent 办理'}
                </Button>
              </div>
            </motion.div>
          </>
        )}
      </AnimatePresence>

    </div>
  );
}