import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import { createRef } from 'react';
import { PaperViewer } from './PaperViewer';
import type { PaperBlock } from '../../markdown/paperContent';

const BLOCKS: PaperBlock[] = [
  {
    id: 'h1', type: 'heading', markdown: '## Introduction',
    headingText: 'Introduction', headingLevel: 2, sourceText: 'Introduction', sourceOffsetShift: 3,
  },
  {
    id: 'p1', type: 'para', markdown: 'An attention function maps a query.',
    sourceText: 'An attention function maps a query.', sourceOffsetShift: 0,
    translation: '어텐션 함수는 질의를 대응시킨다.',
  },
  {
    id: 'p2', type: 'para', markdown: 'No translation here.',
    sourceText: 'No translation here.', sourceOffsetShift: 0,
  },
  {
    id: 'c1', type: 'caption', markdown: 'Figure 1: The Transformer.',
    sourceText: 'Figure 1: The Transformer.', sourceOffsetShift: 0,
    translation: '그림 1: 트랜스포머.',
  },
];

function setup(mode: 'off' | 'below' | 'side') {
  const ref = createRef<HTMLDivElement>();
  const { container } = render(<PaperViewer blocks={BLOCKS} containerRef={ref} translationMode={mode} />);
  return container;
}

afterEach(cleanup);

describe('PaperViewer 번역 표시', () => {
  it('side에서 번역 있는 문단은 section의 형제로 aside를 둔다', () => {
    const container = setup('side');
    const section = container.querySelector<HTMLElement>('section[data-block-id="p1"]')!;
    const row = section.parentElement!;
    expect(row.className).toContain('pt-row');
    expect(row.getAttribute('data-mode')).toBe('side');

    const aside = row.querySelector('aside.pt-translation')!;
    expect(aside).not.toBeNull();
    expect(aside.parentElement).toBe(row);
    expect(section.contains(aside)).toBe(false);
    expect(aside.hasAttribute('data-block-id')).toBe(false);
    expect(aside.closest('[data-block-id]')).toBeNull();
    expect(aside.getAttribute('role')).toBe('note');
    expect(aside.textContent).toContain('어텐션 함수는');
    expect(section.textContent).not.toContain('어텐션');
  });

  it('heading과 번역 없는 문단에는 aside를 두지 않는다', () => {
    const container = setup('side');
    for (const id of ['h1', 'p2']) {
      const row = container.querySelector<HTMLElement>(`section[data-block-id="${id}"]`)!.parentElement!;
      expect(row.querySelector('aside.pt-translation')).toBeNull();
    }
    expect(container.querySelectorAll('aside.pt-translation')).toHaveLength(1);
  });

  it('off에서는 aside가 없다', () => {
    const container = setup('off');
    expect(container.querySelectorAll('aside.pt-translation')).toHaveLength(0);
    expect(container.querySelector('section[data-block-id="p1"]')!.parentElement!.getAttribute('data-mode'))
      .toBe('off');
  });

  it('below에서도 aside를 두고 행 mode를 표시한다', () => {
    const container = setup('below');
    const row = container.querySelector<HTMLElement>('section[data-block-id="p1"]')!.parentElement!;
    expect(row.getAttribute('data-mode')).toBe('below');
    const aside = row.querySelector('aside.pt-translation')!;
    expect(aside).not.toBeNull();
    expect(row.children[row.children.length - 1]).toBe(aside);
  });

  it('section의 구조와 클래스는 그대로 둔다', () => {
    const container = setup('side');
    const heading = container.querySelector<HTMLElement>('section[data-block-id="h1"]')!;
    expect(heading.id).toBe('h1');
    expect(heading.className).toContain('pt-section-start');
    expect(heading.querySelector('h2')?.textContent).toBe('Introduction');
  });

  it('캡션 번역은 별도 셀 없이 캡션 박스 안 두 번째 줄로 붙인다', () => {
    const container = setup('side');
    const row = container.querySelector('[data-block-id="c1"]')!.parentElement!;
    expect(row.classList.contains('pt-row-pair')).toBe(false);
    expect(row.querySelector('aside.pt-translation')).toBeNull();
    const line = row.querySelector('section > .pt-caption-translation')!;
    expect(line).not.toBeNull();
    expect(line.classList.contains('pt-translation')).toBe(true);
    expect(line.textContent).toContain('그림 1');
  });

  it('off에서는 캡션 번역 줄도 없다', () => {
    const container = setup('off');
    expect(container.querySelector('.pt-caption-translation')).toBeNull();
  });
});
