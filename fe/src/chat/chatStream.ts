// 채팅 SSE 스트림 클라이언트 (계약 createChatMessageStream). BE 연동 단일 접점의 결을
// 따르고(api.js·auth.js), 인증은 authFetch(Bearer + 401 refresh 재시도)를 그대로 탄다.
// POST + 인증 헤더 때문에 EventSource가 아니라 fetch ReadableStream이다 (계약 명시).

import { authFetch } from '../api/auth';
import { createSseParser } from './sseParser';

export type ChatStreamEvent =
  | { type: 'started'; sessionId: string; messageId: string }
  | { type: 'delta'; delta: string }
  | { type: 'completed'; content: string }
  | { type: 'failed'; confirmed: boolean; code: string; message: string; retryable: boolean }
  | { type: 'duplicate'; sessionId: string; messageId: string; status: 'GENERATING' | 'COMPLETED' | 'FAILED' };

export interface StreamOpts {
  paperId: string;
  sessionId: string | null;
  clientMessageId: string;
  content: string;
  signal?: AbortSignal;
  onEvent: (e: ChatStreamEvent) => void;
}

function isAbortError(e: unknown): boolean {
  return e instanceof Error && e.name === 'AbortError';
}

/**
 * 스트림을 소비하며 chatReducer에 dispatch 가능한 액션을 onEvent로 전달한다.
 * 종결 판정 (계약): message.completed=성공 / error=확인된 실패 /
 * terminal 없는 EOF·네트워크 예외=결과 미상 실패(성공 아님) / heartbeat=무시.
 */
export async function streamChatMessage({ paperId, sessionId, clientMessageId, content, signal, onEvent }: StreamOpts): Promise<void> {
  const body: { clientMessageId: string; content: string; sessionId?: string } = { clientMessageId, content };
  if (sessionId) body.sessionId = sessionId;

  let res: Response;
  try {
    res = await authFetch(`/api/papers/${paperId}/chat/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal,
    });
  } catch (e) {
    if (isAbortError(e)) return; // 언마운트 — 조용히 종료 (BE는 저장을 완주한다)
    onEvent({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결에 실패했습니다.', retryable: true });
    return;
  }

  if (!res.ok) {
    let errorBody: { code?: string; message?: string; sessionId?: string; messageId?: string; status?: 'GENERATING' | 'COMPLETED' | 'FAILED' } = {};
    try { errorBody = await res.json(); } catch { /* 비-JSON */ }
    if (res.status === 409 && errorBody.code === 'DUPLICATE_MESSAGE') {
      onEvent({ type: 'duplicate', sessionId: errorBody.sessionId!, messageId: errorBody.messageId!, status: errorBody.status! });
      return;
    }
    onEvent({
      type: 'failed', confirmed: true,
      code: errorBody.code || `HTTP_${res.status}`,
      message: errorBody.message || '요청에 실패했습니다.',
      retryable: false,
    });
    return;
  }

  const parser = createSseParser();
  const reader = res.body!.getReader();
  let terminal = false;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      for (const { event, data } of parser.push(value)) {
        switch (event) {
          case 'message.started': {
            const d = data as { sessionId: string; messageId: string };
            onEvent({ type: 'started', sessionId: d.sessionId, messageId: d.messageId });
            break;
          }
          case 'message.delta': {
            const d = data as { delta: string };
            onEvent({ type: 'delta', delta: d.delta });
            break;
          }
          case 'message.completed': {
            const d = data as { content: string };
            terminal = true;
            onEvent({ type: 'completed', content: d.content });
            break;
          }
          case 'error': {
            const d = data as { error: { code: string; message: string; retryable: boolean } };
            terminal = true;
            onEvent({ type: 'failed', confirmed: true, code: d.error.code, message: d.error.message, retryable: d.error.retryable });
            break;
          }
          case 'heartbeat':
            break; // 연결 유지용 — 상태를 바꾸지 않는다 (계약)
          default:
            break;
        }
      }
    }
  } catch (e) {
    if (isAbortError(e)) return;
    // 파싱 실패·수신 오류 — 결과 미상으로 처리
  }
  if (!terminal) {
    // terminal 없는 EOF를 성공으로 간주하지 않는다 (계약). 같은 clientMessageId로 재전송 대상.
    onEvent({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결이 끊어졌습니다.', retryable: true });
  }
}
