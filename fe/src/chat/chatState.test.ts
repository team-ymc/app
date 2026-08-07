import { describe, it, test, expect } from 'vitest';
import { initialChatState, chatReducer, type ChatAction } from './chatState';
import type { ChatMessageItem } from '../api/chatSessions';

function reduceAll(actions: ChatAction[]) {
  return actions.reduce(chatReducer, initialChatState);
}

describe('chatState — 스트림 이벤트를 화면 상태로', () => {
  const send: ChatAction = { type: 'send', clientMessageId: 'c-1', content: '질문' };
  const started: ChatAction = { type: 'started', sessionId: 's-1' };

  it('send: user 메시지와 GENERATING placeholder를 추가하고 입력을 잠근다', () => {
    const s = reduceAll([send]);
    expect(s.messages).toHaveLength(2);
    expect(s.messages[0]).toMatchObject({ role: 'user', content: '질문' });
    expect(s.messages[1]).toMatchObject({ role: 'assistant', status: 'GENERATING', content: '' });
    expect(s.streaming).toBe(true);
    expect(s.pending).toEqual({ clientMessageId: 'c-1', content: '질문', selection: null });
  });

  it('send는 user 메시지와 pending에 selection을 담는다', () => {
    const sel = { start: { blockId: 'b1' }, end: { blockId: 'b1' } };
    const s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '질문', selection: sel });
    expect(s.messages[0].selection).toEqual(sel);
    expect(s.pending).toEqual({ clientMessageId: 'c1', content: '질문', selection: sel });
  });

  it('started → delta → completed: append 후 전체 content로 replace한다', () => {
    const s = reduceAll([send, started,
      { type: 'delta', delta: '핵심은' },
      { type: 'delta', delta: ' 어텐션' },
      { type: 'completed', content: '핵심은 **어텐션**입니다.' }]);
    const assistant = s.messages[1];
    expect(assistant.status).toBe('COMPLETED');
    expect(assistant.content).toBe('핵심은 **어텐션**입니다.'); // 누적이 아니라 replace
    expect(s.sessionId).toBe('s-1');
    expect(s.streaming).toBe(false);
    expect(s.pending).toBeNull(); // 성공 — 재전송 대상 없음
  });

  it('확인된 실패(error event): FAILED + pending 해제 → 재시도는 새 UUID 대상', () => {
    const s = reduceAll([send, started,
      { type: 'failed', confirmed: true, code: 'AI_RUN_FAILED', message: '실패', retryable: true }]);
    expect(s.messages[1].status).toBe('FAILED');
    expect(s.messages[1].error).toMatchObject({ code: 'AI_RUN_FAILED', retryable: true });
    expect(s.streaming).toBe(false);
    expect(s.pending).toBeNull();
  });

  it('결과 모르는 실패(EOF·네트워크): pending 유지 → 같은 clientMessageId로 재전송', () => {
    const s = reduceAll([send, started,
      { type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: '연결 끊김', retryable: true }]);
    expect(s.messages[1].status).toBe('FAILED');
    expect(s.pending).toEqual({ clientMessageId: 'c-1', content: '질문', selection: null }); // 유지!
  });

  it('409 DUPLICATE(GENERATING): 안내로 교체하고 sessionId를 회수한다', () => {
    const s = reduceAll([send,
      { type: 'duplicate', sessionId: 's-9', status: 'GENERATING' }]);
    expect(s.sessionId).toBe('s-9');
    expect(s.messages[1].status).toBe('GENERATING');
    expect(s.streaming).toBe(false); // 스트림은 없다 — 완료를 받을 채널이 없음을 안내
    expect(s.messages[1].content).toContain('생성');
  });

  it('재전송(send with 기존 pending): 메시지를 중복 추가하지 않는다', () => {
    const first = reduceAll([send, started,
      { type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED', message: 'x', retryable: true }]);
    const s = chatReducer(first, { type: 'send', clientMessageId: 'c-1', content: '질문', resend: true });
    expect(s.messages).toHaveLength(2); // user·assistant 그대로, placeholder만 리셋
    expect(s.messages[1]).toMatchObject({ status: 'GENERATING', content: '' });
    expect(s.streaming).toBe(true);
  });

  test('resend는 pending을 새 clientMessageId로 갱신한다', () => {
    let s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '질문' });
    s = chatReducer(s, { type: 'failed', confirmed: true, code: 'AI_RUN_FAILED', retryable: true });
    s = chatReducer(s, { type: 'send', clientMessageId: 'c2', content: '질문', resend: true });
    expect(s.pending).toEqual({ clientMessageId: 'c2', content: '질문', selection: null });
    expect(s.messages).toHaveLength(2); // 말풍선 재사용, 추가 없음
  });

  test('reset은 초기 상태로 돌아간다', () => {
    let s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '질문' });
    s = chatReducer(s, { type: 'reset' });
    expect(s).toEqual(initialChatState);
  });
});

const item = (over: Partial<ChatMessageItem>): ChatMessageItem => ({
  messageId: 'm-1', role: 'USER', content: '질문', status: 'COMPLETED',
  seq: 1, createdAt: '2026-08-01T00:00:00Z', selection: null, ...over,
});

describe('historyLoaded', () => {
  test('서버 항목을 로컬 메시지로 매핑하고 sessionId를 세팅한다', () => {
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's-1',
      items: [
        item({ messageId: 'm-1', role: 'USER', content: '질문', seq: 1 }),
        item({ messageId: 'm-2', role: 'ASSISTANT', content: '**답변**', seq: 2 }),
      ],
    });
    expect(s.sessionId).toBe('s-1');
    expect(s.streaming).toBe(false);
    expect(s.pending).toBeNull();
    expect(s.messages).toEqual([
      { key: 'm-1', role: 'user', content: '질문', status: 'COMPLETED', error: null, selection: null },
      { key: 'm-2', role: 'assistant', content: '**답변**', status: 'COMPLETED', error: null, selection: null },
    ]);
  });

  test('GENERATING assistant는 content 빈 문자열 + 상태 보존', () => {
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's-1',
      items: [item({ messageId: 'm-3', role: 'ASSISTANT', content: null, status: 'GENERATING', seq: 2 })],
    });
    expect(s.messages[0]).toEqual({ key: 'm-3', role: 'assistant', content: '', status: 'GENERATING', error: null, selection: null });
  });

  test('historyLoaded는 항목의 selection을 보존한다', () => {
    const sel = { start: { blockId: 'b1' }, end: { blockId: 'b2' } };
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's1',
      items: [item({ messageId: 'm1', role: 'USER', content: '질문', status: 'COMPLETED', seq: 1, createdAt: 't', selection: sel })],
    });
    expect(s.messages[0].selection).toEqual(sel);
  });

  test('FAILED assistant는 retryable=false 에러로 매핑된다 — 과거 실패에 재시도 미노출', () => {
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's-1',
      items: [item({ messageId: 'm-4', role: 'ASSISTANT', content: null, status: 'FAILED', seq: 2 })],
    });
    expect(s.messages[0].status).toBe('FAILED');
    expect(s.messages[0].error).toEqual({ code: 'HISTORY_FAILED', message: '응답 생성에 실패했습니다.', retryable: false });
  });

  test('기존 대화 상태를 통째로 대체한다', () => {
    let s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '기존 질문' });
    s = chatReducer(s, { type: 'historyLoaded', sessionId: 's-9', items: [item({})] });
    expect(s.messages).toHaveLength(1);
    expect(s.pending).toBeNull();
  });
});
