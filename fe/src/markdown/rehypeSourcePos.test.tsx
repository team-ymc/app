import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { PaperMarkdown } from './PaperMarkdown';

function renderSrc(src: string) {
  return render(<PaperMarkdown sourcePos>{src}</PaperMarkdown>).container;
}

describe('rehypeSourcePos', () => {
  it('일반 텍스트와 강조 내부를 원문 좌표 span으로 감싼다', () => {
    const src = '이 방식은 **병렬화**가 가능해 $E=mc^2$ 처리한다.';
    const container = renderSrc(src);
    const spans = [...container.querySelectorAll('span[data-src-start]:not([data-src-math])')];
    expect(spans.map((s) => s.textContent)).toEqual(['이 방식은 ', '병렬화', '가 가능해 ', ' 처리한다.']);
    for (const span of spans) {
      const from = Number(span.getAttribute('data-src-start'));
      const to = Number(span.getAttribute('data-src-end'));
      expect(src.slice(from, to)).toBe(span.textContent);
    }
  });

  it('인라인 수식은 래퍼가 KaTeX 교체 후에도 살아남는다', () => {
    const container = renderSrc('앞 $E=mc^2$ 뒤');
    const math = container.querySelector('span[data-src-math]')!;
    expect(math.getAttribute('data-src-start')).toBe('2');
    expect(math.getAttribute('data-src-end')).toBe('10');
    expect(math.querySelector('.katex')).not.toBeNull();
  });

  it('이스케이프가 섞인 텍스트는 감싸지 않는다', () => {
    const container = renderSrc('이스케이프 \\*별표\\* 끝');
    expect(container.querySelector('span[data-src-start]')).toBeNull();
    expect(container.textContent).toContain('*별표*');
  });

  it('길이가 보존되는 치환(U+0000 → U+FFFD)도 거부한다', () => {
    const container = renderSrc('널\u0000 문자');
    expect(container.querySelector('span[data-src-start]')).toBeNull();
  });

  it('heading은 # prefix를 제외한 좌표를 준다', () => {
    const container = renderSrc('## 결론');
    const span = container.querySelector('h2 span[data-src-start]')!;
    expect([span.getAttribute('data-src-start'), span.getAttribute('data-src-end')]).toEqual(['3', '5']);
    expect(span.textContent).toBe('결론');
  });

  it('이모지가 있어도 좌표가 UTF-16 기준으로 일치한다', () => {
    const src = '로켓 🚀 끝';
    const container = renderSrc(src);
    const span = container.querySelector('span[data-src-start]')!;
    const from = Number(span.getAttribute('data-src-start'));
    const to = Number(span.getAttribute('data-src-end'));
    expect(src.slice(from, to)).toBe(span.textContent);
  });

  it('중첩 span을 만들지 않는다', () => {
    const container = renderSrc('중첩 **확인** 문자열');
    expect(container.querySelectorAll('span[data-src-start] span[data-src-start]').length).toBe(0);
  });

  it('sourcePos를 끄면 좌표 span이 없다 (채팅 렌더 무변경)', () => {
    const container = render(<PaperMarkdown>{'일반 **문장**'}</PaperMarkdown>).container;
    expect(container.querySelector('span[data-src-start]')).toBeNull();
  });

  it('sourcePos를 켜도 이미지 문단은 그대로 figure로 언래핑된다', () => {
    const container = renderSrc('![캡션](/x.svg)');
    expect(container.querySelector('p')).toBeNull();
    expect(container.querySelector('figure')).not.toBeNull();
  });

  it('블록 수식은 좌표 span으로 감싸지 않는다', () => {
    const container = renderSrc('$$\nE=mc^2\n$$');
    expect(container.querySelector('span[data-src-start]')).toBeNull();
    expect(container.querySelector('.katex-display, .katex')).not.toBeNull();
  });
});
