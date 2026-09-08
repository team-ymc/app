import { describe, it, expect, vi, afterEach } from 'vitest';
import { streamTranslation } from './translationStream';

const enc = new TextEncoder();

function sseBody(...frames: string[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const f of frames) controller.enqueue(enc.encode(f));
      controller.close();
    },
  });
}

function frame(event: string, dataObj: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(dataObj)}\n\n`;
}

function mockStreamFetch(frames: string[]) {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true, status: 200, body: sseBody(...frames), json: async () => ({}),
  }) as unknown as typeof fetch;
}

function mockErrorFetch(status: number, body: unknown) {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: false, status, body: null, json: async () => body,
  }) as unknown as typeof fetch;
}

const SELECTION = { start: { blockId: 'p0-b0', offset: 0 }, end: { blockId: 'p0-b1' } };

async function collect() {
  const actions: { type: string; [key: string]: unknown }[] = [];
  await streamTranslation({ paperId: 'p-1', selection: SELECTION, onEvent: (a) => actions.push(a) });
  return actions;
}

describe('translationStream — 스트림 소비와 종결 판정', () => {
  afterEach(() => vi.restoreAllMocks());

  it('성공 시퀀스: started → delta → completed, heartbeat 무시', async () => {
    mockStreamFetch([
      frame('translation.started', { type: 'translation.started', translationId: 't-1', status: 'GENERATING' }),
      frame('translation.delta', { type: 'translation.delta', delta: '어텐션' }),
      frame('heartbeat', { type: 'heartbeat' }),
      frame('translation.completed', { type: 'translation.completed', translation: '어텐션 함수', contentFormat: 'markdown', status: 'COMPLETED' }),
    ]);
    const actions = await collect();
    expect(actions.map((a) => a.type)).toEqual(['started', 'delta', 'completed']);
    expect(actions[0].translationId).toBe('t-1');
    expect(actions[2].translation).toBe('어텐션 함수');
  });

  it('요청 경로·헤더·바디가 계약과 일치한다', async () => {
    mockStreamFetch([frame('translation.completed', { type: 'translation.completed', translation: 'x', contentFormat: 'markdown', status: 'COMPLETED' })]);
    await collect();
    const [url, opts] = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toBe('/api/papers/p-1/inline-translations');
    expect(opts.method).toBe('POST');
    expect(opts.headers.Accept).toBe('text/event-stream');
    expect(JSON.parse(opts.body)).toEqual({ selection: SELECTION });
  });

  it('error event: 확인된 실패, retryable은 이벤트 값 그대로', async () => {
    mockStreamFetch([
      frame('translation.started', { type: 'translation.started', translationId: 't-1' }),
      frame('error', { type: 'error', status: 'FAILED', error: { code: 'SELECTION_TOO_LARGE', message: '너무 큼', retryable: false } }),
    ]);
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: true, code: 'SELECTION_TOO_LARGE', retryable: false });
  });

  it('error event: retryable true도 그대로 전달한다', async () => {
    mockStreamFetch([
      frame('translation.started', { type: 'translation.started', translationId: 't-1' }),
      frame('error', { type: 'error', status: 'FAILED', error: { code: 'AI_RUN_FAILED', message: '실패', retryable: true } }),
    ]);
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: true, code: 'AI_RUN_FAILED', retryable: true });
  });

  it('terminal 없는 EOF: 결과 미상 실패 — 성공으로 간주하지 않는다', async () => {
    mockStreamFetch([
      frame('translation.started', { type: 'translation.started', translationId: 't-1' }),
      frame('translation.delta', { type: 'translation.delta', delta: '일부' }),
    ]);
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: false, code: 'STREAM_INTERRUPTED' });
  });

  it('409 TRANSLATION_IN_PROGRESS: 재시도 가능한 확인된 실패', async () => {
    mockErrorFetch(409, { code: 'TRANSLATION_IN_PROGRESS', message: '이전 번역이 아직 진행 중입니다.' });
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: true, code: 'TRANSLATION_IN_PROGRESS', retryable: true });
  });

  it('429 CHAT_USAGE_LIMIT_EXCEEDED: 재시도 불가 확인된 실패', async () => {
    mockErrorFetch(429, { code: 'CHAT_USAGE_LIMIT_EXCEEDED', message: '한도 초과' });
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: true, code: 'CHAT_USAGE_LIMIT_EXCEEDED', retryable: false });
  });

  it('abort: 아무 이벤트도 내지 않고 조용히 끝난다', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(Object.assign(new Error('aborted'), { name: 'AbortError' })) as unknown as typeof fetch;
    const actions = await collect();
    expect(actions).toEqual([]);
  });

  it('네트워크 예외: 결과 미상 실패', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('network')) as unknown as typeof fetch;
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({ type: 'failed', confirmed: false });
  });
});
