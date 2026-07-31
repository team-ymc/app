import { expect, test } from 'vitest';
import { computeToolbarPosition } from './selectionPosition';

const container = { top: 100, left: 200, right: 800, bottom: 900, width: 600, height: 800 } as DOMRect;
const popup = { width: 180, height: 40 };

test('선택 영역 아래 중앙에 배치한다 (컨테이너 상대 좌표)', () => {
  const sel = { top: 300, left: 400, right: 500, bottom: 320, width: 100, height: 20 } as DOMRect;
  expect(computeToolbarPosition(sel, container, popup)).toEqual({ top: 228, left: 160 });
  // top: sel.bottom - container.top + 8 = 228, left: sel 중앙(450) - container.left - popup/2 = 160
});

test('오른쪽 경계를 넘으면 안쪽으로 clamp', () => {
  const sel = { top: 300, left: 760, right: 795, bottom: 320, width: 35, height: 20 } as DOMRect;
  const pos = computeToolbarPosition(sel, container, popup);
  expect(pos.left).toBe(600 - 180 - 8); // container.width - popup.width - 여백 8
});

test('왼쪽 경계도 clamp', () => {
  const sel = { top: 300, left: 205, right: 215, bottom: 320, width: 10, height: 20 } as DOMRect;
  expect(computeToolbarPosition(sel, container, popup).left).toBe(8);
});
