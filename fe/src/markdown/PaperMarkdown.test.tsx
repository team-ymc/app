import { describe, expect, test } from 'vitest';
import { render } from '@testing-library/react';
import { PaperMarkdown } from './PaperMarkdown';

describe('PaperMarkdown', () => {
  test('KaTeX로 수식을 렌더한다', () => {
    const { container } = render(<PaperMarkdown>{'인라인 $E = mc^2$ 수식'}</PaperMarkdown>);
    expect(container.querySelector('.katex')).not.toBeNull();
  });

  test('깨진 수식에도 죽지 않는다', () => {
    const { container } = render(<PaperMarkdown>{'$\\undefinedmacro{x}$'}</PaperMarkdown>);
    expect(container.textContent).toContain('undefinedmacro'); // 원문 노출, throw 없음
  });

  test('GFM 표를 렌더한다', () => {
    const { container } = render(<PaperMarkdown>{'| a |\n| - |\n| 1 |'}</PaperMarkdown>);
    expect(container.querySelector('table')).not.toBeNull();
  });

  test('이미지는 figure 프레임 + figcaption(alt)으로 렌더한다', () => {
    const { container } = render(<PaperMarkdown>{'![그림 1: 캡션](/x.svg)'}</PaperMarkdown>);
    expect(container.querySelector('figure.pt-figure img')?.getAttribute('src')).toBe('/x.svg');
    expect(container.querySelector('figcaption')?.textContent).toBe('그림 1: 캡션');
    expect(container.querySelector('p figure')).toBeNull(); // figure가 p 안에 있으면 안 된다
  });
});
