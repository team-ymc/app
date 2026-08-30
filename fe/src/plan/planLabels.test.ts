import { describe, it, expect } from 'vitest';
import {
  isExhausted, resetLabel, exhaustedPlaceholder, meterCaption, uploadLimitNotice, periodLabel,
} from './planLabels';
import type { UsageLimit, PlanUsageResponse } from '../api/types';

const RESET = '2026-08-31T15:00:00Z'; // KST 2026-09-01 00:00

function monthly(over: Partial<UsageLimit> = {}): UsageLimit {
  return { mode: 'MONTHLY', limit: 100, used: 37, remaining: 63, resetAt: RESET, ...over };
}
const UNLIMITED: UsageLimit = { mode: 'UNLIMITED', limit: null, used: null, remaining: null, resetAt: null };

function freePlan(paper: UsageLimit): PlanUsageResponse {
  return { plan: 'FREE', planExpiresAt: null, usage: { aiQuery: monthly(), paperRegistration: paper } };
}

describe('isExhausted', () => {
  it('MONTHLY이고 remaining 0일 때만 true', () => {
    expect(isExhausted(monthly({ remaining: 0 }))).toBe(true);
    expect(isExhausted(monthly({ remaining: 1 }))).toBe(false);
    expect(isExhausted(UNLIMITED)).toBe(false);
    expect(isExhausted(monthly({ remaining: null }))).toBe(false);
  });
});

describe('라벨 — KST 표시', () => {
  it('resetLabel: UTC 월말 자정 직전 → KST 다음 달 1일', () => {
    expect(resetLabel(RESET)).toBe('9월 1일 초기화');
  });

  it('exhaustedPlaceholder: resetAt 유무 분기', () => {
    expect(exhaustedPlaceholder(RESET)).toBe('금월 사용량 소진 · 9월 1일 초기화');
    expect(exhaustedPlaceholder(null)).toBe('금월 사용량 소진');
  });

  it('meterCaption: 잔여/소진/무제한', () => {
    expect(meterCaption('질문', monthly())).toBe('남은 질문 63회 · 9월 1일 초기화');
    expect(meterCaption('질문', monthly({ used: 100, remaining: 0 }))).toBe('모두 사용 · 9월 1일 00:00 초기화');
    expect(meterCaption('질문', UNLIMITED)).toBeNull();
  });

  it('uploadLimitNotice: 소진 시 플랜명·상한을 응답 값으로 조립, 아니면 null', () => {
    expect(uploadLimitNotice(freePlan(monthly({ limit: 3, used: 3, remaining: 0 })))).toEqual({
      headline: 'Free 플랜 월 3회를 모두 소진했습니다',
      reset: ' · 9월 1일 초기화',
    });
    expect(uploadLimitNotice(freePlan(monthly({ limit: 3, used: 1, remaining: 2 })))).toBeNull();
  });

  it('periodLabel: FREE는 현재 KST 달 범위, PRO는 만료일', () => {
    const now = new Date('2026-08-15T03:00:00Z'); // KST 8월 15일
    expect(periodLabel(freePlan(monthly()), now)).toBe('8월 1일 – 31일');
    const pro: PlanUsageResponse = {
      plan: 'PRO', planExpiresAt: '2026-09-26T02:00:00Z',
      usage: { aiQuery: monthly({ limit: 1000, remaining: 963 }), paperRegistration: monthly({ limit: 100 }) },
    };
    expect(periodLabel(pro, now)).toBe('Pro · 9월 26일까지');
  });

  it('periodLabel: KST 자정 경계 — UTC 저녁은 KST 다음 날', () => {
    const now = new Date('2026-08-31T15:30:00Z'); // KST 9월 1일 00:30
    expect(periodLabel(freePlan(monthly()), now)).toBe('9월 1일 – 30일');
  });
});
