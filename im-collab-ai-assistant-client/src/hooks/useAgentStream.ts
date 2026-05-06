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
  const runtimePollTimerRef = useRef<number | null>(null);

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
    if (runtimePollTimerRef.current) {
      window.clearInterval(runtimePollTimerRef.current);
      runtimePollTimerRef.current = null;
    }

    abortControllerRef.current = new AbortController();
    const ctrl = abortControllerRef.current;

    fetchRuntimeData(activeTaskId);

    runtimePollTimerRef.current = window.setInterval(() => {
      fetchRuntimeData(activeTaskId);
    }, 1800);

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

            type StreamEventPayload = {
              content?: string;
              taskId?: string;
            };

            const parsed: StreamEventPayload | null = (() => {
              try {
                return JSON.parse(event.data) as StreamEventPayload;
              } catch {
                return null;
              }
            })();

            if (event.event === 'TEXT_CHUNK') {
              if (parsed?.content) {
                textBufferRef.current += parsed.content;
                flushTextBuffer();
              }
              return;
            }

            if (parsed?.content && !parsed?.taskId) {
              textBufferRef.current += parsed.content;
              flushTextBuffer();
              return;
            }

            // ✅ 兜底：只要收到了业务事件（非 heartbeat），就触发一次 runtime 刷新
            const targetTaskId = parsed?.taskId || activeTaskId;
            throttledRefreshRuntime(targetTaskId);
          },
          onclose() {
            flushTextBuffer();
            setIsStreaming(false);
          },
          onerror(err) {
            console.warn('SSE 流异常波动:', err);
            // 👇 仅在未授权时真正中断；普通网络抖动交给库自动重连
            if (err.message === 'UNAUTHORIZED') {
              setIsStreaming(false);
              throw err;
            }
          },
        });
      } catch (err: unknown) {
        if (!(err instanceof Error && err.name === 'AbortError')) {
          console.error('SSE 意外断开:', err);
          setIsStreaming(false);
        }
      }
    };

    connectSSE();

    return () => {
      ctrl.abort();
      if (runtimePollTimerRef.current) {
        window.clearInterval(runtimePollTimerRef.current);
        runtimePollTimerRef.current = null;
      }
      flushTextBuffer();
      setIsStreaming(false);
    };
  }, [activeTaskId, token]);
}
