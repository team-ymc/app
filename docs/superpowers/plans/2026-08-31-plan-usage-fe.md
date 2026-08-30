# 플랜·사용량 FE (YMC-348) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** v2 디자인의 플랜·사용량 부분(배지·사용량 패널·채팅 잠금·업로드 잠금)을 FE에 적용한다.

**Architecture:** `GET /api/me/plan`을 react-query `['plan']` 단일 소스로 두고, 서재·학습 페이지가 `isExhausted` 판정으로 잠금 상태를 파생한다. 잠금용 로컬 상태는 두지 않는다 — 사용량이 변하는 시점(채팅 completed, 업로드 성공, 429)에 invalidate만 한다. UI는 목업 `_ds_bundle.js`의 Badge·UsageMeter를 기존 Button/IconButton과 같은 방식으로 전사한다.

**Tech Stack:** React 19, @tanstack/react-query 5, @phosphor-icons/react, vitest + testing-library (환경 `jsdom`, `vite.config.js`).

**Spec:** `docs/superpowers/specs/2026-08-31-plan-usage-fe-design.md`

## Global Constraints

- 브랜치 `YMC-348-plan-usage-fe`, 커밋 메시지는 `[YMC-348] type(scope): subject` (한국어 subject, 기존 로그 결). Co-Authored-By·Generated with 등 어떤 attribution도 넣지 않는다.
- 커밋 전 반드시 `npm test`(fe/)와 `npx tsc --noEmit` 통과 확인.
- 계약 필드명은 `project-docs/contracts/frontend-backend/openapi.yaml`의 `PlanUsageResponse` 그대로 — 임의 개명 금지.
- 시각 표시는 전부 KST (`Asia/Seoul`). 문구는 목업(design/v2)과 spec §2의 라벨 정의를 벗어나지 않는다.
- 플랜명·한도 숫자를 하드코딩하지 않는다 — 항상 응답 값으로 렌더.
- 한도 소진 판정은 `isExhausted` 하나만 쓴다: `mode === 'MONTHLY' && remaining === 0`. UNLIMITED·null 필드는 소진 아님.
- 스타일은 기존 페이지 결(인라인 style + CSS 변수)을 따른다. 새 CSS 파일을 만들지 않는다.

---

### Task 1: 계약 타입 + `getMyPlan` API

**Files:**
- Modify: `fe/src/api/types.ts` (끝에 추가)
- Create: `fe/src/api/plan.ts`
- Test: `fe/src/api/plan.test.ts`

**Interfaces:**
- Produces: `UsageMode`, `UsageLimit`, `PlanUsageResponse` 타입, `getMyPlan(): Promise<PlanUsageResponse>`. 이후 모든 태스크가 이 타입을 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성** — `fe/src/api/plan.test.ts`

`papers.test.ts`와 같은 결(모듈 상단 mockFetch 헬퍼 복제):

```ts
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd fe && npx vitest run src/api/plan.test.ts`
Expected: FAIL — `Cannot find module './plan'` 또는 유사 (기능 부재).

- [ ] **Step 3: 타입 추가 + 최소 구현**

`fe/src/api/types.ts` 끝에 (파일 상단 주석이 말하는 openapi.yaml 출처 그대로):

```ts
// GET /api/me/plan (FT-011 Story 3). UNLIMITED면 limit·used·remaining·resetAt 모두 null.
export type UsageMode = 'MONTHLY' | 'UNLIMITED';

export interface UsageLimit {
  mode: UsageMode;
  limit: number | null;
  used: number | null;      // 확정 + 진행 중 예약
  remaining: number | null;
  resetAt: string | null;   // 다음 KST 월간 버킷 시작
}

export interface PlanUsageResponse {
  plan: 'FREE' | 'PRO';
  planExpiresAt: string | null; // PRO만, FREE면 null
  usage: { aiQuery: UsageLimit; paperRegistration: UsageLimit };
}
```

`fe/src/api/plan.ts`:

```ts
// 플랜·사용량 조회 (FT-011 Story 3). papers.ts와 같은 결 — authFetch + apiError.
import { authFetch } from './auth';
import { apiError } from './papers';
import type { PlanUsageResponse } from './types';

export async function getMyPlan(): Promise<PlanUsageResponse> {
  const res = await authFetch('/api/me/plan');
  if (!res.ok) throw await apiError(res);
  return res.json(); // { plan, planExpiresAt, usage: { aiQuery, paperRegistration } }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd fe && npx vitest run src/api/plan.test.ts` → PASS. 이어서 `npx vitest run` 전체 + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add fe/src/api/types.ts fe/src/api/plan.ts fe/src/api/plan.test.ts
git commit -m "[YMC-348] feat(fe): 플랜·사용량 조회 API 접점 추가"
```

---

### Task 2: `planLabels` — 판정·라벨 유틸

**Files:**
- Create: `fe/src/plan/planLabels.ts`
- Test: `fe/src/plan/planLabels.test.ts`

**Interfaces:**
- Consumes: Task 1의 `UsageLimit`, `PlanUsageResponse`.
- Produces (이후 태스크가 그대로 호출):
  - `isExhausted(u: UsageLimit): boolean`
  - `resetLabel(resetAt: string): string` → `"9월 1일 초기화"`
  - `exhaustedPlaceholder(resetAt: string | null): string` → `"금월 사용량 소진 · 9월 1일 초기화"` (resetAt null이면 `"금월 사용량 소진"`)
  - `meterCaption(unitLabel: string, u: UsageLimit): string | null` — UNLIMITED는 null, 소진 `"모두 사용 · 9월 1일 00:00 초기화"`, 잔여 `"남은 질문 63회 · 9월 1일 초기화"`
  - `uploadLimitNotice(data: PlanUsageResponse): { headline: string; reset: string } | null` — `"Free 플랜 월 3회를 모두 소진했습니다"` + `" · 9월 1일 초기화"`. 소진 아니면 null.
  - `periodLabel(data: PlanUsageResponse, now: Date): string` — FREE `"8월 1일 – 31일"`, PRO(planExpiresAt 있음) `"Pro · 9월 26일까지"`

- [ ] **Step 1: 실패하는 테스트 작성** — `fe/src/plan/planLabels.test.ts`

KST 경계 케이스 포함 (`2026-08-31T15:00:00Z` = KST 9월 1일 00:00):

```ts
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd fe && npx vitest run src/plan/planLabels.test.ts`
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: 최소 구현** — `fe/src/plan/planLabels.ts`

```ts
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
  if (u.remaining === 0) return `모두 사용 · ${kstMonthDay(u.resetAt)} ${HM.format(new Date(u.resetAt))} 초기화`;
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd fe && npx vitest run src/plan/planLabels.test.ts` → PASS. 전체 테스트 + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add fe/src/plan/planLabels.ts fe/src/plan/planLabels.test.ts
git commit -m "[YMC-348] feat(fe): 사용량 소진 판정과 KST 라벨 유틸"
```

---

### Task 3: Badge 컴포넌트 + brass 토큰 + `seal-check` 아이콘

**Files:**
- Create: `fe/src/design/components/Badge.tsx`
- Modify: `fe/src/design/tokens/colors.css` (토큰 2개 추가)
- Modify: `fe/src/design/components/icons.ts` (`seal-check` 등록)
- Test: `fe/src/design/components/Badge.test.tsx`

**Interfaces:**
- Produces: `Badge` — props `{ tone?: BadgeTone; icon?: string; children; style? }`, `BadgeTone = 'neutral' | 'success' | 'danger' | 'pro' | 'neutralOnDark' | 'proOnDark'`. 아이콘은 `iconComponent(kebab)`로 해석.

- [ ] **Step 1: 실패하는 테스트 작성** — `fe/src/design/components/Badge.test.tsx`

```tsx
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { Badge } from './Badge';

afterEach(cleanup);

describe('Badge', () => {
  it('children을 렌더하고 tone 색을 적용한다', () => {
    render(<Badge tone="proOnDark">Pro</Badge>);
    const el = screen.getByText('Pro');
    expect(el.style.color).toBe('var(--color-accent-brass-on-dark)');
  });

  it('icon이 있으면 아이콘을 함께 렌더한다', () => {
    const { container } = render(<Badge tone="pro" icon="seal-check">Pro</Badge>);
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('모르는 tone은 neutral로 폴백한다', () => {
    // @ts-expect-error 런타임 폴백 확인
    render(<Badge tone="nope">Free</Badge>);
    expect(screen.getByText('Free').style.color).toBe('var(--color-text-muted)');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd fe && npx vitest run src/design/components/Badge.test.tsx` → FAIL (모듈 없음).

- [ ] **Step 3: 구현**

`fe/src/design/tokens/colors.css` — 기존 `--brass:#A48248;` 뒤에 raw 토큰, `--color-accent-brass:var(--brass);` 옆에 시맨틱 토큰 추가:

```css
--brass-on-dark:#E8C98F;
--color-accent-brass-on-dark:var(--brass-on-dark);
```

`fe/src/design/components/icons.ts` — import에 `SealCheck` 추가, `ICONS`에 `'seal-check': SealCheck,` 추가.

`fe/src/design/components/Badge.tsx` — 목업 `_ds_bundle.js`의 Badge 전사 (IconButton과 같은 결):

```tsx
import type { CSSProperties, ReactNode } from 'react';
import { iconComponent } from './icons';

export type BadgeTone = 'neutral' | 'success' | 'danger' | 'pro' | 'neutralOnDark' | 'proOnDark';

interface ToneStyle { background: string; color: string; border: string }

// FT-011 플랜 배지 — Pro는 Navy Ink, 다크(월넛) 바 위에서는 *OnDark 톤을 쓴다.
const TONES: Record<BadgeTone, ToneStyle> = {
  success: { background: 'var(--color-primary-subtle)', color: 'var(--success-sage)', border: '1px solid var(--success-sage)' },
  danger: { background: 'transparent', color: 'var(--color-danger)', border: '1px solid var(--color-danger)' },
  neutral: { background: 'var(--color-bg-surface)', color: 'var(--color-text-muted)', border: '1px solid var(--color-border)' },
  pro: { background: 'var(--color-primary-subtle)', color: 'var(--color-primary)', border: '1px solid var(--color-primary)' },
  neutralOnDark: { background: 'transparent', color: 'rgba(255,253,247,0.85)', border: '1px solid rgba(255,253,247,0.35)' },
  proOnDark: { background: 'transparent', color: 'var(--color-accent-brass-on-dark)', border: '1px solid var(--color-accent-brass-on-dark)' },
};

export interface BadgeProps {
  tone?: BadgeTone;
  icon?: string;
  children?: ReactNode;
  style?: CSSProperties;
}

export function Badge({ tone = 'neutral', icon, children, style }: BadgeProps) {
  const t = TONES[tone] || TONES.neutral;
  const IconCmp = icon ? iconComponent(icon) : undefined;
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '5px',
        fontFamily: 'var(--font-sans)',
        fontSize: 'var(--label-size)',
        fontWeight: 'var(--label-weight)' as CSSProperties['fontWeight'],
        letterSpacing: 'var(--label-tracking)',
        textTransform: 'uppercase',
        padding: '4px 10px',
        borderRadius: 'var(--radius-pill)',
        ...t,
        ...style,
      }}
    >
      {IconCmp ? <IconCmp size={12} weight="regular" /> : null}
      {children}
    </span>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd fe && npx vitest run src/design/components/Badge.test.tsx` → PASS. 전체 + typecheck.

- [ ] **Step 5: Commit**

```bash
git add fe/src/design/components/Badge.tsx fe/src/design/components/Badge.test.tsx fe/src/design/tokens/colors.css fe/src/design/components/icons.ts
git commit -m "[YMC-348] feat(fe): Badge 컴포넌트와 플랜 톤·brass 토큰 이식"
```

---

### Task 4: UsageMeter 컴포넌트

**Files:**
- Create: `fe/src/design/components/UsageMeter.tsx`
- Test: `fe/src/design/components/UsageMeter.test.tsx`

**Interfaces:**
- Consumes: 없음 (독립 표시 컴포넌트 — `UsageLimit`을 직접 받지 않고 원시 props를 받는다, 목업 시그니처 유지).
- Produces: `UsageMeter` — props `{ label: string; used?: number; limit?: number | null; unlimited?: boolean; resetLabel?: string | null; compact?: boolean; style? }`.

- [ ] **Step 1: 실패하는 테스트 작성** — `fe/src/design/components/UsageMeter.test.tsx`

```tsx
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { UsageMeter } from './UsageMeter';

afterEach(cleanup);

function bar(container: HTMLElement): HTMLElement {
  // 트랙(고정 높이 배경) 안의 채움 div
  return container.querySelector('[data-part="fill"]') as HTMLElement;
}

describe('UsageMeter', () => {
  it('MONTHLY: used / limit와 캡션을 표시하고 바 너비는 사용률', () => {
    const { container } = render(<UsageMeter label="AI 질의" used={37} limit={100} resetLabel="남은 질문 63회 · 9월 1일 초기화" />);
    expect(screen.getByText('37 / 100')).toBeTruthy();
    expect(screen.getByText('남은 질문 63회 · 9월 1일 초기화')).toBeTruthy();
    expect(bar(container).style.width).toBe('37%');
    expect(bar(container).style.background).toBe('var(--color-primary)');
  });

  it('소진(remaining 0)이면 바가 danger 색', () => {
    const { container } = render(<UsageMeter label="문서 등록" used={3} limit={3} />);
    expect(bar(container).style.background).toBe('var(--color-danger)');
  });

  it('UNLIMITED: 무제한 표시, 횟수 미노출, 기본 캡션', () => {
    const { container } = render(<UsageMeter label="AI 질의" unlimited />);
    expect(screen.getByText('무제한')).toBeTruthy();
    expect(screen.queryByText(/\//)).toBeNull();
    expect(screen.getByText('이번 달 제한 없음')).toBeTruthy();
    expect(bar(container).style.width).toBe('100%');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd fe && npx vitest run src/design/components/UsageMeter.test.tsx` → FAIL (모듈 없음).

- [ ] **Step 3: 구현** — `fe/src/design/components/UsageMeter.tsx` (목업 `_ds_bundle.js` 전사, 채움 div에 `data-part="fill"`만 추가)

```tsx
import type { CSSProperties } from 'react';

// FT-011 플랜·사용량 미터. MONTHLY: used/limit + 바, UNLIMITED: '무제한' + 사선 무늬 바(횟수 미노출).
const UNLIMITED_FILL =
  'repeating-linear-gradient(135deg, var(--color-primary-selection) 0 4px, var(--color-primary-subtle) 4px 8px)';

export interface UsageMeterProps {
  label: string;
  used?: number;
  limit?: number | null;
  unlimited?: boolean;
  resetLabel?: string | null;
  compact?: boolean;
  style?: CSSProperties;
}

export function UsageMeter({ label, used = 0, limit = null, unlimited = false, resetLabel, compact = false, style }: UsageMeterProps) {
  const lim = Number(limit);
  const finite = !unlimited && Number.isFinite(lim) && lim > 0;
  const u = finite ? Math.max(0, Math.min(lim, Number(used) || 0)) : 0;
  const remaining = finite ? lim - u : Infinity;
  const pct = unlimited ? 100 : finite ? Math.round((u / lim) * 100) : 0;
  const fill = unlimited ? UNLIMITED_FILL : remaining === 0 ? 'var(--color-danger)' : 'var(--color-primary)';
  const valueText = unlimited ? '무제한' : finite ? `${u} / ${lim}` : '';
  const caption = resetLabel ?? (unlimited ? '이번 달 제한 없음' : null);
  const fs = compact ? '12px' : 'var(--caption-size)';
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', fontFamily: 'var(--font-sans)', ...style }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '12px' }}>
        <span style={{ fontSize: fs, color: 'var(--color-text-muted)' }}>{label}</span>
        <span style={{ fontSize: fs, fontWeight: 600, color: 'var(--color-text-heading)', fontVariantNumeric: 'tabular-nums' }}>
          {valueText}
        </span>
      </div>
      <div style={{ height: compact ? '4px' : '6px', borderRadius: 'var(--radius-pill)', background: 'var(--color-border)', overflow: 'hidden' }}>
        <div
          data-part="fill"
          style={{ height: '100%', width: `${pct}%`, background: fill, borderRadius: 'var(--radius-pill)', transition: 'width 200ms ease' }}
        />
      </div>
      {caption && !compact ? <div style={{ fontSize: '12px', color: 'var(--color-text-muted)' }}>{caption}</div> : null}
    </div>
  );
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd fe && npx vitest run src/design/components/UsageMeter.test.tsx` → PASS. 전체 + typecheck.

- [ ] **Step 5: Commit**

```bash
git add fe/src/design/components/UsageMeter.tsx fe/src/design/components/UsageMeter.test.tsx
git commit -m "[YMC-348] feat(fe): UsageMeter 컴포넌트 이식"
```

---

### Task 5: `usePlanQuery` + 서재 배지·사용량 패널

**Files:**
- Create: `fe/src/plan/usePlanQuery.ts`
- Modify: `fe/src/routes/BookshelfPage.tsx`

**Interfaces:**
- Consumes: `getMyPlan`(Task 1), `periodLabel`·`meterCaption`(Task 2), `Badge`(Task 3), `UsageMeter`(Task 4).
- Produces: `usePlanQuery()` — `useQuery({ queryKey: ['plan'], queryFn: getMyPlan })` 반환. **쿼리 키는 문자열 리터럴 `['plan']`** — 이후 태스크의 invalidate가 이 키를 쓴다.

usePlanQuery는 설정 한 줄 래퍼라 단독 테스트를 두지 않는다(`usePapersQuery` 전례). 이 태스크는 표시 조립이 전부라 자동 테스트 없이 Task 8의 수동 검증으로 커버한다 — 판정·라벨·컴포넌트 로직은 Task 1–4에서 이미 테스트됨.

- [ ] **Step 1: `usePlanQuery` 작성** — `fe/src/plan/usePlanQuery.ts`

```ts
import { useQuery } from '@tanstack/react-query';
import { getMyPlan } from '../api/plan';

// 폴링 없음 — 사용량이 변하는 시점(채팅 completed·업로드 성공·429)에 invalidateQueries(['plan'])로 갱신한다.
export function usePlanQuery() {
  return useQuery({ queryKey: ['plan'], queryFn: getMyPlan });
}
```

- [ ] **Step 2: BookshelfPage — 배지**

import 추가: `import { Badge } from '../design/components/Badge';`, `import { UsageMeter } from '../design/components/UsageMeter';`, `import { usePlanQuery } from '../plan/usePlanQuery';`, `import { periodLabel, meterCaption } from '../plan/planLabels';`, phosphor import에 `CaretLeft` 추가.

컴포넌트 상단에:

```tsx
const planQuery = usePlanQuery();
const plan = planQuery.data;
const [usageOpen, setUsageOpen] = useState(false);
```

상단 바(`Custom top bar overlay`)의 `<div style={{ display:'flex', alignItems:'center', gap:'8px' }}>` 안, `<ProfileButton …/>` 앞에 (gap은 목업대로 `14px`로 변경):

```tsx
{plan && (
  <Badge
    tone={plan.plan === 'PRO' ? 'proOnDark' : 'neutralOnDark'}
    icon={plan.plan === 'PRO' ? 'seal-check' : undefined}
  >
    {plan.plan === 'PRO' ? 'Pro' : 'Free'}
  </Badge>
)}
```

로딩·실패 중에는 배지를 렌더하지 않는다 (spec §4).

- [ ] **Step 3: BookshelfPage — 프로필 메뉴에 `< 사용량` + 패널**

프로필 메뉴 토글 시 패널 초기화: `onClick={() => setProfileMenuOpen((o) => !o)}` → `onClick={() => { setProfileMenuOpen((o) => !o); setUsageOpen(false); }}`.

메뉴(`profileMenuRef` div, 목업대로 `width: '200px'`로 변경) 안에서 `<DropdownButton>프로필</DropdownButton>`과 `<DropdownButton>설정</DropdownButton>` 사이에:

```tsx
<button
  onClick={() => setUsageOpen((v) => !v)}
  style={{
    width: '100%',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: '10px',
    padding: '9px 10px',
    border: 'none',
    borderRadius: '6px',
    background: usageOpen ? 'var(--color-primary-subtle)' : 'transparent',
    fontFamily: 'var(--font-sans)',
    fontSize: '13px',
    color: 'var(--color-text-body)',
    cursor: 'pointer',
    whiteSpace: 'nowrap',
  }}
>
  <CaretLeft size={13} color="var(--color-text-muted)" />
  <span>사용량</span>
</button>
```

메뉴 div의 마지막 자식으로 (메뉴 DOM 내부라 기존 바깥클릭 닫기가 그대로 덮는다):

```tsx
{usageOpen && plan && (
  <div
    style={{
      position: 'absolute',
      top: 0,
      right: 'calc(100% + 8px)',
      width: '260px',
      background: 'var(--color-bg-paper)',
      border: '1px solid var(--color-border)',
      borderRadius: '8px',
      boxShadow: 'var(--shadow-menu)',
      padding: '16px',
      boxSizing: 'border-box',
      display: 'flex',
      flexDirection: 'column',
      gap: '14px',
      zIndex: 55,
    }}
  >
    <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: '10px' }}>
      <span style={{ fontFamily: 'var(--font-serif)', fontSize: '16px', fontWeight: 600, color: 'var(--color-text-heading)', whiteSpace: 'nowrap' }}>
        이번 달 사용량
      </span>
      <span style={{ fontFamily: 'var(--font-sans)', fontSize: '12px', color: 'var(--color-text-muted)', whiteSpace: 'nowrap' }}>
        {periodLabel(plan, new Date())}
      </span>
    </div>
    <UsageMeter
      label="AI 질의"
      used={plan.usage.aiQuery.used ?? 0}
      limit={plan.usage.aiQuery.limit}
      unlimited={plan.usage.aiQuery.mode === 'UNLIMITED'}
      resetLabel={meterCaption('질문', plan.usage.aiQuery)}
    />
    <UsageMeter
      label="문서 등록"
      used={plan.usage.paperRegistration.used ?? 0}
      limit={plan.usage.paperRegistration.limit}
      unlimited={plan.usage.paperRegistration.mode === 'UNLIMITED'}
      resetLabel={meterCaption('등록', plan.usage.paperRegistration)}
    />
  </div>
)}
```

주의: 메뉴 div에 `position: 'fixed'`가 이미 있으므로 패널의 `position: 'absolute'` 기준이 된다 — 추가 wrapper 불필요.

- [ ] **Step 4: 확인**

Run: `cd fe && npx vitest run && npx tsc --noEmit` → 전부 PASS (기존 테스트 회귀 없음).

- [ ] **Step 5: Commit**

```bash
git add fe/src/plan/usePlanQuery.ts fe/src/routes/BookshelfPage.tsx
git commit -m "[YMC-348] feat(fe): 서재 상단 플랜 배지와 사용량 패널"
```

---

### Task 6: 업로드 한도 소진 — 안내 박스·버튼 비활성·429 갱신

**Files:**
- Modify: `fe/src/routes/BookshelfPage.tsx` (R2 헤더)
- Modify: `fe/src/routes/bookshelf/UploadDialog.tsx` (catch 경로)
- Test: `fe/src/routes/bookshelf/UploadDialog.test.tsx` (테스트 추가)

**Interfaces:**
- Consumes: `isExhausted`·`uploadLimitNotice`(Task 2), `usePlanQuery`(Task 5), `ApiError`(기존).

- [ ] **Step 1: 실패하는 테스트 작성** — `UploadDialog.test.tsx`에 describe 추가 (기존 mock·헬퍼 재사용)

```tsx
import { ApiError } from '../../api/types';
// 기존 renderDialog 대신 qc를 밖에서 잡는 변형이 필요 — 파일의 기존 헬퍼 옆에 추가:
function renderDialogWith(qc: QueryClient) {
  return render(
    <QueryClientProvider client={qc}>
      <UploadDialog open onClose={() => {}} onUploaded={() => {}} />
    </QueryClientProvider>,
  );
}

describe('UploadDialog — 사용량 한도', () => {
  beforeEach(() => vi.clearAllMocks());

  it('429 PAPER_USAGE_LIMIT_EXCEEDED면 plan 쿼리를 invalidate하고 에러를 노출한다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockRejectedValue(new ApiError('한도 초과', 'PAPER_USAGE_LIMIT_EXCEEDED', 429));
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { container } = renderDialogWith(qc);

    selectPdfAndUpload(container);

    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['plan'] }));
    await waitFor(() => expect(screen.getByText(/PAPER_USAGE_LIMIT_EXCEEDED/)).toBeTruthy());
  });

  it('한도와 무관한 실패는 plan을 invalidate하지 않는다', async () => {
    vi.mocked(sha256Base64).mockResolvedValue(HASH);
    vi.mocked(createPaper).mockRejectedValue(new ApiError('중복 파일명', 'DUPLICATE_FILENAME', 409));
    const qc = new QueryClient();
    const invalidateSpy = vi.spyOn(qc, 'invalidateQueries');
    const { container } = renderDialogWith(qc);

    selectPdfAndUpload(container);

    await waitFor(() => expect(screen.getByText(/DUPLICATE_FILENAME/)).toBeTruthy());
    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ['plan'] });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd fe && npx vitest run src/routes/bookshelf/UploadDialog.test.tsx`
Expected: 새 테스트 1번이 FAIL (invalidate 미호출) — 2번은 통과할 수 있다(현행도 invalidate 안 함). 1번의 실패 사유가 invalidate 부재인지 확인.

- [ ] **Step 3: 구현**

`UploadDialog.tsx`의 catch (기존 109–121행):

```ts
    } catch (e) {
      // 한도 초과(FT-011)면 plan을 재조회 — 서재가 안내 박스·버튼 비활성으로 전환된다.
      if (e instanceof ApiError && e.code === 'PAPER_USAGE_LIMIT_EXCEEDED') {
        queryClient.invalidateQueries({ queryKey: ['plan'] });
      }
      setError(e); // 숨기지 않는다 — 다이얼로그 안에 그대로 노출
      setPhase('file-selected'); // 재시도 가능하도록 복귀
    }
```

(`ApiError`는 이 파일에 이미 import되어 있다.)

`BookshelfPage.tsx` — import에 `WarningCircle` 추가(phosphor), `isExhausted, uploadLimitNotice` 추가(planLabels). 컴포넌트 본문에:

```tsx
const uploadLocked = plan ? isExhausted(plan.usage.paperRegistration) : false;
const lockNotice = plan ? uploadLimitNotice(plan) : null;
```

R2 헤더의 업로드 버튼을 목업대로 감싼다 (기존 `<Button variant="secondary" icon="plus" …>` 교체):

```tsx
<div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
  {uploadLocked && lockNotice && (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '8px',
        height: '36px',
        padding: '0 12px',
        boxSizing: 'border-box',
        background: 'var(--color-bg-surface)',
        border: '1px solid var(--color-border)',
        borderRadius: 'var(--radius-control)',
        fontFamily: 'var(--font-sans)',
        fontSize: '13px',
        color: 'var(--color-text-body)',
        whiteSpace: 'nowrap',
      }}
    >
      <WarningCircle size={15} color="var(--color-danger)" />
      <span>
        <span style={{ fontWeight: 600 }}>{lockNotice.headline}</span>
        <span style={{ color: 'var(--color-text-muted)' }}>{lockNotice.reset}</span>
      </span>
    </div>
  )}
  <Button variant="secondary" icon="plus" onClick={() => setUploadOpen(true)} disabled={uploadLocked} style={{ flexShrink: 0 }}>
    <span style={{ whiteSpace: 'nowrap', fontWeight: 600 }}>업로드</span>
  </Button>
</div>
```

업로드 성공 갱신 — `onUploaded` 콜백에 plan invalidate 추가. BookshelfPage에 `useQueryClient`를 import해 (`@tanstack/react-query`):

```tsx
const queryClient = useQueryClient();
// …
onUploaded={() => {
  queryClient.invalidateQueries({ queryKey: ['plan'] });
  showToast('등록되었습니다 — 분석이 시작됩니다');
}}
```

- [ ] **Step 4: 통과 확인**

Run: `cd fe && npx vitest run` → 전부 PASS. `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add fe/src/routes/BookshelfPage.tsx fe/src/routes/bookshelf/UploadDialog.tsx fe/src/routes/bookshelf/UploadDialog.test.tsx
git commit -m "[YMC-348] feat(fe): 문서 등록 한도 소진 안내와 업로드 잠금"
```

---

### Task 7: 채팅 한도 소진 — 입력창 잠금·429 갱신·재시도 미노출

**Files:**
- Modify: `fe/src/routes/study/TutorPanel.tsx`
- Modify: `fe/src/routes/StudyPage.tsx`
- Test: `fe/src/routes/study/TutorPanel.lock.test.tsx` (신규 파일)

**Interfaces:**
- Consumes: `usePlanQuery`(Task 5), `isExhausted`·`exhaustedPlaceholder`(Task 2).
- Produces: `TutorPanelProps`에 `queryLocked?: boolean`, `lockPlaceholder?: string` 추가. StudyPage가 계산해 내려준다 — TutorPanel은 plan을 직접 조회하지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성** — `fe/src/routes/study/TutorPanel.lock.test.tsx`

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TutorPanel } from './TutorPanel';
import { streamChatMessage } from '../../chat/chatStream';

vi.mock('../../chat/chatStream', () => ({ streamChatMessage: vi.fn() }));
vi.mock('../../api/chatSessions', () => ({
  listChatSessions: vi.fn(), listChatSessionMessages: vi.fn(), deleteChatSession: vi.fn(),
}));

function renderPanel(locked: boolean) {
  const qc = new QueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <TutorPanel
        paperId="p1"
        blocks={[]}
        pendingContext={null}
        onContextConsumed={() => {}}
        collapsed={false}
        onToggleCollapse={() => {}}
        queryLocked={locked}
        lockPlaceholder="금월 사용량 소진 · 9월 1일 초기화"
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

describe('TutorPanel — AI 질의 한도 잠금', () => {
  it('잠금이면 입력창 disabled + 소진 placeholder + 보내기 비활성', () => {
    renderPanel(true);
    const textarea = screen.getByPlaceholderText('금월 사용량 소진 · 9월 1일 초기화') as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(true);
    expect((screen.getByRole('button', { name: '질문 보내기' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('잠금이면 Enter로도 전송되지 않는다', () => {
    renderPanel(true);
    const textarea = screen.getByPlaceholderText('금월 사용량 소진 · 9월 1일 초기화');
    fireEvent.keyDown(textarea, { key: 'Enter' });
    expect(streamChatMessage).not.toHaveBeenCalled();
  });

  it('잠금이 아니면 기존 placeholder와 활성 상태', () => {
    renderPanel(false);
    const textarea = screen.getByPlaceholderText('AI에게 질문해보세요') as HTMLTextAreaElement;
    expect(textarea.disabled).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd fe && npx vitest run src/routes/study/TutorPanel.lock.test.tsx`
Expected: FAIL — `queryLocked` prop 없음 / placeholder 불일치.

- [ ] **Step 3: TutorPanel 구현**

1. `TutorPanelProps`에 추가 (interface 정의부):

```ts
  /** AI 질의 한도 소진(FT-011) — StudyPage가 plan 조회로 판정해 내려준다. */
  queryLocked?: boolean;
  lockPlaceholder?: string;
```

함수 시그니처 구조분해에 `queryLocked = false, lockPlaceholder` 추가.

2. composer의 `<textarea>`: `disabled={queryLocked}`, `placeholder={queryLocked ? (lockPlaceholder ?? '금월 사용량 소진') : 'AI에게 질문해보세요'}`, 잠금 시 흐린 글씨·커서 — style의 `color`를 `queryLocked ? 'var(--color-text-muted)' : 'var(--color-text-body)'`로, wrapper div와 textarea에 `cursor: queryLocked ? 'not-allowed' : undefined`.

3. 보내기 버튼: `disabled={state.streaming || queryLocked}`.

4. `handleSend` 첫 줄 가드: `if (queryLocked) return;` (Enter 경로도 이걸로 막힌다).

5. `run()`의 `onEvent`를 교체 — completed·한도 초과 시 plan 갱신:

```ts
      onEvent: (e) => {
        // 전송 완료 시 세션 목록을 무효화 — 새 세션이 다음에 드롭다운을 열 때 반영되게 한다.
        if (e.type === 'completed') {
          queryClient.invalidateQueries({ queryKey: ['chat-sessions', paperId] });
          queryClient.invalidateQueries({ queryKey: ['plan'] }); // 사용량 확정 반영
        }
        // 한도 초과(FT-011) — plan 재조회가 입력창을 잠근다
        if (e.type === 'failed' && e.code === 'CHAT_USAGE_LIMIT_EXCEEDED') {
          queryClient.invalidateQueries({ queryKey: ['plan'] });
        }
        dispatch(e);
      },
```

6. `canRetry`에 한도 초과 제외 추가:

```ts
  const canRetry =
    !!lastMessage &&
    lastMessage.role === 'assistant' &&
    lastMessage.status === 'FAILED' &&
    lastMessage.error?.retryable !== false &&
    lastMessage.error?.code !== 'CHAT_USAGE_LIMIT_EXCEEDED'; // 재시도해도 429 — 초기화 시각까지 불가
```

- [ ] **Step 4: StudyPage 연결**

import: `import { usePlanQuery } from '../plan/usePlanQuery';`, `import { isExhausted, exhaustedPlaceholder } from '../plan/planLabels';`

컴포넌트 본문:

```tsx
const planQuery = usePlanQuery();
const aiUsage = planQuery.data?.usage.aiQuery;
```

`<TutorPanel …>`에 props 추가:

```tsx
queryLocked={aiUsage ? isExhausted(aiUsage) : false}
lockPlaceholder={exhaustedPlaceholder(aiUsage?.resetAt ?? null)}
```

- [ ] **Step 5: 통과 확인**

Run: `cd fe && npx vitest run` → 전부 PASS (기존 TutorPanel 관련 테스트 회귀 확인). `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add fe/src/routes/study/TutorPanel.tsx fe/src/routes/study/TutorPanel.lock.test.tsx fe/src/routes/StudyPage.tsx
git commit -m "[YMC-348] feat(fe): AI 질의 한도 소진 시 입력창 잠금"
```

---

### Task 8: 수동 검증 (로컬 dev)

**Files:** 없음 (검증만)

- [ ] **Step 1: 로컬 스택 기동** — `infra/local` Docker Compose (기존 runbook). FE dev 서버는 preview 도구로 띄운다.

- [ ] **Step 2: 4개 상태 확인 + 스크린샷**

1. 서재: Free 배지 표시, 프로필 메뉴 → `< 사용량` → 패널에 미터 2개·기간·초기화 라벨.
2. 문서 등록 한도: BE env `PLAN_POLICY_FREE_PAPER_REGISTRATION_LIMIT=0` (또는 등록 3회 소진) 후 서재 — 안내 박스 + 업로드 버튼 비활성.
3. AI 질의 한도: `PLAN_POLICY_FREE_AI_QUERY_LIMIT=0` 후 학습 페이지 — 입력창 잠금 placeholder + 보내기 비활성.
4. 429 경로: 한도 1로 두고 초과 시도 — 다이얼로그 에러 노출 + 서재 잠금 전환 / 채팅 실패 후 재시도 버튼 미노출·입력창 잠금 전환.

(env 키 형식은 BE `application.yml`의 relaxed binding 기준 — 안 먹으면 `plan.policy.free.*`를 application-local.yml로 넘긴다.)

- [ ] **Step 3: 결과 정리** — 스크린샷과 함께 핵심 diff 요약을 사용자에게 보여주고, 이후(푸시·PR)는 별도 승인으로 진행.

---

## Self-Review 결과

- **Spec coverage**: §2 데이터 층=Task 1·2·5, invalidate 4개 시점=Task 6(업로드 성공·429)·Task 7(completed·429), §3=Task 3·4, §4=Task 5·6, §5=Task 7, §6 테스트=Task 1·2·3·4·6·7, 수동 검증=Task 8. 누락 없음.
- **Placeholder scan**: 코드 블록 전부 실제 코드. Task 8의 env 키는 실검증 전 불확실성을 명시(YMC-349 관련)했고 대체 경로를 적었다.
- **Type consistency**: `['plan']` 키, `isExhausted(u: UsageLimit)`, `queryLocked`/`lockPlaceholder`, `uploadLimitNotice` 반환 `{ headline, reset }` — 태스크 간 일치 확인.
