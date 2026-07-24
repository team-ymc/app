import { describe, it, expect } from 'vitest';
import { initialChatState, chatReducer } from './chatState';

function reduceAll(actions) {
  return actions.reduce(chatReducer, initialChatState);
}

describe('chatState — 스트림 이벤트를 화면 상태로', () => {
  const send = { type: 'send', clientMessageId: 'c-1', content: '질문' };
  const started = { type: 'started', sessionId: 's-1', messageId: 'm-1' };

  it('send: user 메시지와 GENERATING placeholder를 추가하고 입력을 잠근다', () => {
    const s = reduceAll([send]);
    expect(s.messages).toHaveLength(2);
    expect(s.messages[0]).toMatchObject({ role: 'user', content: '질문' });
    expect(s.messages[1]).toMatchObject({ role: 'assistant', status: 'GENERATING', content: '' });
    expect(s.streaming).toBe(true);
    expect(s.pending).toEqual({ clientMessageId: 'c-1', content: '질문' });
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
    expect(s.pending).toEqual({ clientMessageId: 'c-1', content: '질문' }); // 유지!
  });

  it('409 DUPLICATE(GENERATING): 안내로 교체하고 sessionId를 회수한다', () => {
    const s = reduceAll([send,
      { type: 'duplicate', sessionId: 's-9', messageId: 'm-9', status: 'GENERATING' }]);
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
});
