// src/hooks/useAgentStream.ts
import { useEffect, useRef } from 'react';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { useThrottleFn } from 'ahooks';
import { useTaskStore } from '@/store/useTaskStore';
import { useAuthStore } from '@/store/useAuthStore';
import { plannerApi } from '@/services/api/planner';
import { getBaseUrl } from '@/services/api/client';

export function useAgentStream() {
  const { activeTaskId, setTaskRuntime, appendThinkingText, setIsStreaming } = useTaskStore();
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

  const fetchRuntimeData = async (taskId: string) => {
    try {
      const runtimeData = await plannerApi.getTaskRuntime(taskId);
      setTaskRuntime(runtimeData);
    } catch (e) {
      console.error('拉取 Runtime 快照失败:', e);
    }
  };

  const { run: throttledRefreshRuntime } = useThrottleFn(
    (taskId: string) => {
      fetchRuntimeData(taskId);
    },
    { wait: 400 }
  );

  useEffect(() => {
    if (!activeTaskId || !token) return;

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;

    fetchRuntimeData(activeTaskId);

const connectSSE = async () => {
      setIsStreaming(true);
      try {
        // ✨ 在这里拼接 getBaseUrl()
        await fetchEventSource(`${getBaseUrl()}/api/planner/tasks/${activeTaskId}/events/stream`, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'text/event-stream',
          },
          signal: ctrl.signal,
          async onopen(response) {
            if (response.status === 401 || response.status === 403) {
              throw new Error('UNAUTHORIZED'); 
            }
            if (!response.ok) {
              console.warn(`任务流连接异常: ${response.status}`);
            }
          },
          onmessage(event) {
            if (event.event === 'heartbeat') return;
            if (!event.data) return;

            let parsed: any = null;
            try {
              parsed = JSON.parse(event.data);
            } catch {
              parsed = null;
            }

            if (event.event === 'TEXT_CHUNK') {
              if (parsed?.content) {
                textBufferRef.current += parsed.content;
                flushTextBuffer();
              }
              return;
            }

            if (parsed?.content && !parsed?.status && !parsed?.taskId) {
              textBufferRef.current += parsed.content;
              flushTextBuffer();
              return;
            }

            const targetTaskId = parsed?.taskId || activeTaskId;
            if (parsed?.status || parsed?.taskId || parsed?.version !== undefined) {
              throttledRefreshRuntime(targetTaskId);
            }
          },
          onclose() {
            flushTextBuffer();
            setIsStreaming(false);
          },
          onerror(err) {
            console.warn('SSE 流异常波动:', err);
            setIsStreaming(false);
            // 👇 核心：只有未授权才阻断，普通网络抖动千万别抛出，让库自动帮你重连！
            if (err.message === 'UNAUTHORIZED') {
              throw err; 
            }
          },
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
