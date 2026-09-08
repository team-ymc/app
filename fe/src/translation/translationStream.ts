// 인라인 번역 SSE 클라이언트 (계약 createInlineTranslationStream). chatStream.ts와 같은 구조 —
// POST + 인증 헤더 때문에 EventSource가 아니라 fetch ReadableStream이다.
import { authFetch } from '../api/auth';
import { createSseParser } from '../chat/sseParser';
import type { SelectionAnchors } from '../routes/study/selectionAnchors';

export type TranslationStreamEvent =
  | { type: 'started'; translationId: string }
  | { type: 'delta'; delta: string }
  | { type: 'completed'; translation: string }
  | { type: 'failed'; confirmed: boolean; code: string; message: string; retryable: boolean };

export interface TranslationStreamOpts {
  paperId: string;
  selection: SelectionAnchors;
  signal?: AbortSignal;
  onEvent: (e: TranslationStreamEvent) => void;
}

function isAbortError(e: unknown): boolean {
  return e instanceof Error && e.name === 'AbortError';
}

/**
 * 종결 판정 (계약): translation.completed=성공 / error=확인된 실패 /
 * terminal 없는 EOF·네트워크 예외=결과 미상 실패 / heartbeat=무시.
 * abort는 "듣기 중단"일 뿐이다 — translation.started 이후라면 BE는 완주한다.
 */
export async function streamTranslation({ paperId, selection, signal, onEvent }: TranslationStreamOpts): Promise<void> {
  let res: Response;
  try {
    res = await authFetch(`/api/papers/${paperId}/inline-translations`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ selection }),
      signal,
    });
  } catch (e) {
    if (isAbortError(e)) return;
    onEvent({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결에 실패했습니다.', retryable: true });
    return;
  }

  if (!res.ok) {
    let errorBody: { code?: string; message?: string } = {};
    try { errorBody = await res.json(); } catch { /* 비-JSON */ }
    const code = errorBody.code || `HTTP_${res.status}`;
    onEvent({
      type: 'failed', confirmed: true, code,
      message: errorBody.message || '번역 요청에 실패했습니다.',
      retryable: code === 'TRANSLATION_IN_PROGRESS', // 이전 번역이 끝나면 같은 선택으로 다시 보낼 수 있다
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
          case 'translation.started': {
            const d = data as { translationId: string };
            onEvent({ type: 'started', translationId: d.translationId });
            break;
          }
          case 'translation.delta': {
            const d = data as { delta: string };
            onEvent({ type: 'delta', delta: d.delta });
            break;
          }
          case 'translation.completed': {
            const d = data as { translation: string };
            terminal = true;
            onEvent({ type: 'completed', translation: d.translation });
            break;
          }
          case 'error': {
            const d = data as { error: { code: string; message: string; retryable: boolean } };
            terminal = true;
            onEvent({ type: 'failed', confirmed: true, code: d.error.code, message: d.error.message, retryable: d.error.retryable });
            break;
          }
          default:
            break; // heartbeat 등 — 상태를 바꾸지 않는다
        }
      }
    }
  } catch (e) {
    if (isAbortError(e)) return;
  }
  if (!terminal) {
    onEvent({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결이 끊어졌습니다.', retryable: true });
  }
}
