// src/hooks/useAgentStream.ts
import { useEffect, useRef } from 'react';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { useThrottleFn } from 'ahooks';
import { useTaskStore } from '@/store/useTaskStore';
import { useAuthStore } from '@/store/useAuthStore';
import { plannerApi } from '@/services/api/planner'; // ✨ 引入 API

export function useAgentStream() {
  const { activeTaskId, setPlanPreview, appendThinkingText, setIsStreaming } = useTaskStore();
  const token = useAuthStore((state) => state.accessToken);
  
  const textBufferRef = useRef('');
  const abortControllerRef = useRef<AbortController | null>(null);

  const { run: flushTextBuffer } = useThrottleFn(
    () => {
      if (textBufferRef.current) {
        appendThinkingText(textBufferRef.current);
        textBufferRef.current = ''; 
      }
    },
    { wait: 100 }
  );

  // ✨ 新增：拉取最新事实源数据的函数
  const fetchRuntimeData = async (taskId: string) => {
    try {
      const runtimeData = await plannerApi.getTaskRuntime(taskId);
      setPlanPreview(runtimeData); // 用最新的 runtime 数据覆盖 Zustand
    } catch (e) {
      console.error('拉取 Runtime 快照失败:', e);
    }
  };

  useEffect(() => {
    if (!activeTaskId || !token) return;

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;

    // 🌟🌟 新任务刚建立时，主动拉取一次初始状态
    fetchRuntimeData(activeTaskId);

    const connectSSE = async () => {
      setIsStreaming(true);
      try {
        await fetchEventSource(`/api/planner/tasks/${activeTaskId}/events/stream`, {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
          },
          signal: ctrl.signal,
          async onopen(response) {
            if (!response.ok) throw new Error(`SSE 连接失败: ${response.status}`);
          },
          onmessage(event) {
            // 1. 过滤心跳包
            if (event.event === 'heartbeat') return;

            // 2. 如果是文字流，继续打字机效果
            if (event.event === 'TEXT_CHUNK' || !event.event) { 
              try {
                const data = JSON.parse(event.data);
                if (data.content) {
                  textBufferRef.current += data.content;
                  flushTextBuffer();
                }
              } catch (e) {
                textBufferRef.current += event.data;
                flushTextBuffer();
              }
              return;
            }

            // 3. ✨ 核心变更：只要收到除文字流以外的任何有效事件（状态更新/步骤完成），立刻去拉取 /runtime！
            if (event.event === 'STATE_UPDATE' || event.event === 'STEP_COMPLETED' || event.event === 'ARTIFACT_CREATED') {
              fetchRuntimeData(activeTaskId);
            }
          },
          onclose() {
            flushTextBuffer();
            setIsStreaming(false);
          },
          onerror(err) {
            console.error('SSE 流异常:', err);
            setIsStreaming(false);
            throw err; 
          }
        });
      } catch (err: any) {
        if (err.name !== 'AbortError') console.error('SSE 意外断开:', err);
      }
    };

    connectSSE();

    return () => {
      ctrl.abort();
      flushTextBuffer();
      setIsStreaming(false);
    };
  }, [activeTaskId, token]); 
}