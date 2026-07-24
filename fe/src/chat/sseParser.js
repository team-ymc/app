// SSE frame 증분 파서 (계약 x-stream-handling.frontend). network chunk가 UTF-8 문자·frame
// 경계와 일치한다고 가정하지 않는다 — TextDecoder(stream)와 내부 버퍼가 경계를 흡수한다.
// 줄 끝은 SSE 표준(LF·CRLF·CR)을 모두 허용한다 — 우리 BE는 LF지만 표준 방어다.

export function createSseParser() {
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventName = 'message'; // SSE 기본 event 이름
  let dataLines = [];

  function finishFrame() {
    if (dataLines.length === 0) {
      eventName = 'message';
      return null;
    }
    const data = dataLines.join('\n');
    const name = eventName;
    dataLines = [];
    eventName = 'message';
    return { event: name, data: JSON.parse(data) };
  }

  function handleLine(line) {
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
    // 그 외 줄(주석 등)은 무시 — 계약은 event/data 두 줄만 쓴다
  }

  return {
    /** 바이트 chunk를 넣고, 이번 chunk로 완성된 frame들을 {event, data} 배열로 받는다. */
    push(chunk) {
      buffer += decoder.decode(chunk, { stream: true });
      const events = [];
      for (;;) {
        const lineEnd = /\r\n|\r|\n/.exec(buffer);
        if (!lineEnd) break;
        if (lineEnd[0] === '\r' && lineEnd.index === buffer.length - 1) {
          break; // CRLF가 chunk 경계에서 \r|\n으로 쪼개졌을 수 있다 — 다음 chunk까지 보류
        }
        const line = buffer.slice(0, lineEnd.index);
        buffer = buffer.slice(lineEnd.index + lineEnd[0].length);
        if (line === '') {
          const parsed = finishFrame(); // 빈 줄 = frame 종료
          if (parsed) events.push(parsed);
        } else {
          handleLine(line);
        }
      }
      return events;
    },
  };
}
