// 플랜·사용량 조회. papers.ts와 같은 결 — authFetch + apiError.
import { authFetch } from './auth';
import { apiError } from './papers';
import type { PlanUsageResponse } from './types';
import { getPreviewPlan, isDevPreview } from '../dev/preview';

export async function getMyPlan(): Promise<PlanUsageResponse> {
  if (isDevPreview) return getPreviewPlan();
  const res = await authFetch('/api/me/plan');
  if (!res.ok) throw await apiError(res);
  return res.json(); // { plan, planExpiresAt, usage: { aiQuery, paperRegistration } }
}
