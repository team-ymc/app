# 챗 세션 히스토리 FE (YMC-260) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계약 확정으로 풀린 챗 세션 목록·히스토리 조회·삭제를 TutorPanel에 복원한다 (FE v1 재구축 때 spec §8-2로 생략했던 픽션 조정의 해소).

**Architecture:** 기존 TutorPanel(YMC-289)에 목업의 `historyOpen` 드롭다운을 이식하고, 계약 3 operation을 `api/chatSessions.ts`로 감싼 뒤 `chatState`에 `historyLoaded` 액션 하나를 추가해 재방문 로드를 reducer로 처리한다. 세션 목록은 TanStack Query 캐시.

**Tech Stack:** 기존 스택 그대로 (react-router 7, TanStack Query 5, vitest). 신규 의존성 없음.

**Spec:** 계약 = `project-docs/contracts/frontend-backend/openapi.yaml` (698ac6e). FT-007 / Jira YMC-260 (FE AC: 세션 목록, 재방문 로드, 삭제 확인·삭제 후 상태).

## Global Constraints

- 브랜치: `YMC-260-chat-session-history-fe` (YMC-289 기반). 작업 디렉토리 `app/fe`.
- 커밋: `[YMC-260] type(fe): subject` 한 줄, 트레일러 금지. **be/ 스테이징 절대 금지** (application.yml에 YMC-276 WIP 있음).
- **계약이 SSOT다.** 구 설계 문서(`2026-07-26-ymc-260-chat-history-design.md`, 원격 브랜치)와 상충 시 계약을 따른다. 확정 계약 요지:
  - `GET …/chat/sessions` → `ChatSessionSummary[]` bare array, `lastMessageAt` 내림차순, 페이지네이션 없음. `ChatSessionSummary = { sessionId, title(≤120), lastMessageAt, createdAt }`.
  - `GET …/chat/sessions/{sessionId}/messages` → `ChatMessageItem[]` bare array, `seq` 오름차순, 페이지네이션 없음(무한스크롤 만들지 말 것). `ChatMessageItem = { messageId, role: USER|ASSISTANT, content: string|null (GENERATING·FAILED assistant는 null), status, seq, createdAt }`. `completedAt` 없음.
  - `DELETE …/chat/sessions/{sessionId}` → 204. **GENERATING 중에도 삭제 허용** (409 없음). 404 `CHAT_SESSION_NOT_FOUND`.
- 목업 SSOT: `project-docs/design/v1/Paper Study Page.dc.html`의 history 영역(헤더 좌측 clock-counter-clockwise IconButton + 220px 드롭다운, `sc-for historyList`). 스타일 값 그대로. **목업에 없는 삭제 버튼·확인 다이얼로그는 티켓 AC가 요구하는 기능이므로 토큰만 써서 최소 구성** — 픽션 조정 아님(계약·AC 근거 있음).
- 브랜드: 이모지 금지, hover는 darken/subtle-bg만, 삭제는 muted red + 문구.
- 검증: `npm run typecheck && npm test && npm run build` — 태스크마다 커밋 전 통과.

---

### Task 1: 세션 API 래퍼 + chatState historyLoaded (TDD)

**Files:**
- Create: `fe/src/api/chatSessions.ts`
- Modify: `fe/src/api/papers.ts` (private `apiError` → `export`로 승격만)
- Modify: `fe/src/chat/chatState.ts`
- Test: `fe/src/chat/chatState.test.ts` (케이스 추가)

**Interfaces:**
- Produces: `listChatSessions(paperId): Promise<ChatSessionSummary[]>` / `listChatSessionMessages(paperId, sessionId): Promise<ChatMessageItem[]>` / `deleteChatSession(paperId, sessionId): Promise<void>`, 타입 `ChatSessionSummary`·`ChatMessageItem`, chatState 액션 `{ type: 'historyLoaded'; sessionId: string; items: ChatMessageItem[] }`
- Consumes: `authFetch`(api/auth), `apiError`(api/papers, export로 승격)

- [ ] **Step 1: 실패하는 테스트 작성** — `chatState.test.ts`에 추가

```ts
import type { ChatMessageItem } from '../api/chatSessions';

const item = (over: Partial<ChatMessageItem>): ChatMessageItem => ({
  messageId: 'm-1', role: 'USER', content: '질문', status: 'COMPLETED',
  seq: 1, createdAt: '2026-08-01T00:00:00Z', ...over,
});

describe('historyLoaded', () => {
  test('서버 항목을 로컬 메시지로 매핑하고 sessionId를 세팅한다', () => {
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's-1',
      items: [
        item({ messageId: 'm-1', role: 'USER', content: '질문', seq: 1 }),
        item({ messageId: 'm-2', role: 'ASSISTANT', content: '**답변**', seq: 2 }),
      ],
    });
    expect(s.sessionId).toBe('s-1');
    expect(s.streaming).toBe(false);
    expect(s.pending).toBeNull();
    expect(s.messages).toEqual([
      { key: 'm-1', role: 'user', content: '질문', status: 'COMPLETED', error: null },
      { key: 'm-2', role: 'assistant', content: '**답변**', status: 'COMPLETED', error: null },
    ]);
  });

  test('GENERATING assistant는 content 빈 문자열 + 상태 보존', () => {
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's-1',
      items: [item({ messageId: 'm-3', role: 'ASSISTANT', content: null, status: 'GENERATING', seq: 2 })],
    });
    expect(s.messages[0]).toEqual({ key: 'm-3', role: 'assistant', content: '', status: 'GENERATING', error: null });
  });

  test('FAILED assistant는 retryable=false 에러로 매핑된다 — 과거 실패에 재시도 미노출', () => {
    const s = chatReducer(initialChatState, {
      type: 'historyLoaded', sessionId: 's-1',
      items: [item({ messageId: 'm-4', role: 'ASSISTANT', content: null, status: 'FAILED', seq: 2 })],
    });
    expect(s.messages[0].status).toBe('FAILED');
    expect(s.messages[0].error).toEqual({ code: 'HISTORY_FAILED', message: '응답 생성에 실패했습니다.', retryable: false });
  });

  test('기존 대화 상태를 통째로 대체한다', () => {
    let s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '기존 질문' });
    s = chatReducer(s, { type: 'historyLoaded', sessionId: 's-9', items: [item({})] });
    expect(s.messages).toHaveLength(1);
    expect(s.pending).toBeNull();
  });
});
```

- [ ] **Step 2: RED 확인** — `npx vitest run src/chat/chatState.test.ts` → FAIL (`chatSessions` 모듈·액션 없음)

- [ ] **Step 3: 구현**

`fe/src/api/chatSessions.ts`:

```ts
// 챗 세션 히스토리 계약 3 operation (openapi.yaml listChatSessions 등, YMC-260).
import { authFetch } from './auth';
import { apiError } from './papers';
import type { ChatMessageStatus } from '../chat/chatState';

export interface ChatSessionSummary {
  sessionId: string;
  title: string;          // 첫 user 질문 앞 120자, 불변 (계약)
  lastMessageAt: string;  // 목록 정렬 키 (내림차순, 서버 정렬 신뢰)
  createdAt: string;
}

export interface ChatMessageItem {
  messageId: string;
  role: 'USER' | 'ASSISTANT';
  content: string | null; // GENERATING·FAILED assistant는 null (계약 — partial 저장 안 함)
  status: ChatMessageStatus;
  seq: number;            // 세션 내 단조 증가, 서버가 오름차순 정렬
  createdAt: string;
}

export async function listChatSessions(paperId: string): Promise<ChatSessionSummary[]> {
  const res = await authFetch(`/api/papers/${paperId}/chat/sessions`);
  if (!res.ok) throw await apiError(res);
  return res.json();
}

export async function listChatSessionMessages(paperId: string, sessionId: string): Promise<ChatMessageItem[]> {
  const res = await authFetch(`/api/papers/${paperId}/chat/sessions/${sessionId}/messages`);
  if (!res.ok) throw await apiError(res);
  return res.json();
}

export async function deleteChatSession(paperId: string, sessionId: string): Promise<void> {
  const res = await authFetch(`/api/papers/${paperId}/chat/sessions/${sessionId}`, { method: 'DELETE' });
  if (!res.ok) throw await apiError(res); // 204 기대
}
```

`papers.ts`: `async function apiError` 앞에 `export` 추가 (본문 무변경).

`chatState.ts`: 액션 union에 `| { type: 'historyLoaded'; sessionId: string; items: ChatMessageItem[] }` 추가 (`import type { ChatMessageItem } from '../api/chatSessions';`), reducer에:

```ts
case 'historyLoaded':
  // 재방문 로드 — 서버 항목으로 대화를 통째로 대체한다. 진행 중 스트림은 호출부가 먼저 abort.
  return {
    sessionId: action.sessionId,
    streaming: false,
    pending: null,
    messages: action.items.map((it) => ({
      key: it.messageId,
      role: it.role === 'USER' ? 'user' as const : 'assistant' as const,
      content: it.content ?? '',
      status: it.status,
      error: it.status === 'FAILED'
        ? { code: 'HISTORY_FAILED', message: '응답 생성에 실패했습니다.', retryable: false }
        : null,
    })),
  };
```

- [ ] **Step 4: GREEN 확인 + 전체 검증** — `npx vitest run src/chat` → PASS, 이어서 typecheck/test/build

- [ ] **Step 5: Commit**

```bash
git add fe/src/api fe/src/chat
git commit -m "[YMC-260] feat(fe): 세션 히스토리 API 래퍼·historyLoaded 리듀서"
```

---

### Task 2: 히스토리 드롭다운 이식 + 재방문 로드

**Files:**
- Modify: `fe/src/routes/study/TutorPanel.tsx`

**Interfaces:**
- Consumes: Task 1의 API·액션, `IconButton`(clock-counter-clockwise는 icons.ts에 이미 등록됨), TanStack Query(`useQuery`·`useQueryClient`).
- Produces: TutorPanel 내부 완결 — props 변경 없음.

- [ ] **Step 1: 목업 이식.** `Paper Study Page.dc.html`의 history 영역을 변환표(sc-if→`{cond && …}`, sc-for→map, 정적 style→camelCase 그대로)로 이식:
  - 헤더 좌측 그룹: `clock-counter-clockwise` IconButton(label "이전 대화 기록") — 기존 `note-pencil`(새 대화) **왼쪽**에, 목업 순서대로.
  - 드롭다운: `position:absolute; top:42px; left:0; width:220px; background:var(--color-bg-paper); border:1px solid var(--color-border); borderRadius:8px; boxShadow:var(--shadow-menu); padding:6px; zIndex:10; …` 목업 값 그대로. 항목 버튼: `9px 10px`, 13px sans, hover `background:var(--color-primary-subtle)` (state 기반 hover — 기존 패널 내 패턴 준용). 표시 텍스트는 `title`만 (목업 1:1 — 상대시간 등 추가하지 말 것).
  - 목업의 doc mousedown 바깥클릭 처리(`handleDocMouseDown`)가 historyOpen도 닫는지 원본에서 확인하고 동일하게: 닫는다면 드롭다운 열림 시 document mousedown capture 리스너(드롭다운 내부 클릭 무시) 추가, SelectionLayer의 기존 패턴 참고.
- [ ] **Step 2: 데이터 연동.**
  - `const sessionsQuery = useQuery({ queryKey: ['chat-sessions', paperId], queryFn: () => listChatSessions(paperId), enabled: historyOpen });` — 드롭다운이 열릴 때만 조회. 열 때마다 신선하게: `refetchOnMount` 기본 + 삭제·전송 후 `invalidateQueries({ queryKey: ['chat-sessions', paperId] })`.
  - 빈 목록: "저장된 대화가 없습니다" muted 한 줄. 로딩: muted "불러오는 중…". 에러: `ApiError.message` 그대로 한 줄 (spec §7 원칙 — 맥락 있는 곳 인라인).
  - 항목 클릭: `abortRef.current?.abort()` → `listChatSessionMessages(paperId, sessionId)` → `dispatch({ type: 'historyLoaded', sessionId, items })` → 드롭다운 닫기. 실패 시 드롭다운 안에 에러 한 줄, 기존 대화 유지.
  - 질문 전송 성공(completed) 시에도 세션 목록 invalidate (새 세션이 목록에 반영되게) — `onEvent`의 completed 분기에서.
- [ ] **Step 3: GENERATING 히스토리 표시 분기.** 현재 점 3개 바운스는 "마지막 assistant가 GENERATING이고 `state.streaming`"일 때만. 히스토리에서 로드된 GENERATING(스트리밍 아님)은 바운스 대신 muted 텍스트 `답변을 생성하던 중이던 메시지입니다 — 잠시 후 다시 열어 확인해 주세요.` (resume 미지원, 계약 상태 보존).
- [ ] **Step 4: 검증·커밋** — typecheck/test/build 통과.

```bash
git add fe/src/routes/study/TutorPanel.tsx
git commit -m "[YMC-260] feat(fe): 튜터 패널 이전 대화 목록·재방문 로드"
```

---

### Task 3: 세션 삭제 + 스펙 갱신

**Files:**
- Modify: `fe/src/routes/study/TutorPanel.tsx`
- Modify: `docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md` (§8-2)

**Interfaces:**
- Consumes: `deleteChatSession`(Task 1), Task 2의 드롭다운.

- [ ] **Step 1: 삭제 UI.** 드롭다운 각 항목을 `title 버튼 + x IconButton(size 28, label "대화 삭제")` 가로 배치로 확장 (목업엔 없음 — AC 요구 기능, 토큰만 사용). x 클릭 → 항목 자리에서 인라인 확인으로 전환: `"이 대화를 삭제할까요? 되돌릴 수 없습니다." [삭제] [취소]` — 삭제 버튼은 `var(--color-danger)` 텍스트(색+문구, 색만으로 구분 금지 충족). 별도 모달 만들지 말 것 (드롭다운 안 인라인 확인이면 AC "삭제 확인" 충족, YAGNI).
- [ ] **Step 2: 삭제 플로우.** 확인 → `deleteChatSession` → 성공: `invalidateQueries(['chat-sessions', paperId])`, **열려 있던 세션이면** `abortRef.current?.abort()` + `dispatch({ type: 'reset' })`. 실패(404 등): 항목 자리에 `ApiError.message` 인라인 표시. 삭제 중 버튼 disabled.
- [ ] **Step 3: 스펙 §8-2 갱신** — 기존 항목 끝에 추가: `→ **복원됨 (2026-08-01, YMC-260)** — 계약(listChatSessions·listChatSessionMessages·deleteChatSession, project-docs 698ac6e) 확정으로 세션 목록·재방문 로드·삭제 구현.`
- [ ] **Step 4: 검증·커밋** — typecheck/test/build 통과.

```bash
git add fe/src/routes/study/TutorPanel.tsx docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md
git commit -m "[YMC-260] feat(fe): 세션 삭제 — 인라인 확인·열린 세션 초기화"
```

---

## Self-Review 결과 (작성 시 수행)

- **계약 대조**: 3 operation 경로·응답 형태(bare array)·nullable content·404 코드가 openapi.yaml(698ac6e) 그대로. 구 설계 문서의 페이지네이션·409·80자 title은 계약에서 제거됐으므로 반영하지 않음.
- **AC 커버**: 세션 목록(Task 2), 재방문 로드(Task 1·2), 삭제 확인·삭제 후 상태(Task 3). "다른 사용자 접근 거부"는 BE 소유 — FE는 404/403 message 인라인 표시로 대응.
- **타입 일관성**: `ChatMessageItem`(Task 1) ↔ historyLoaded(Task 1) ↔ TutorPanel(Task 2·3). `ChatMessageStatus`는 chatState 기존 export 재사용.
