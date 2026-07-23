import { describe, it, expect } from 'vitest';
import { createSseParser } from './sseParser';

const enc = new TextEncoder();

function frame(event, dataObj) {
  return `event: ${event}\ndata: ${JSON.stringify(dataObj)}\n\n`;
}

describe('sseParser — 계약 x-stream-handling.frontend의 파싱 규칙', () => {
  it('완성된 frame 하나를 event 이름과 JSON data로 파싱한다', () => {
    const p = createSseParser();
    const events = p.push(enc.encode(frame('message.delta', { type: 'message.delta', delta: 'ab' })));
    expect(events).toEqual([{ event: 'message.delta', data: { type: 'message.delta', delta: 'ab' } }]);
  });

  it('한 chunk에 frame 여러 개가 오면 전부 순서대로 반환한다', () => {
    const p = createSseParser();
    const events = p.push(enc.encode(
      frame('message.started', { type: 'message.started' }) + frame('message.delta', { type: 'message.delta', delta: 'x' })));
    expect(events.map((e) => e.event)).toEqual(['message.started', 'message.delta']);
  });

  it('frame이 chunk 두 개에 걸쳐 잘려도 완성 시점에 반환한다', () => {
    const p = createSseParser();
    const whole = frame('message.completed', { type: 'message.completed', content: '끝' });
    const cut = 10; // "event: mes" 중간
    expect(p.push(enc.encode(whole.slice(0, cut)))).toEqual([]);
    const events = p.push(enc.encode(whole.slice(cut)));
    expect(events[0].data.content).toBe('끝');
  });

  it('한글 UTF-8 바이트가 chunk 중간에서 잘려도 깨지지 않는다', () => {
    const p = createSseParser();
    const bytes = enc.encode(frame('message.delta', { type: 'message.delta', delta: '한글' }));
    // '한'(3바이트)의 중간에서 자르기 위해 바이트 배열을 절반으로 쪼갠다
    const mid = Math.floor(bytes.length / 2);
    expect(p.push(bytes.slice(0, mid))).toEqual([]);
    const events = p.push(bytes.slice(mid));
    expect(events[0].data.delta).toBe('한글');
  });

  it('heartbeat frame도 그대로 반환한다 (무시는 상위 계층 몫)', () => {
    const p = createSseParser();
    const events = p.push(enc.encode(frame('heartbeat', { type: 'heartbeat' })));
    expect(events[0].event).toBe('heartbeat');
  });

  it('data가 JSON이 아니면 예외를 던진다', () => {
    const p = createSseParser();
    expect(() => p.push(enc.encode('event: message.delta\ndata: {broken\n\n'))).toThrow();
  });
});
