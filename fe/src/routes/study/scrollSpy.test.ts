import { expect, test } from 'vitest';
import { pickActiveHeading } from './scrollSpy';

const ORDER = ['block-1', 'block-3', 'block-6'];

test('보이는 heading 중 문서 순서상 첫 번째를 고른다', () => {
  expect(pickActiveHeading(['block-6', 'block-3'], ORDER)).toBe('block-3');
});
test('보이는 heading이 없으면 null', () => {
  expect(pickActiveHeading([], ORDER)).toBeNull();
});
test('toc에 없는 id는 무시한다', () => {
  expect(pickActiveHeading(['block-99'], ORDER)).toBeNull();
});
