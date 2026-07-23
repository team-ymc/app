import { describe, it, expect, vi, afterEach } from 'vitest';
import { streamChatMessage } from './chatStream';

const enc = new TextEncoder();

function sseBody(...frames) {
  return new ReadableStream({
    start(controller) {
      for (const f of frames) controller.enqueue(enc.encode(f));
      controller.close();
    },
  });
}

function frame(event, dataObj) {
  return `event: ${event}\ndata: ${JSON.stringify(dataObj)}\n\n`;
}

function mockStreamFetch(frames, { status = 200 } = {}) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: status === 200, status, body: sseBody(...frames),
    json: async () => ({}),
  });
}

async function collect(overrides = {}) {
  const actions = [];
  await streamChatMessage({
    paperId: 'p-1', sessionId: null, clientMessageId: 'c-1', content: '질문',
    onEvent: (a) => actions.push(a), ...overrides,
  });
  return actions;
}

describe('chatStream — 스트림 소비와 종결 판정', () => {
  afterEach(() => vi.restoreAllMocks());

  it('성공 시퀀스: started → delta → completed 순서로 콜백하고 heartbeat는 무시한다', async () => {
    mockStreamFetch([
      frame('message.started', { type: 'message.started', sessionId: 's-1', messageId: 'm-1' }),
      frame('message.delta', { type: 'message.delta', delta: '안녕' }),
      frame('heartbeat', { type: 'heartbeat' }),
      frame('message.completed', { type: 'message.completed', content: '안녕하세요', status: 'COMPLETED' }),
    ]);
    const actions = await collect();
    expect(actions.map((a) => a.type)).toEqual(['started', 'delta', 'completed']);
    expect(actions[2].content).toBe('안녕하세요');
  });

  it('요청 바디·헤더가 계약과 일치한다 (Accept, sessionId 생략)', async () => {
    mockStreamFetch([frame('message.completed', { type: 'message.completed', content: 'x', status: 'COMPLETED' })]);
    await collect();
    const [url, opts] = global.fetch.mock.calls[0];
    expect(url).toBe('/api/papers/p-1/chat/messages');
    expect(opts.headers.Accept).toBe('text/event-stream');
    const body = JSON.parse(opts.body);
    expect(body).toEqual({ clientMessageId: 'c-1', content: '질문' }); // sessionId null이면 키 제외
  });

  it('error event: 확인된 실패로 콜백한다', async () => {
    mockStreamFetch([
      frame('message.started', { type: 'message.started', sessionId: 's-1', messageId: 'm-1' }),
      frame('error', { type: 'error', status: 'FAILED', error: { code: 'AI_RUN_FAILED', message: '실패', retryable: true } }),
    ]);
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: true, code: 'AI_RUN_FAILED', retryable: true });
  });

  it('terminal 없는 EOF: 결과 미상 실패로 콜백한다 — 성공으로 간주하지 않는다', async () => {
    mockStreamFetch([
      frame('message.started', { type: 'message.started', sessionId: 's-1', messageId: 'm-1' }),
      frame('message.delta', { type: 'message.delta', delta: '일부' }),
    ]);
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED' });
  });

  it('409 DUPLICATE_MESSAGE: duplicate 액션으로 콜백한다', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 409, body: null,
      json: async () => ({ code: 'DUPLICATE_MESSAGE', message: '중복', sessionId: 's-9', messageId: 'm-9', status: 'GENERATING' }),
    });
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'duplicate', sessionId: 's-9', status: 'GENERATING' });
  });

  it('그 외 HTTP 오류: code를 담은 확인된 실패로 콜백한다', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 409, body: null,
      json: async () => ({ code: 'CHAT_RUN_IN_PROGRESS', message: '이미 생성 중' }),
    });
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: true, code: 'CHAT_RUN_IN_PROGRESS' });
  });

  it('네트워크 예외: 결과 미상 실패로 콜백한다', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('network error'));
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: false });
  });
});
