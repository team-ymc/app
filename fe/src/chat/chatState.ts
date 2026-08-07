// 채팅 화면 상태 reducer (설계 §5 상태 모델 + §3 멱등 재시도 의미론). 순수 함수 —
// ChatPanel은 useReducer로 이것만 감싼다. pending은 "결과를 모르는 재전송" 대상이다:
// 성공·확인된 실패에서 비우고, 스트림 중단(결과 미상)에서만 유지한다.

import type { ChatMessageItem } from '../api/chatSessions';
import type { SelectionAnchors } from '../routes/study/selectionAnchors';

export type ChatMessageStatus = 'GENERATING' | 'COMPLETED' | 'FAILED'; // 계약 ChatMessageStatus

export interface ChatMessage {
  key: string;
  role: 'user' | 'assistant';
  content: string;
  status: ChatMessageStatus;
  error: { code: string; message?: string; retryable: boolean } | null;
  selection: SelectionAnchors | null;
}

export interface ChatState {
  sessionId: string | null;
  messages: ChatMessage[];
  streaming: boolean;
  pending: { clientMessageId: string; content: string; selection: SelectionAnchors | null } | null;
}

export type ChatAction =
  | { type: 'send'; clientMessageId: string; content: string; selection?: SelectionAnchors | null; resend?: boolean }
  | { type: 'started'; sessionId: string }
  | { type: 'delta'; delta: string }
  | { type: 'completed'; content: string }
  | { type: 'failed'; confirmed: boolean; code: string; message?: string; retryable: boolean }
  | { type: 'duplicate'; sessionId: string; status: ChatMessageStatus }
  | { type: 'historyLoaded'; sessionId: string; items: ChatMessageItem[] }
  | { type: 'reset' }; // 새 대화(ask popup의 '새 대화에서') — initialChatState로 복귀

export const initialChatState: ChatState = {
  sessionId: null,
  messages: [], // {key, role: 'user'|'assistant', content, status, error}
  streaming: false,
  pending: null, // {clientMessageId, content}
};

let keySeq = 0;
function nextKey(prefix: string): string {
  keySeq += 1;
  return `${prefix}-${keySeq}`;
}

export function chatReducer(state: ChatState, action: ChatAction): ChatState {
  switch (action.type) {
    case 'send': {
      if (action.resend) {
        // 재전송 — 기존 말풍선을 유지하고 placeholder만 초기화. clientMessageId는 같을 수도(결과
        // 미상 재시도) 다를 수도(확인된 실패 후 새 UUID 재시도) 있으므로 pending을 매번 갱신한다 —
        // 그래야 이 재전송이 다시 중단됐을 때 올바른 id로 후속 재시도가 가능하다.
        return {
          ...state,
          streaming: true,
          pending: { clientMessageId: action.clientMessageId, content: action.content, selection: action.selection ?? null },
          messages: state.messages.map((m, i) =>
            i === state.messages.length - 1 && m.role === 'assistant'
              ? { ...m, content: '', status: 'GENERATING', error: null }
              : m),
        };
      }
      return {
        ...state,
        streaming: true,
        pending: { clientMessageId: action.clientMessageId, content: action.content, selection: action.selection ?? null },
        messages: [
          ...state.messages,
          { key: nextKey('u'), role: 'user', content: action.content, status: 'COMPLETED', error: null, selection: action.selection ?? null },
          { key: nextKey('a'), role: 'assistant', content: '', status: 'GENERATING', error: null, selection: null },
        ],
      };
    }
    case 'started':
      return { ...state, sessionId: action.sessionId };
    case 'delta':
      return { ...state, messages: updateLastAssistant(state.messages, (m) => ({ ...m, content: m.content + action.delta })) };
    case 'completed':
      return {
        ...state,
        streaming: false,
        pending: null, // 성공 — 재전송 대상 아님
        messages: updateLastAssistant(state.messages, (m) => ({ ...m, content: action.content, status: 'COMPLETED' })),
      };
    case 'failed':
      return {
        ...state,
        streaming: false,
        // 확인된 실패는 새 시도(새 UUID)가 맞으므로 pending을 비운다. 결과 미상이면 유지 (설계 §3)
        pending: action.confirmed ? null : state.pending,
        messages: updateLastAssistant(state.messages, (m) => ({
          ...m, status: 'FAILED',
          error: { code: action.code, message: action.message, retryable: action.retryable },
        })),
      };
    case 'duplicate': {
      // 이미 처리된(또는 처리 중인) 요청 — 스트림 없이 상태만 회수했다 (계약 ChatDuplicateMessageError)
      const notice = action.status === 'GENERATING'
        ? '답변을 생성하는 중입니다. 잠시 후 다시 질문해 주세요.'
        : action.status === 'COMPLETED'
          ? '이 질문의 답변은 이미 완료되었습니다.'
          : '이전 시도가 실패했습니다. 다시 시도해 주세요.';
      return {
        ...state,
        sessionId: action.sessionId,
        streaming: false,
        pending: action.status === 'FAILED' ? null : state.pending,
        messages: updateLastAssistant(state.messages, (m) => ({
          ...m, content: notice, status: action.status,
          error: action.status === 'FAILED' ? { code: 'DUPLICATE_MESSAGE', retryable: true } : null,
        })),
      };
    }
    case 'historyLoaded':
      // 재방문 로드 — 서버 항목으로 대화를 통째로 대체한다. 진행 중 스트림은 호출부가 먼저 abort.
      return {
        sessionId: action.sessionId,
        streaming: false,
        pending: null,
        messages: action.items.map((it) => ({
          key: it.messageId,
          role: it.role === 'USER' ? 'user' as const : 'assistant' as const,
          content: it.content ?? '',
          status: it.status,
          error: it.status === 'FAILED'
            ? { code: 'HISTORY_FAILED', message: '응답 생성에 실패했습니다.', retryable: false }
            : null,
          selection: it.selection ?? null,
        })),
      };
    case 'reset':
      return initialChatState;
    default:
      return state;
  }
}

function updateLastAssistant(messages: ChatMessage[], update: (m: ChatMessage) => ChatMessage): ChatMessage[] {
  const lastIndex = messages.length - 1;
  return messages.map((m, i) => (i === lastIndex && m.role === 'assistant' ? update(m) : m));
}
