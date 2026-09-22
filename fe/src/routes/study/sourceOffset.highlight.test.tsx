import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { resolveOffset } from './sourceOffset';
import { PaperMarkdown } from '../../markdown/PaperMarkdown';

describe('resolveOffset with prerequisite highlight', () => {
  it('하이라이트로 쪼개진 뒤쪽 조각에서도 원문 offset이 맞는다', () => {
    const { container } = render(
      <PaperMarkdown sourcePos highlights={[{ id: 'h', start: 8, end: 15 }]}>{'softmax dropout layer'}</PaperMarkdown>,
    );
    const block = { id: 'b', type: 'para', markdown: 'softmax dropout layer', sourceText: 'softmax dropout layer', sourceOffsetShift: 0 } as const;
    const tail = Array.from(container.querySelectorAll('[data-src-start]')).at(-1)!.firstChild as Text;
    // ' layer'의 두 번째 글자 'l' = 원문 16
    expect(resolveOffset(tail, 1, block, 'start')).toBe(16);
  });
});
