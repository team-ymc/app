import { expect, test } from 'vitest';
import { filterPapers, paginate } from './paperFilters';
import type { Paper } from '../../api/types';

const p = (filename: string): Paper =>
  ({ paperId: filename, filename, status: 'COMPLETED', createdAt: '', updatedAt: '' });

test('filterPapers는 파일명 부분일치·대소문자 무시', () => {
  const papers = [p('Attention.pdf'), p('BERT.pdf')];
  expect(filterPapers(papers, 'atten')).toEqual([papers[0]]);
  expect(filterPapers(papers, '')).toEqual(papers);
});

test('paginate는 페이지를 자르고 전체 페이지 수를 준다', () => {
  const items = Array.from({ length: 23 }, (_, i) => i);
  expect(paginate(items, 1, 10).items).toHaveLength(10);
  expect(paginate(items, 3, 10)).toEqual({ items: [20, 21, 22], totalPages: 3 });
  expect(paginate(items, 99, 10).items).toEqual([20, 21, 22]); // 범위 밖은 마지막 페이지로 clamp
});
