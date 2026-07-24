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
    // '한'(ED 95 9C, 3바이트)의 첫 바이트 위치를 찾아 그 "내부"에서 자른다 —
    // 절반 분할은 멀티바이트 앞에서 잘려 TextDecoder(stream) 경계 처리를 검증하지 못한다
    const hanFirstByte = bytes.findIndex((b) => b === 0xed);
    expect(hanFirstByte).toBeGreaterThan(-1);
    expect(p.push(bytes.slice(0, hanFirstByte + 1))).toEqual([]);
    const events = p.push(bytes.slice(hanFirstByte + 1));
    expect(events[0].data.delta).toBe('한글');
  });

  it('CRLF 줄 끝(\\r\\n)으로 온 frame도 파싱한다 (SSE 표준)', () => {
    const p = createSseParser();
    const raw = 'event: message.delta\r\ndata: {"type":"message.delta","delta":"x"}\r\n\r\n';
    const events = p.push(enc.encode(raw));
    expect(events).toHaveLength(1);
    expect(events[0]).toEqual({ event: 'message.delta', data: { type: 'message.delta', delta: 'x' } });
  });

  it('CRLF가 chunk 경계에서 \\r|\\n으로 쪼개져도 안전하다', () => {
    const p = createSseParser();
    const raw = 'event: message.delta\r\ndata: {"type":"message.delta","delta":"y"}\r\n\r\n';
    const bytes = enc.encode(raw);
    const afterFirstCr = raw.indexOf('\r') + 1; // 첫 \r 바로 뒤(\n 앞)에서 분할
    expect(p.push(bytes.slice(0, afterFirstCr))).toEqual([]);
    const events = p.push(bytes.slice(afterFirstCr));
    expect(events[0].data.delta).toBe('y');
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
