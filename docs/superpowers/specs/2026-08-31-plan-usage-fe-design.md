# 플랜·사용량 FE — 구현 스펙

- 티켓: YMC-348 · 브랜치: `YMC-348-plan-usage-fe` (origin/main 분기)
- 디자인 SSOT: `project-docs/design/v2/` 플랜·사용량 아트보드 3개 + `README.md` "Screen States — 플랜·사용량" 절
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml` — `GET /api/me/plan` (`PlanUsageResponse`)
- 기능 SSOT: FT-011 Story 3 (조회), Story 2 (한도 초과 429)
- BE: main에 머지됨 (app#50, YMC-344). 베타 정책값 Free 질의 100·문서 3 / Pro 질의 1,000·문서 100 — 모두 MONTHLY.

## 1. 범위

v2 디자인 중 플랜·사용량 부분만 적용한다.

1. 서재 상단 바에 `FREE`/`PRO` 플랜 배지 상시 표시.
2. 서재 프로필 메뉴에 `< 사용량` 항목 → 왼쪽으로 펼쳐지는 사용량 패널 (AI 질의·문서 등록 미터 + 초기화 시각).
3. 학습 페이지 AI 질의 한도 소진 상태 — 입력창 잠금.
4. 서재 문서 등록 한도 소진 상태 — 안내 박스 + 업로드 버튼 비활성.

범위 밖: 학습 페이지 상단 바 배지(목업에 없음), 결제·플랜 변경 UI, design/v2 README의 Pro 표기 현행화(project-docs 후속).

## 2. 데이터 층

### 계약 타입 — `fe/src/api/types.ts`

openapi.yaml 그대로:

```ts
export type UsageMode = 'MONTHLY' | 'UNLIMITED';
export interface UsageLimit {
  mode: UsageMode;
  limit: number | null;     // UNLIMITED면 4개 필드 모두 null
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

UNLIMITED는 현재 정책값으로는 오지 않지만 계약에 있으므로 렌더링 경로를 유지한다(무제한 표시). 판정 결정: **한도 소진 = `mode === 'MONTHLY' && remaining === 0`** — `isExhausted(usage: UsageLimit)` 헬퍼로 두고 서재·학습 양쪽이 공유한다. null 필드는 소진으로 보지 않는다.

### API — `fe/src/api/plan.ts` (신규)

`getMyPlan(): Promise<PlanUsageResponse>` — `authFetch('/api/me/plan')`, 실패 시 papers.ts의 `apiError()` 재사용. 테스트는 papers.test.ts 결.

### 쿼리 — `fe/src/plan/usePlanQuery.ts` (신규)

react-query `queryKey: ['plan']`, `queryFn: getMyPlan`. 폴링 없음 — 사용량이 변하는 시점에 invalidate로 갱신한다:

| 시점 | 위치 |
|---|---|
| 채팅 스트림 `completed` | TutorPanel `onEvent` (기존 chat-sessions invalidate 옆) |
| 채팅 `failed` code `CHAT_USAGE_LIMIT_EXCEEDED` | TutorPanel `onEvent` |
| 업로드 성공 (`onUploaded`) | BookshelfPage |
| `createPaper` 429 `PAPER_USAGE_LIMIT_EXCEEDED` | UploadDialog 에러 경로 |

429 수신 → invalidate → 재조회 결과 `remaining === 0` → 잠금 상태 진입. 별도의 로컬 잠금 플래그를 두지 않고 plan 쿼리 데이터만 단일 소스로 쓴다.

### 라벨 — `fe/src/plan/planLabels.ts` (신규, 유닛테스트 대상)

모든 시각은 KST 기준 표시. 디자인 목업의 문구를 따른다:

- `resetLabel(resetAt)` → `"9월 1일 초기화"`.
- `exhaustedLabel(resetAt)` → `"금월 사용량 소진 · 9월 1일 초기화"` (채팅 placeholder).
- `uploadExhaustedText(plan, limit, resetAt)` → `"Free 플랜 월 3회를 모두 소진했습니다"` + `" · 9월 1일 초기화"`. 플랜명·한도는 응답 값으로 렌더 — 하드코딩 금지.
- `periodLabel(plan, planExpiresAt)` → FREE: 현재 달 `"8월 1일 – 31일"`, PRO: `"Pro · 9월 26일까지"`.
- 미터 하단 캡션(목업 `reset()`): 잔여 있음 `"남은 질문 N회 · 9월 1일 초기화"` / 소진 `"모두 사용 · 9월 1일 00:00 초기화"` / UNLIMITED는 캡션 없음("이번 달 제한 없음"은 컴포넌트 기본값).

## 3. 디자인 시스템 포팅

목업 `_ds_bundle.js`의 구현을 기존 Button/IconButton과 같은 방식으로 전사한다.

- `fe/src/design/components/Badge.tsx` (신규) — tone `neutral | pro | neutralOnDark | proOnDark` (+ 번들의 success/danger 톤 유지). `icon`은 icons.ts 레지스트리로 해석.
- `fe/src/design/components/UsageMeter.tsx` (신규) — props `label, used, limit, unlimited, resetLabel, compact`. MONTHLY: `used / limit` + 진행 바, remaining 0이면 `--color-danger`. UNLIMITED: `무제한` + 사선 무늬 바, 횟수 미노출.
- `fe/src/design/tokens/colors.css` — `--brass-on-dark:#E8C98F`, `--color-accent-brass-on-dark` 추가 (다크 바 위 Pro 배지).
- `fe/src/design/components/icons.ts` — `seal-check` 등록. `warning-circle`·`user`는 페이지에서 직접 import (기존 결).

## 4. 서재 (BookshelfPage)

- **배지**: 상단 바 프로필 버튼 왼쪽에 `<Badge tone={plan==='PRO' ? 'proOnDark' : 'neutralOnDark'} icon={plan==='PRO' ? 'seal-check' : undefined}>`. plan 쿼리 로딩·실패 중에는 배지를 렌더하지 않는다 (빈 자리).
- **사용량 패널**: 프로필 메뉴 항목 순서 `프로필 / < 사용량 / 설정 / 로그아웃` (목업). `< 사용량` 클릭 시 메뉴 왼쪽 `right: calc(100% + 8px)` 절대 위치 패널 토글 — 제목 `이번 달 사용량` + `periodLabel` + UsageMeter 2개. 메뉴 바깥 클릭 닫기는 기존 `handleDocMouseDown`이 패널까지 덮는다(패널이 메뉴 DOM 내부). 메뉴가 닫히면 패널도 초기화.
- **업로드 잠금**: `paperRegistration`이 소진이면 업로드 버튼 `disabled` + 왼쪽에 `warning-circle` 안내 박스(목업 그대로). plan 로딩 중엔 버튼 활성 유지 — 최종 방어는 BE 429.

## 5. 학습 (StudyPage → TutorPanel)

- StudyPage에서 `usePlanQuery`로 조회, `aiQuery` 소진 여부를 TutorPanel prop `queryLocked`로 전달 (TutorPanel이 직접 조회하지 않는다 — 페이지가 데이터 소유, 기존 결).
- `queryLocked`면: textarea `disabled` + placeholder `exhaustedLabel(resetAt)` + muted 색, 보내기 IconButton `disabled`, `handleSend`·Enter 무시. 대화 내역·히스토리 드롭다운은 그대로 동작.
- 스트림 `failed` code `CHAT_USAGE_LIMIT_EXCEEDED` → plan invalidate (재조회가 잠금 상태를 만든다). 이 실패는 재시도 버튼을 노출하지 않는다 — `retryable` 값과 무관하게 코드로 구분.

## 6. 테스트

vitest, 기존 결 그대로:

- `plan.test.ts` — GET 경로·응답 파싱·에러 코드 (papers.test.ts 패턴).
- `planLabels.test.ts` — 라벨 5종: KST 경계(월말 resetAt), FREE/PRO, 소진/잔여.
- `UsageMeter.test.tsx` — MONTHLY 표시·소진 시 danger·UNLIMITED 무제한 (testing-library).
- TutorPanel 잠금 — `queryLocked`시 전송 차단·placeholder, `CHAT_USAGE_LIMIT_EXCEEDED` 실패 시 재시도 미노출.
- 잠금 판정 — `isExhausted` 헬퍼 유닛테스트 (MONTHLY 0/잔여, UNLIMITED, null 필드). BookshelfPage 자체는 페이지 테스트를 만들지 않는다 — 판정은 헬퍼, 표시는 수동 검증으로 커버.

수동 검증: 로컬 dev로 4개 상태 확인(BE env로 한도 조절), 스크린샷 공유.

## 7. 남는 것

- 미터 표시는 Pro·Free 동일하게 횟수(`사용 / 상한`)로 제공한다. 추후 % 표시로 전환 예정 — 이번 범위 아님.
- design/v2 README의 Pro 무제한 표기 현행화 — project-docs에서 별도 진행 (이 spec과 같은 세션에서 처리).
- 설정·프로필 메뉴 항목은 여전히 자리만 있음(기존과 동일, 이번 범위 아님).
