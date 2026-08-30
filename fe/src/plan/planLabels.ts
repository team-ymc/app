// 플랜·사용량 표시 문구 — 문구 원형은 design/v2 목업. 모든 시각은 KST로 표시한다.
import type { PlanUsageResponse, UsageLimit } from '../api/types';

const KST = 'Asia/Seoul';
const MD = new Intl.DateTimeFormat('ko-KR', { timeZone: KST, month: 'long', day: 'numeric' });
const HM = new Intl.DateTimeFormat('ko-KR', { timeZone: KST, hourCycle: 'h23', hour: '2-digit', minute: '2-digit' });

function kstMonthDay(iso: string): string {
  return MD.format(new Date(iso)); // '9월 1일'
}

export function isExhausted(u: UsageLimit): boolean {
  return u.mode === 'MONTHLY' && u.remaining === 0;
}

export function resetLabel(resetAt: string): string {
  return `${kstMonthDay(resetAt)} 초기화`;
}

export function exhaustedPlaceholder(resetAt: string | null): string {
  return resetAt ? `금월 사용량 소진 · ${kstMonthDay(resetAt)} 초기화` : '금월 사용량 소진';
}

// UsageMeter 하단 캡션. UNLIMITED는 컴포넌트 기본 캡션('이번 달 제한 없음')에 맡긴다.
export function meterCaption(unitLabel: string, u: UsageLimit): string | null {
  if (u.mode !== 'MONTHLY' || u.resetAt == null) return null;
  if (isExhausted(u)) return `모두 사용 · ${kstMonthDay(u.resetAt)} ${HM.format(new Date(u.resetAt))} 초기화`;
  return `남은 ${unitLabel} ${u.remaining}회 · ${kstMonthDay(u.resetAt)} 초기화`;
}

function planName(plan: PlanUsageResponse['plan']): string {
  return plan === 'PRO' ? 'Pro' : 'Free';
}

export function uploadLimitNotice(data: PlanUsageResponse): { headline: string; reset: string } | null {
  const u = data.usage.paperRegistration;
  if (!isExhausted(u)) return null;
  return {
    headline: `${planName(data.plan)} 플랜 월 ${u.limit}회를 모두 소진했습니다`,
    reset: u.resetAt ? ` · ${kstMonthDay(u.resetAt)} 초기화` : '',
  };
}

export function periodLabel(data: PlanUsageResponse, now: Date): string {
  if (data.plan === 'PRO' && data.planExpiresAt) return `Pro · ${kstMonthDay(data.planExpiresAt)}까지`;
  // 현재 KST 달의 1일–말일. en-CA는 YYYY-MM-DD 고정 포맷이라 파싱용으로 쓴다.
  const [y, m] = new Intl.DateTimeFormat('en-CA', { timeZone: KST }).format(now).split('-').map(Number);
  const lastDay = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return `${m}월 1일 – ${lastDay}일`;
}
