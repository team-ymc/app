// 플랜·사용량 조회 (FT-011 Story 3). papers.ts와 같은 결 — authFetch + apiError.
import { authFetch } from './auth';
import { apiError } from './papers';
import type { PlanUsageResponse } from './types';

export async function getMyPlan(): Promise<PlanUsageResponse> {
  const res = await authFetch('/api/me/plan');
  if (!res.ok) throw await apiError(res);
  return res.json(); // { plan, planExpiresAt, usage: { aiQuery, paperRegistration } }
}
