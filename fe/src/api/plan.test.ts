import { describe, it, expect, vi, afterEach } from 'vitest';
import { getMyPlan } from './plan';
import { ApiError } from './types';

function mockFetch({ ok = true, status = 200, body = {} }: { ok?: boolean; status?: number; body?: unknown }) {
  globalThis.fetch = vi.fn().mockResolvedValue({ ok, status, json: async () => body }) as unknown as typeof fetch;
}

const PLAN_BODY = {
  plan: 'FREE',
  planExpiresAt: null,
  usage: {
    aiQuery: { mode: 'MONTHLY', limit: 100, used: 37, remaining: 63, resetAt: '2026-08-31T15:00:00Z' },
    paperRegistration: { mode: 'MONTHLY', limit: 3, used: 1, remaining: 2, resetAt: '2026-08-31T15:00:00Z' },
  },
};

describe('api/plan', () => {
  afterEach(() => vi.restoreAllMocks());

  it('getMyPlan: GET /api/me/plan 응답을 그대로 반환한다', async () => {
    mockFetch({ body: PLAN_BODY });
    const res = await getMyPlan();
    expect(globalThis.fetch).toHaveBeenCalledWith('/api/me/plan', expect.objectContaining({}));
    expect(res.plan).toBe('FREE');
    expect(res.usage.aiQuery.remaining).toBe(63);
  });

  it('실패 응답은 code를 담은 ApiError로 던진다', async () => {
    mockFetch({ ok: false, status: 401, body: { code: 'UNAUTHORIZED', message: '인증 필요' } });
    await expect(getMyPlan()).rejects.toMatchObject({ code: 'UNAUTHORIZED', httpStatus: 401 });
    await expect(mockFetchReject()).rejects.toBeInstanceOf(ApiError);
  });
});

function mockFetchReject() {
  mockFetch({ ok: false, status: 401, body: { code: 'UNAUTHORIZED', message: '인증 필요' } });
  return getMyPlan();
}
