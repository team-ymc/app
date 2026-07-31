import { describe, expect, test } from 'vitest';
import { parsePaperMarkdown } from './paperContent';

const SRC = [
  '# 제목',
  '',
  '## 1. Introduction',
  '',
  '첫 문단이다. 인라인 수식 $x^2$ 포함.',
  '',
  '### 1.1 하위 절',
  '',
  '$$',
  'E = mc^2',
  '$$',
  '',
  '![그림 1: 캡션 텍스트](/fixtures/figure-attention.svg)',
  '',
  '| a | b |',
  '| - | - |',
  '| 1 | 2 |',
].join('\n');

describe('parsePaperMarkdown', () => {
  const { blocks, toc } = parsePaperMarkdown(SRC);

  test('최상위 노드가 순서대로 블록이 된다', () => {
    expect(blocks.map((b) => b.type)).toEqual([
      'heading', 'heading', 'para', 'subheading', 'equation', 'figure', 'table',
    ]);
    expect(blocks.map((b) => b.id)).toEqual(blocks.map((_, i) => `block-${i}`));
  });

  test('블록 markdown은 원문 slice다', () => {
    expect(blocks[2].markdown).toBe('첫 문단이다. 인라인 수식 $x^2$ 포함.');
    expect(blocks[4].markdown).toBe('$$\nE = mc^2\n$$');
  });

  test('h1·h2는 heading, h3+는 subheading, heading 텍스트를 추출한다', () => {
    expect(blocks[1]).toMatchObject({ type: 'heading', headingText: '1. Introduction', headingLevel: 2 });
    expect(blocks[3]).toMatchObject({ type: 'subheading', headingText: '1.1 하위 절', headingLevel: 3 });
  });

  test('이미지 단독 문단은 figure다', () => {
    expect(blocks[5].type).toBe('figure');
  });

  test('toc는 h2·h3만 담는다 (h1 = 논문 제목)', () => {
    expect(toc).toEqual([
      { blockId: 'block-1', text: '1. Introduction', level: 2 },
      { blockId: 'block-3', text: '1.1 하위 절', level: 3 },
    ]);
  });
});
