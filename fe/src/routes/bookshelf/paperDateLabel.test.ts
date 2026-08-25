import { describe, expect, it } from 'vitest';
import { accessDisplay } from './paperDateLabel';

describe('accessDisplay', () => {
  it('lastAccessedAt이 있으면 최근 조회 라벨과 그 시각을 쓴다', () => {
    expect(
      accessDisplay({ createdAt: '2026-01-01T00:00:00Z', lastAccessedAt: '2026-01-20T00:00:00Z' }),
    ).toEqual({ prefix: '최근 조회', iso: '2026-01-20T00:00:00Z' });
  });

  it('lastAccessedAt이 null이면 등록일 라벨과 등록 시각으로 폴백한다', () => {
    expect(accessDisplay({ createdAt: '2026-01-01T00:00:00Z', lastAccessedAt: null })).toEqual({
      prefix: '등록일',
      iso: '2026-01-01T00:00:00Z',
    });
  });
});
