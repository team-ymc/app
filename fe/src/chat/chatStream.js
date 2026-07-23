// 채팅 SSE 스트림 클라이언트 (계약 createChatMessageStream). BE 연동 단일 접점의 결을
// 따르고(api.js·auth.js), 인증은 authFetch(Bearer + 401 refresh 재시도)를 그대로 탄다.
// POST + 인증 헤더 때문에 EventSource가 아니라 fetch ReadableStream이다 (계약 명시).

import { authFetch } from '../auth';
import { createSseParser } from './sseParser';

/**
 * 스트림을 소비하며 chatReducer에 dispatch 가능한 액션을 onEvent로 전달한다.
 * 종결 판정 (계약): message.completed=성공 / error=확인된 실패 /
 * terminal 없는 EOF·네트워크 예외=결과 미상 실패(성공 아님) / heartbeat=무시.
 */
export async function streamChatMessage({ paperId, sessionId, clientMessageId, content, signal, onEvent }) {
  const body = { clientMessageId, content };
  if (sessionId) body.sessionId = sessionId;

  let res;
  try {
    res = await authFetch(`/api/papers/${paperId}/chat/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(body),
      signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') return; // 언마운트 — 조용히 종료 (BE는 저장을 완주한다)
    onEvent({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결에 실패했습니다.', retryable: true });
    return;
  }

  if (!res.ok) {
    let errorBody = {};
    try { errorBody = await res.json(); } catch { /* 비-JSON */ }
    if (res.status === 409 && errorBody.code === 'DUPLICATE_MESSAGE') {
      onEvent({ type: 'duplicate', sessionId: errorBody.sessionId, messageId: errorBody.messageId, status: errorBody.status });
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
  const reader = res.body.getReader();
  let terminal = false;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      for (const { event, data } of parser.push(value)) {
        switch (event) {
          case 'message.started':
            onEvent({ type: 'started', sessionId: data.sessionId, messageId: data.messageId });
            break;
          case 'message.delta':
            onEvent({ type: 'delta', delta: data.delta });
            break;
          case 'message.completed':
            terminal = true;
            onEvent({ type: 'completed', content: data.content });
            break;
          case 'error':
            terminal = true;
            onEvent({ type: 'failed', confirmed: true, code: data.error.code, message: data.error.message, retryable: data.error.retryable });
            break;
          case 'heartbeat':
            break; // 연결 유지용 — 상태를 바꾸지 않는다 (계약)
          default:
            break;
        }
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') return;
    // 파싱 실패·수신 오류 — 결과 미상으로 처리
  }
  if (!terminal) {
    // terminal 없는 EOF를 성공으로 간주하지 않는다 (계약). 같은 clientMessageId로 재전송 대상.
    onEvent({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결이 끊어졌습니다.', retryable: true });
  }
}
