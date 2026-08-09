import { describe, expect, test } from 'vitest';
import { render } from '@testing-library/react';
import { InlineTex } from './InlineTex';

describe('InlineTex', () => {
  test('$...$ 구간을 KaTeX로 렌더하고 나머지 텍스트는 남긴다', () => {
    const { container } = render(<InlineTex>{'3. $L_2$ 정규화'}</InlineTex>);
    expect(container.querySelector('.katex')).not.toBeNull();
    expect(container.textContent).not.toContain('$');
    expect(container.textContent).toContain('3. ');
    expect(container.textContent).toContain(' 정규화');
  });

  test('수식이 없으면 원문 그대로다', () => {
    const { container } = render(<InlineTex>{'1. Introduction'}</InlineTex>);
    expect(container.textContent).toBe('1. Introduction');
    expect(container.querySelector('ol')).toBeNull(); // markdown으로 해석되면 안 된다
  });

  test('깨진 수식에도 죽지 않는다', () => {
    const { container } = render(<InlineTex>{'$\\undefinedmacro{x}$'}</InlineTex>);
    expect(container.textContent).toContain('undefinedmacro');
  });
});
