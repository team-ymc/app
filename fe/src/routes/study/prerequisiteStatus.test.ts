import { describe, expect, it } from 'vitest';
import { prerequisiteDisabledReason } from './prerequisiteStatus';

describe('prerequisiteDisabledReason', () => {
  it('하이라이트가 없으면 안내 문구', () => {
    expect(prerequisiteDisabledReason(0)).toBe('표시할 선행지식이 없습니다');
  });
  it('있으면 undefined', () => {
    expect(prerequisiteDisabledReason(3)).toBeUndefined();
  });
});
