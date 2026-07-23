// SSE frame 증분 파서 (계약 x-stream-handling.frontend). network chunk가 UTF-8 문자·frame
// 경계와 일치한다고 가정하지 않는다 — TextDecoder(stream)와 내부 버퍼가 경계를 흡수한다.

export function createSseParser() {
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  return {
    /** 바이트 chunk를 넣고, 이번 chunk로 완성된 frame들을 {event, data} 배열로 받는다. */
    push(chunk) {
      buffer += decoder.decode(chunk, { stream: true });
      const events = [];
      let separator;
      while ((separator = buffer.indexOf('\n\n')) !== -1) {
        const rawFrame = buffer.slice(0, separator);
        buffer = buffer.slice(separator + 2);
        const parsed = parseFrame(rawFrame);
        if (parsed) events.push(parsed);
      }
      return events;
    },
  };
}

function parseFrame(rawFrame) {
  let event = 'message'; // SSE 기본 event 이름
  const dataLines = [];
  for (const line of rawFrame.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
    // 그 외 줄(주석 등)은 무시 — 계약은 event/data 두 줄만 쓴다
  }
  if (dataLines.length === 0) return null;
  return { event, data: JSON.parse(dataLines.join('\n')) };
}
