# FE v1 재구축 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `project-docs/design/v1` 디자인으로 진짜 FE를 세운다 — Landing/Bookshelf/Study 3페이지, 논문 마크다운(수식·그림·표) 렌더링, AI 튜터 챗(SSE), 인라인 액션.

**Architecture:** Vite + React + TypeScript SPA. 디자인 시스템 토큰 CSS를 그대로 복사하고 목업(.dc.html)을 1:1 이식. 본문·인라인 번역은 계약 미확정이라 어댑터(`paperContent.ts`·`translate.ts`)로 격리하고 픽스처/목으로 동작시킨다. 기존 auth·chat·upload 로직은 TS로 이식(테스트 포함).

**Tech Stack:** react-router 7, TanStack Query 5, react-markdown 10 + remark-gfm/remark-math + rehype-katex(KaTeX), Phosphor Icons(React), vitest + testing-library.

**Spec:** `docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md` (승인됨)

## Global Constraints

- 작업 디렉토리: `app/fe`. 브랜치: `YMC-289-fe-v1-redesign`.
- 커밋: `[YMC-289] type(scope): subject` 한 줄. scope는 `fe`(문서만이면 `docs`). **Co-Authored-By·Generated-with 트레일러 금지.**
- 버전 고정 (2026-07-31 npm 조회 + API 문서 검증 결과):
  - `react@^19.2.8` `react-dom@^19.2.8` `@types/react@^19` `@types/react-dom@^19`
  - `react-router@^7.18.2` — **v8.3.0이 최신이지만 쓰지 않는다.** 문서 검증이 v7까지만 가능했다. v8 업그레이드는 별도 백로그.
  - `@tanstack/react-query@^5.101.4`
  - `react-markdown@^10.1.0` `remark-gfm@^4.0.1` `remark-math@^6.0.0` `rehype-katex@^7.0.1` `katex@^0.18.1` `unified@^11.0.5` `remark-parse@^11`
  - `@phosphor-icons/react@^2.1.10`
  - `typescript@^5.9` — 7.x가 나와 있지만 생태계 호환 검증 전이므로 5.x 유지.
  - 툴체인 유지: `vite@^6` `vitest@^2` `@vitejs/plugin-react@^4` `jsdom@^25`. 추가: `@testing-library/react@^16.3.2` `@testing-library/dom@^10`.
- 디자인 SSOT: `project-docs/design/v1/`. 토큰 CSS는 **복사만, 수정 금지**. 목업에 있는데 데이터가 없는 UI(픽션)는 지어내지 말고 spec §8에 기록 후 조정.
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml`. 미확정 계약(본문 조회·인라인 번역)은 어댑터 안에서만 목/픽스처로 처리하고 `project-docs/contracts/`에 임의로 만들지 않는다.
- 브랜드: 이모지 금지, sentence case, 한·영 동급, hover는 어두워지기만(darken), `prefers-reduced-motion` 존중.
- 검증 명령: `npx tsc --noEmit` / `npm test`(= vitest run) / `npm run build`. 각 태스크 커밋 전에 셋 다 통과.
- dev 확인: `npm run dev` (vite proxy `/api` → `:8080`는 기존 `vite.config.js` 설정 유지).

### 목업 DSL → React 변환표 (이식 태스크 공통)

목업 `.dc.html`은 아래 표면만 쓴다. 임시 UI 이식 때 검증된 기계적 변환이다.

| 목업 | React |
|---|---|
| `<sc-if value="{{ cond }}">…</sc-if>` | `{cond && (…)}` |
| `sc-camel-on-click="{{ fn }}"` (기타 이벤트 동일) | `onClick={fn}` |
| `style="{{ styleObj }}"` | `style={styleObj}` (이미 camelCase 객체) |
| `>{{ value }}<` | `{value}` |
| `<x-map value="{{ items }}" as="item">` | `{items.map((item) => …)}` |
| `<x-import component-from-global-scope="PaperTeacherDesignSystem_1a53a7.X" p="v">` | `<X p="v" />` (Task 7에서 이식한 컴포넌트) |
| 정적 `style="a:b;c:d"` | `style={{ a: 'b', c: 'd' }}` camelCase 변환 |

목업 파일과 영역 주석(원본에 실제로 있는 `<!-- R# … -->`):

- `Paper Landing Page.dc.html` — top bar / 중앙 선언문+CTA
- `Paper Bookshelf Page.dc.html` — R1 spacer, R2 헤더, R3 컨트롤, R4 목록, R5 페이지네이션, Profile dropdown, Custom top bar overlay, Upload dialog, Toast
- `Paper Study Page.dc.html` — R1 top bar, R2 work area, R3 TOC rail, R4 뷰어, Resizable splitter, R5 챗 패널, Selection popup, Ask popup, Translation popup

---

### Task 1: TypeScript 툴체인 도입

**Files:**
- Modify: `fe/package.json`
- Create: `fe/tsconfig.json`
- Create: `fe/src/vite-env.d.ts`

**Interfaces:**
- Produces: 이후 모든 태스크가 `.ts`/`.tsx`를 추가할 수 있는 상태. `npm run typecheck` 스크립트.

- [ ] **Step 1: 의존성 설치**

```bash
cd fe
npm i react@^19.2.8 react-dom@^19.2.8 react-router@^7.18.2 @tanstack/react-query@^5.101.4 \
  react-markdown@^10.1.0 remark-gfm@^4.0.1 remark-math@^6.0.0 rehype-katex@^7.0.1 katex@^0.18.1 \
  unified@^11.0.5 remark-parse@^11 @phosphor-icons/react@^2.1.10
npm i -D typescript@^5.9 @types/react@^19 @types/react-dom@^19 \
  @testing-library/react@^16.3.2 @testing-library/dom@^10
```

- [ ] **Step 2: tsconfig.json 작성**

```jsonc
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noEmit": true,
    "allowJs": true,          // 이식 중 .jsx 공존 허용 — Task 15에서 남은 js가 없음을 확인
    "skipLibCheck": true,
    "isolatedModules": true,
    "types": ["vite/client"]
  },
  "include": ["src"]
}
```

- [ ] **Step 3: vite-env.d.ts 작성** (`?raw` 임포트 타입 — Task 5의 픽스처용)

```ts
/// <reference types="vite/client" />
declare module '*.md?raw' {
  const content: string;
  export default content;
}
```

- [ ] **Step 4: package.json 스크립트에 `"typecheck": "tsc --noEmit"` 추가**

- [ ] **Step 5: 기존 코드가 그대로 도는지 확인**

Run: `npm run typecheck && npm test && npm run build`
Expected: 셋 다 통과 (기존 .jsx는 allowJs로 통과, 기존 vitest 테스트 6파일 전부 PASS)

- [ ] **Step 6: Commit**

```bash
git add fe/package.json fe/package-lock.json fe/tsconfig.json fe/src/vite-env.d.ts
git commit -m "[YMC-289] chore(fe): TypeScript 툴체인·핵심 의존성 도입"
```

---

### Task 2: 디자인 토큰 이식 + 전역 스타일

**Files:**
- Create: `fe/src/design/tokens/` (colors·typography·spacing·radius·shadows·fonts .css — 번들에서 복사)
- Create: `fe/src/design/global.css`
- Modify: `fe/src/main.jsx` (CSS import 추가)
- Modify: `fe/index.html` (title, lang)

**Interfaces:**
- Produces: 모든 컴포넌트가 쓰는 CSS 변수(`--color-*`, `--font-*`, `--space-*`, `--radius-*`, `--shadow-*`)와 `[data-theme="night"]` 토큰 스왑.

- [ ] **Step 1: 토큰 복사 (수정 금지)**

```bash
mkdir -p fe/src/design/tokens
cp ../project-docs/design/v1/paper-teacher-design-system-*/tokens/*.css fe/src/design/tokens/
```

- [ ] **Step 2: global.css 작성** — 목업 helmet의 전역 규칙과 동일

```css
@import './tokens/fonts.css';
@import './tokens/colors.css';
@import './tokens/typography.css';
@import './tokens/spacing.css';
@import './tokens/radius.css';
@import './tokens/shadows.css';

body { margin: 0; }
a { color: var(--color-primary); }
a:hover { color: var(--color-primary-hover); }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
```

- [ ] **Step 3: main.jsx 최상단에 `import './design/global.css';` 추가. index.html `<html lang="ko">`, `<title>Paper Teacher</title>`.**

- [ ] **Step 4: 확인 후 커밋**

Run: `npm run build` — 통과. `npm run dev`로 폰트(CDN) 로드 확인(개발자 도구 network에 Noto Serif KR·Pretendard).

```bash
git add fe/src/design fe/src/main.jsx fe/index.html
git commit -m "[YMC-289] feat(fe): 디자인 시스템 토큰·전역 스타일 이식"
```

---

### Task 3: api·auth 로직 TS 이식

**Files:**
- Create: `fe/src/api/types.ts`, `fe/src/api/auth.ts`, `fe/src/api/papers.ts`
- Create: `fe/src/api/auth.test.ts`, `fe/src/api/papers.test.ts` (기존 테스트 이동)
- Delete: `fe/src/auth.js`, `fe/src/api.js`, `fe/src/auth.test.js`, `fe/src/api.test.js`
- Modify: `fe/src/AuthRoot.jsx`, `fe/src/App.jsx`, `fe/src/chat/chatStream.js` (import 경로만 `../api/auth` 등으로)

**Interfaces:**
- Produces (papers.ts): `createPaper(filename, contentType): Promise<CreatePaperResponse>` / `uploadToS3(uploadUrl, file, onProgress?): Promise<void>` / `completeUpload(paperId)` / `getStatus(paperId): Promise<PaperStatusResponse>` / `getDownloadUrl(paperId)` / `listPapers(): Promise<{ papers: Paper[] }>`
- Produces (auth.ts): `bootstrap()` / `login({onComplete})` / `logout()` / `authFetch(url, opts?)` / `onSessionExpired(handler)` / `_resetForTest()` — 시그니처는 기존 JS와 동일, 타입만 부여.
- Produces (types.ts): 아래 타입 전부. **enum 값은 openapi.yaml 그대로다 — 바꾸지 말 것.**

- [ ] **Step 1: types.ts 작성**

```ts
// 출처: project-docs/contracts/frontend-backend/openapi.yaml (PaperStatus, Error 스키마)
export type PaperStatus =
  | 'UPLOAD_PENDING' | 'UPLOADED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED';

export const TERMINAL_STATUSES: ReadonlySet<PaperStatus> = new Set(['COMPLETED', 'FAILED', 'EXPIRED']);

export interface Paper {
  paperId: string;
  filename: string;
  status: PaperStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePaperResponse {
  paperId: string;
  fileKey: string;
  uploadUrl: string;
  uploadExpiresAt: string;
  status: PaperStatus;
  createdAt: string;
}

export interface PaperStatusResponse { paperId: string; status: PaperStatus; updatedAt: string; }

export interface AuthUser { email?: string; displayName?: string; }

export class ApiError extends Error {
  constructor(message: string, readonly code: string | undefined, readonly httpStatus: number) {
    super(message);
    this.name = 'ApiError';
  }
}
```

- [ ] **Step 2: auth.js → api/auth.ts 이식.** 로직 변경 없음(모듈 메모리 access token, single-flight refresh, 팝업 로그인 postMessage+BroadcastChannel 이중화, 401 재시도 1회). 타입: `let accessToken: string | null`, `login(opts: { onComplete: (user: AuthUser | null, error: string | null) => void }): () => void`, `authFetch(url: string, opts?: RequestInit): Promise<Response>`.

- [ ] **Step 3: api.js → api/papers.ts 이식.** `apiError()`는 `ApiError` 인스턴스를 만들도록 교체:

```ts
async function apiError(res: Response): Promise<ApiError> {
  let body: { code?: string; message?: string } = {};
  try { body = await res.json(); } catch { /* 비-JSON 응답 */ }
  return new ApiError(body.message || `HTTP ${res.status}`, body.code, res.status);
}
```

나머지 함수 본문은 기존과 동일(특히 `uploadToS3`의 `setRequestHeader('Content-Type', 'application/pdf')` 유지 — presign 서명에 포함되는 값).

- [ ] **Step 4: 기존 테스트를 .ts로 이동.** `auth.test.js` → `api/auth.test.ts`, `api.test.js` → `api/papers.test.ts`. 테스트 본문 유지, import 경로만 수정. 소비처(.jsx 3곳)의 import 경로 수정.

- [ ] **Step 5: 검증 후 구파일 삭제·커밋**

Run: `npm run typecheck && npm test && npm run build` — 전부 통과.

```bash
git add -A fe/src
git commit -m "[YMC-289] refactor(fe): api·auth 모듈 TypeScript 이식"
```

---

### Task 4: chat 로직 TS 이식

**Files:**
- Create: `fe/src/chat/sseParser.ts`, `fe/src/chat/chatStream.ts`, `fe/src/chat/chatState.ts` (+ 각 `.test.ts`로 기존 테스트 이동)
- Delete: 대응하는 `.js`/`.test.js` 5개 (`ChatPanel.jsx`는 Task 13까지 유지)

**Interfaces:**
- Produces (sseParser.ts): `createSseParser(): { push(chunk: Uint8Array): SseFrame[] }`, `interface SseFrame { event: string; data: unknown }`
- Produces (chatStream.ts): `streamChatMessage(opts: StreamOpts): Promise<void>` — `StreamOpts = { paperId: string; sessionId: string | null; clientMessageId: string; content: string; signal?: AbortSignal; onEvent: (e: ChatStreamEvent) => void }`
- Produces (chatState.ts): `chatReducer(state: ChatState, action: ChatAction): ChatState`, `initialChatState: ChatState`

- [ ] **Step 1: 타입 정의 추가하며 이식.** 로직은 무변경 — 계약 의미론(delta append→completed replace, terminal 없는 EOF는 결과 미상, heartbeat 무시, 409 DUPLICATE_MESSAGE 회수)이 이미 테스트로 고정돼 있다.

```ts
// chatStream.ts
export type ChatStreamEvent =
  | { type: 'started'; sessionId: string; messageId: string }
  | { type: 'delta'; delta: string }
  | { type: 'completed'; content: string }
  | { type: 'failed'; confirmed: boolean; code: string; message: string; retryable: boolean }
  | { type: 'duplicate'; sessionId: string; messageId: string; status: 'GENERATING' | 'COMPLETED' | 'FAILED' };

// chatState.ts
export type ChatMessageStatus = 'GENERATING' | 'COMPLETED' | 'FAILED'; // 계약 ChatMessageStatus
export interface ChatMessage {
  key: string;
  role: 'user' | 'assistant';
  content: string;
  status: ChatMessageStatus;
  error: { code: string; message?: string; retryable: boolean } | null;
}
export interface ChatState {
  sessionId: string | null;
  messages: ChatMessage[];
  streaming: boolean;
  pending: { clientMessageId: string; content: string } | null;
}
export type ChatAction =
  | { type: 'send'; clientMessageId: string; content: string; resend?: boolean }
  | { type: 'started'; sessionId: string }
  | { type: 'delta'; delta: string }
  | { type: 'completed'; content: string }
  | { type: 'failed'; confirmed: boolean; code: string; message?: string; retryable: boolean }
  | { type: 'duplicate'; sessionId: string; status: ChatMessageStatus }
  | { type: 'reset' }; // 새 대화(ask popup의 '새 대화에서') — initialChatState로 복귀
```

`reset` 액션만 신규다: `case 'reset': return initialChatState;` (Task 13·14의 "새 대화" 진입점).

- [ ] **Step 2: 테스트 이동 후 reset 케이스 테스트 추가**

```ts
test('reset은 초기 상태로 돌아간다', () => {
  let s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '질문' });
  s = chatReducer(s, { type: 'reset' });
  expect(s).toEqual(initialChatState);
});
```

- [ ] **Step 3: 검증·커밋**

Run: `npm run typecheck && npm test` — 통과 (`ChatPanel.jsx`의 import는 `./chatState` 등 확장자 없는 경로라 그대로 동작).

```bash
git add -A fe/src/chat
git commit -m "[YMC-289] refactor(fe): chat SSE 로직 TypeScript 이식·reset 액션 추가"
```

---

### Task 5: 픽스처 논문 + paperContent 어댑터

**Files:**
- Create: `fe/src/fixtures/sample-paper.md`
- Create: `fe/public/fixtures/figure-attention.svg`
- Create: `fe/src/markdown/paperContent.ts`
- Test: `fe/src/markdown/paperContent.test.ts`

**Interfaces:**
- Produces: `parsePaperMarkdown(source: string): PaperContent` (순수 함수), `getPaperContent(paperId: string): Promise<PaperContent>` (계약 미확정 접점 — 지금은 픽스처), `PaperBlock { id, type: 'heading'|'subheading'|'para'|'figure'|'equation'|'table'|'other', markdown, headingText?, headingLevel? }`, `PaperContent { blocks: PaperBlock[], toc: TocEntry[] }`, `TocEntry { blockId, text, level }`
- Consumes: 없음 (독립 모듈)

- [ ] **Step 1: 픽스처 작성** — `sample-paper.md`. 요구 요소 전부 포함: h1 제목, h2 섹션 3개, h3 하위 섹션, 인라인 수식, 블록 수식 2개, 그림 1개(캡션), GFM 표 1개, 문단 다수. 아래 내용 그대로 저장:

````markdown
# Attention Is All You Need — 학습용 픽스처

## 1. Introduction

Recurrent 모델은 시퀀스를 순차 처리하므로 병렬화가 어렵다. 본 논문은 attention만으로
시퀀스 변환을 수행하는 Transformer를 제안한다. 시퀀스 길이 $n$, 표현 차원 $d$에서
self-attention 층의 복잡도는 $O(n^2 \cdot d)$이다.

The dominant sequence transduction models are based on complex recurrent or
convolutional neural networks. We propose a new simple network architecture.

## 2. Model Architecture

### 2.1 Scaled Dot-Product Attention

query·key·value 행렬 $Q, K, V$에 대해 attention은 다음과 같이 정의된다.

$$
\mathrm{Attention}(Q, K, V) = \mathrm{softmax}\!\left(\frac{QK^{\top}}{\sqrt{d_k}}\right)V
$$

$\sqrt{d_k}$로 나누는 이유는 내적 값이 커질수록 softmax의 gradient가 소실되기 때문이다.

### 2.2 Multi-Head Attention

$$
\mathrm{MultiHead}(Q, K, V) = \mathrm{Concat}(\mathrm{head}_1, \ldots, \mathrm{head}_h)W^{O}
$$

![Figure 1: Transformer 아키텍처 — encoder-decoder 구조와 multi-head attention의 배치](/fixtures/figure-attention.svg)

## 3. Results

| Model | BLEU (EN-DE) | Training cost (FLOPs) |
| --- | --- | --- |
| ByteNet | 23.75 | — |
| ConvS2S | 25.16 | $9.6 \times 10^{18}$ |
| Transformer (big) | **28.4** | $2.3 \times 10^{19}$ |

Transformer는 더 적은 학습 비용으로 기존 최고 성능을 넘었다.
````

- [ ] **Step 2: 그림 픽스처 작성** — `figure-attention.svg` (외부 의존 없는 단색 라인 일러스트, 브랜드 규칙: 캐릭터·3D 금지):

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="480" height="280" viewBox="0 0 480 280">
  <rect width="480" height="280" fill="#FAF6EF"/>
  <g fill="none" stroke="#1F3552" stroke-width="1.5">
    <rect x="60" y="40" width="140" height="200" rx="4"/>
    <rect x="280" y="40" width="140" height="200" rx="4"/>
    <line x1="200" y1="140" x2="280" y2="140"/>
    <rect x="80" y="70" width="100" height="30" rx="2"/>
    <rect x="80" y="120" width="100" height="30" rx="2"/>
    <rect x="80" y="170" width="100" height="30" rx="2"/>
    <rect x="300" y="70" width="100" height="30" rx="2"/>
    <rect x="300" y="120" width="100" height="30" rx="2"/>
    <rect x="300" y="170" width="100" height="30" rx="2"/>
  </g>
  <text x="130" y="260" text-anchor="middle" font-family="serif" font-size="12" fill="#1F3552">Encoder</text>
  <text x="350" y="260" text-anchor="middle" font-family="serif" font-size="12" fill="#1F3552">Decoder</text>
</svg>
```

- [ ] **Step 3: 실패하는 테스트 작성** — `paperContent.test.ts`

```ts
import { describe, expect, test } from 'vitest';
import { parsePaperMarkdown } from './paperContent';

const SRC = [
  '# 제목',
  '',
  '## 1. Introduction',
  '',
  '첫 문단이다. 인라인 수식 $x^2$ 포함.',
  '',
  '### 1.1 하위 절',
  '',
  '$$',
  'E = mc^2',
  '$$',
  '',
  '![그림 1: 캡션 텍스트](/fixtures/figure-attention.svg)',
  '',
  '| a | b |',
  '| - | - |',
  '| 1 | 2 |',
].join('\n');

describe('parsePaperMarkdown', () => {
  const { blocks, toc } = parsePaperMarkdown(SRC);

  test('최상위 노드가 순서대로 블록이 된다', () => {
    expect(blocks.map((b) => b.type)).toEqual([
      'heading', 'heading', 'para', 'subheading', 'equation', 'figure', 'table',
    ]);
    expect(blocks.map((b) => b.id)).toEqual(blocks.map((_, i) => `block-${i}`));
  });

  test('블록 markdown은 원문 slice다', () => {
    expect(blocks[2].markdown).toBe('첫 문단이다. 인라인 수식 $x^2$ 포함.');
    expect(blocks[4].markdown).toBe('$$\nE = mc^2\n$$');
  });

  test('h1·h2는 heading, h3+는 subheading, heading 텍스트를 추출한다', () => {
    expect(blocks[1]).toMatchObject({ type: 'heading', headingText: '1. Introduction', headingLevel: 2 });
    expect(blocks[3]).toMatchObject({ type: 'subheading', headingText: '1.1 하위 절', headingLevel: 3 });
  });

  test('이미지 단독 문단은 figure다', () => {
    expect(blocks[5].type).toBe('figure');
  });

  test('toc는 h2·h3만 담는다 (h1 = 논문 제목)', () => {
    expect(toc).toEqual([
      { blockId: 'block-1', text: '1. Introduction', level: 2 },
      { blockId: 'block-3', text: '1.1 하위 절', level: 3 },
    ]);
  });
});
```

- [ ] **Step 4: 실패 확인**

Run: `npx vitest run src/markdown/paperContent.test.ts`
Expected: FAIL — `paperContent` 모듈 없음

- [ ] **Step 5: 구현** — `paperContent.ts`

```ts
// 본문 계약(DocumentParseResponse) 미확정 — FT-004 블로커, Jira 백로그.
// 이 모듈이 "무엇이 오든 블록 배열로 정규화"하는 유일한 접점이다.
// 계약 확정 시 getPaperContent 내부만 바꾼다 (spec §6).
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import type { Content, Parent } from 'mdast';
import samplePaper from '../fixtures/sample-paper.md?raw';

export type BlockType = 'heading' | 'subheading' | 'para' | 'figure' | 'equation' | 'table' | 'other';

export interface PaperBlock {
  id: string;
  type: BlockType;
  markdown: string;
  headingText?: string;
  headingLevel?: number;
}

export interface TocEntry { blockId: string; text: string; level: number; }
export interface PaperContent { blocks: PaperBlock[]; toc: TocEntry[]; }

const parser = unified().use(remarkParse).use(remarkGfm).use(remarkMath);

export function parsePaperMarkdown(source: string): PaperContent {
  const tree = parser.parse(source) as Parent;
  const blocks = (tree.children as Content[]).map((node, i): PaperBlock => {
    const markdown = source.slice(node.position!.start.offset!, node.position!.end.offset!);
    return { id: `block-${i}`, markdown, ...classify(node) };
  });
  const toc = blocks
    .filter((b) => (b.type === 'heading' || b.type === 'subheading') && b.headingLevel! >= 2)
    .map((b) => ({ blockId: b.id, text: b.headingText!, level: b.headingLevel! }));
  return { blocks, toc };
}

function classify(node: Content): Omit<PaperBlock, 'id' | 'markdown'> {
  switch (node.type) {
    case 'heading':
      return {
        type: node.depth <= 2 ? 'heading' : 'subheading',
        headingText: textOf(node),
        headingLevel: node.depth,
      };
    case 'paragraph': {
      const children = (node as Parent).children as Content[];
      const meaningful = children.filter((c) => !(c.type === 'text' && /^\s*$/.test((c as { value: string }).value)));
      if (meaningful.length === 1 && meaningful[0].type === 'image') return { type: 'figure' };
      return { type: 'para' };
    }
    case 'math':
      return { type: 'equation' };
    case 'table':
      return { type: 'table' };
    default:
      return { type: 'other' };
  }
}

function textOf(node: Content): string {
  if ('value' in node && typeof node.value === 'string') return node.value;
  if ('children' in node) return ((node as Parent).children as Content[]).map(textOf).join('');
  return '';
}

export async function getPaperContent(_paperId: string): Promise<PaperContent> {
  // 계약 미확정 — 픽스처 반환. 확정 시 이 함수만 실제 fetch로 교체 (spec §6).
  return parsePaperMarkdown(samplePaper);
}
```

- [ ] **Step 6: 통과 확인 후 커밋**

Run: `npx vitest run src/markdown/paperContent.test.ts` → PASS, 이어서 `npm run typecheck && npm test`

```bash
git add fe/src/markdown fe/src/fixtures fe/public/fixtures
git commit -m "[YMC-289] feat(fe): 논문 픽스처·paperContent 블록 정규화 어댑터"
```

---

### Task 6: PaperMarkdown 공용 렌더러

**Files:**
- Create: `fe/src/markdown/PaperMarkdown.tsx`, `fe/src/markdown/markdown.css`
- Test: `fe/src/markdown/PaperMarkdown.test.tsx`

**Interfaces:**
- Produces: `<PaperMarkdown>{markdownString}</PaperMarkdown>` — 챗(Task 13)과 뷰어(Task 12)가 공유.
- Consumes: 없음.

- [ ] **Step 1: 실패하는 테스트 작성**

```tsx
import { describe, expect, test } from 'vitest';
import { render } from '@testing-library/react';
import { PaperMarkdown } from './PaperMarkdown';

describe('PaperMarkdown', () => {
  test('KaTeX로 수식을 렌더한다', () => {
    const { container } = render(<PaperMarkdown>{'인라인 $E = mc^2$ 수식'}</PaperMarkdown>);
    expect(container.querySelector('.katex')).not.toBeNull();
  });

  test('깨진 수식에도 죽지 않는다', () => {
    const { container } = render(<PaperMarkdown>{'$\\undefinedmacro{x}$'}</PaperMarkdown>);
    expect(container.textContent).toContain('undefinedmacro'); // 원문 노출, throw 없음
  });

  test('GFM 표를 렌더한다', () => {
    const { container } = render(<PaperMarkdown>{'| a |\n| - |\n| 1 |'}</PaperMarkdown>);
    expect(container.querySelector('table')).not.toBeNull();
  });

  test('이미지는 figure 프레임 + figcaption(alt)으로 렌더한다', () => {
    const { container } = render(<PaperMarkdown>{'![그림 1: 캡션](/x.svg)'}</PaperMarkdown>);
    expect(container.querySelector('figure.pt-figure img')?.getAttribute('src')).toBe('/x.svg');
    expect(container.querySelector('figcaption')?.textContent).toBe('그림 1: 캡션');
  });
});
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/markdown/PaperMarkdown.test.tsx` → FAIL

- [ ] **Step 3: 구현**

```tsx
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import 'katex/dist/katex.min.css';
import './markdown.css';

export function PaperMarkdown({ children }: { children: string }) {
  return (
    <div className="pt-markdown">
      <Markdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[[rehypeKatex, { throwOnError: false }]]}
        components={{
          img({ src, alt }) {
            return (
              <figure className="pt-figure">
                <img src={src ?? ''} alt={alt ?? ''} loading="lazy"
                  onError={(e) => { e.currentTarget.classList.add('pt-figure-broken'); }} />
                {alt && <figcaption>{alt}</figcaption>}
              </figure>
            );
          },
          table(props) {
            return <div className="pt-table-scroll"><table {...props} /></div>;
          },
        }}
      >
        {children}
      </Markdown>
    </div>
  );
}
```

`markdown.css` — 디자인 시스템 규칙(읽기 서체 serif·1.8 행간, 폴리오 프레임은 테두리+웜 서피스·그림자 없음, 표는 얇은 보더):

```css
.pt-markdown { font-family: var(--font-serif); color: var(--color-text-body); line-height: 1.8; }
.pt-markdown h1, .pt-markdown h2, .pt-markdown h3 { color: var(--color-text-heading); line-height: 1.4; }
.pt-markdown .katex-display { overflow-x: auto; padding: var(--space-xs) 0; }

.pt-figure { margin: var(--space-lg) 0; }
.pt-figure img {
  display: block; max-width: 100%;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-structural);
  padding: var(--space-sm);
  box-sizing: border-box;
}
.pt-figure img.pt-figure-broken { min-height: 80px; }
.pt-figure figcaption {
  margin-top: var(--space-xs); font-family: var(--font-sans);
  font-size: 13px; color: var(--color-text-muted);
}

.pt-table-scroll { overflow-x: auto; margin: var(--space-md) 0; }
.pt-markdown table { border-collapse: collapse; font-size: 14px; }
.pt-markdown th, .pt-markdown td { border: 1px solid var(--color-border); padding: var(--space-xs) var(--space-sm); }
.pt-markdown th { font-family: var(--font-sans); font-weight: 600; background: var(--color-bg-surface); }
```

- [ ] **Step 4: 통과 확인 후 커밋**

Run: `npx vitest run src/markdown` → PASS, `npm run typecheck && npm test && npm run build`

```bash
git add fe/src/markdown
git commit -m "[YMC-289] feat(fe): PaperMarkdown 공용 렌더러 (KaTeX·GFM·figure 프레임)"
```

---

### Task 7: 디자인 시스템 컴포넌트 7종 이식

**Files:**
- Create: `fe/src/design/components/PaperStackMark.tsx`, `Button.tsx`, `IconButton.tsx`, `PaperSheet.tsx`, `TutorNotebook.tsx`, `NotebookSection.tsx`, `StudentMessage.tsx`

**Interfaces:**
- Produces: 목업이 `x-import`로 실제 사용하는 7종만 (전수 조사 결과: Button 4회, IconButton 8회, PaperStackMark 3회, PaperSheet·TutorNotebook·NotebookSection·StudentMessage 각 1회). 그 외 DS 컴포넌트는 YAGNI — 이식하지 않는다. 목업의 dialog·toast·input은 인라인 마크업이므로 페이지 태스크에서 직접 이식한다.
- Consumes: Task 2의 토큰 CSS 변수.

- [ ] **Step 1: 원본 소스 추출.** `project-docs/design/v1/paper-teacher-design-system-*/_ds_bundle.js`를 열면 각 컴포넌트가 `sourcePath`(예: `components/core/Button.jsx`) 주석과 함께 JSX 소스로 들어 있다. 7종의 소스를 찾아 그대로 TSX로 옮긴다 — 스타일 값·구조 변경 금지, props 타입만 부여. 각 컴포넌트의 props는 번들 내 `.d.ts` 스텁과 목업의 실제 사용처(`x-import` 속성)를 기준으로 한다.

- [ ] **Step 2: 사용처 대조.** 3개 목업에서 `x-import` 호출부를 grep해 props 목록을 뽑고, 이식본 시그니처가 전부 커버하는지 확인한다:

```bash
grep -o -E 'x-import component-from-global-scope="PaperTeacherDesignSystem_1a53a7\.[^>]+' \
  ../project-docs/design/v1/*.dc.html
```

- [ ] **Step 3: 검증·커밋** — `npm run typecheck && npm run build` 통과.

```bash
git add fe/src/design/components
git commit -m "[YMC-289] feat(fe): 디자인 시스템 컴포넌트 7종 이식"
```

---

### Task 8: 라우터 + 인증 게이트

**Files:**
- Create: `fe/src/auth/AuthContext.tsx`, `fe/src/auth/RequireAuth.tsx`
- Create: `fe/src/routes/LandingPage.tsx`, `fe/src/routes/BookshelfPage.tsx`, `fe/src/routes/StudyPage.tsx` (뼈대 — 페이지 태스크에서 채움)
- Modify: `fe/src/main.jsx` → `fe/src/main.tsx`
- Delete: `fe/src/AuthRoot.jsx` (의미론을 AuthContext로 흡수)

**Interfaces:**
- Produces: `useAuth(): { status: 'loading'|'guest'|'authed'; user: AuthUser | null; startLogin(): void; signOut(): Promise<void>; initialError: string | null }`, 라우트 3개(`/`, `/library`, `/papers/:paperId`).
- Consumes: Task 3 `api/auth.ts`.

- [ ] **Step 1: AuthContext 작성.** `AuthRoot.jsx`의 의미론을 그대로 옮긴다 — StrictMode 이중 실행 방어(?error 소비를 effect에서), `onSessionExpired` 시 guest 전환, bootstrap 실패도 guest, logout은 네트워크 실패에도 로컬 정리:

```tsx
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { bootstrap, login, logout, onSessionExpired } from '../api/auth';
import type { AuthUser } from '../api/types';

interface AuthContextValue {
  status: 'loading' | 'guest' | 'authed';
  user: AuthUser | null;
  initialError: string | null;
  startLogin: () => void;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<Pick<AuthContextValue, 'status' | 'user'>>({ status: 'loading', user: null });
  const [initialError, setInitialError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('error')) {
      window.history.replaceState(null, '', '/');
      setInitialError('로그인에 실패했습니다. 다시 시도해 주세요.');
    }
    onSessionExpired(() => setState({ status: 'guest', user: null }));
    bootstrap()
      .then((user) => setState({ status: user ? 'authed' : 'guest', user }))
      .catch(() => setState({ status: 'guest', user: null }));
  }, []);

  const startLogin = useCallback(() => {
    login({
      onComplete: (user, error) => {
        if (user) setState({ status: 'authed', user });
        else if (error) setInitialError('로그인에 실패했습니다. 다시 시도해 주세요.');
      },
    });
  }, []);

  const signOut = useCallback(async () => {
    try { await logout(); } catch { /* 로컬 세션은 정리 — 쿠키는 다음 refresh 실패로 소멸 */ }
    setState({ status: 'guest', user: null });
  }, []);

  return (
    <AuthContext.Provider value={{ ...state, initialError, startLogin, signOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth는 AuthProvider 안에서만 쓴다');
  return ctx;
}
```

- [ ] **Step 2: RequireAuth 작성**

```tsx
import { Navigate, Outlet } from 'react-router';
import { useAuth } from './AuthContext';

export function RequireAuth() {
  const { status } = useAuth();
  if (status === 'loading') return <div style={{ padding: 48, textAlign: 'center' }}>불러오는 중…</div>;
  if (status === 'guest') return <Navigate to="/" replace />;
  return <Outlet />;
}
```

- [ ] **Step 3: main.tsx 작성** (main.jsx 대체)

```tsx
import React from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './design/global.css';
import { AuthProvider } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import LandingPage from './routes/LandingPage';
import BookshelfPage from './routes/BookshelfPage';
import StudyPage from './routes/StudyPage';

const queryClient = new QueryClient();
const router = createBrowserRouter([
  { path: '/', element: <LandingPage /> },
  {
    element: <RequireAuth />,
    children: [
      { path: '/library', element: <BookshelfPage /> },
      { path: '/papers/:paperId', element: <StudyPage /> },
    ],
  },
]);

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
```

`index.html`의 `src="/src/main.jsx"`를 `main.tsx`로 수정. 라우트 3파일은 `export default function …Page() { return <div>…</div>; }` 뼈대로 시작 (LandingPage는 `useAuth().status === 'authed'`면 `<Navigate to="/library" replace />`).

- [ ] **Step 4: 검증·커밋.** 이 시점에 구 `App.jsx`·`Landing.jsx`는 라우터에서 분리돼 죽은 코드가 된다 — 아직 지우지 않는다(Task 10·11 이식 참조용, 삭제는 Task 15).

Run: `npm run typecheck && npm test && npm run build`, `npm run dev`로 `/` → 로그인 → `/library` 뼈대 진입 확인.

```bash
git add -A fe/src fe/index.html
git commit -m "[YMC-289] feat(fe): react-router·TanStack Query·인증 게이트 골격"
```

---

### Task 9: Landing 페이지 이식

**Files:**
- Modify: `fe/src/routes/LandingPage.tsx`

**Interfaces:**
- Consumes: `useAuth()` (Task 8), `PaperStackMark`·`Button` (Task 7).

- [ ] **Step 1: 목업 이식.** `project-docs/design/v1/Paper Landing Page.dc.html`(4KB)을 열고 변환표대로 옮긴다 — 고정 top bar(월넛, PaperStackMark + 워드마크 + 로그인 버튼), 중앙 가치 선언문(Noto Serif KR), CTA. CTA와 로그인 버튼 모두 `startLogin()`, `initialError`는 선언문 아래 muted red 텍스트로 표시(색만으로 구분 금지 — 문구 포함이므로 충족).

- [ ] **Step 2: 시각 대조.** `npm run dev` 화면과 목업 파일(브라우저로 직접 열기)을 나란히 놓고 대조 — 레이아웃·색·서체·간격 동일 확인.

- [ ] **Step 3: 검증·커밋**

```bash
git add fe/src/routes/LandingPage.tsx
git commit -m "[YMC-289] feat(fe): 랜딩 페이지 design/v1 이식"
```

---

### Task 10: Bookshelf 페이지 — 목록·컨트롤·폴링

**Files:**
- Modify: `fe/src/routes/BookshelfPage.tsx`
- Create: `fe/src/routes/bookshelf/usePapersQuery.ts`, `fe/src/routes/bookshelf/paperFilters.ts`
- Test: `fe/src/routes/bookshelf/paperFilters.test.ts`

**Interfaces:**
- Produces: `usePapersQuery()` — 논문 목록 + 비terminal 행 존재 시 2초 자동 폴링. `filterPapers(papers, keyword)`, `paginate(items, page, pageSize)` 순수 함수.
- Consumes: `listPapers`·`TERMINAL_STATUSES` (Task 3), DS 컴포넌트 (Task 7), `useAuth` (Task 8).

- [ ] **Step 1: 실패하는 테스트 작성** — `paperFilters.test.ts`

```ts
import { expect, test } from 'vitest';
import { filterPapers, paginate } from './paperFilters';
import type { Paper } from '../../api/types';

const p = (filename: string): Paper =>
  ({ paperId: filename, filename, status: 'COMPLETED', createdAt: '', updatedAt: '' });

test('filterPapers는 파일명 부분일치·대소문자 무시', () => {
  const papers = [p('Attention.pdf'), p('BERT.pdf')];
  expect(filterPapers(papers, 'atten')).toEqual([papers[0]]);
  expect(filterPapers(papers, '')).toEqual(papers);
});

test('paginate는 페이지를 자르고 전체 페이지 수를 준다', () => {
  const items = Array.from({ length: 23 }, (_, i) => i);
  expect(paginate(items, 1, 10).items).toHaveLength(10);
  expect(paginate(items, 3, 10)).toEqual({ items: [20, 21, 22], totalPages: 3 });
  expect(paginate(items, 99, 10).items).toEqual([20, 21, 22]); // 범위 밖은 마지막 페이지로 clamp
});
```

- [ ] **Step 2: 실패 확인 후 구현** — `paperFilters.ts`

```ts
import type { Paper } from '../../api/types';

export function filterPapers(papers: Paper[], keyword: string): Paper[] {
  const k = keyword.trim().toLowerCase();
  if (!k) return papers;
  return papers.filter((p) => p.filename.toLowerCase().includes(k));
}

export function paginate<T>(items: T[], page: number, pageSize: number): { items: T[]; totalPages: number } {
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  const clamped = Math.min(Math.max(1, page), totalPages);
  return { items: items.slice((clamped - 1) * pageSize, clamped * pageSize), totalPages };
}
```

`usePapersQuery.ts` — 비terminal이 하나라도 있으면 목록 전체를 2초 폴링한다. (임시 UI는 행별 status 폴링이었지만, 이제 `GET /api/papers`가 있으므로 목록 폴링이 더 단순하고 계약에도 맞다.)

```ts
import { useQuery } from '@tanstack/react-query';
import { listPapers } from '../../api/papers';
import { TERMINAL_STATUSES } from '../../api/types';

export function usePapersQuery() {
  return useQuery({
    queryKey: ['papers'],
    queryFn: listPapers,
    refetchInterval: (query) => {
      const papers = query.state.data?.papers;
      return papers?.some((p) => !TERMINAL_STATUSES.has(p.status)) ? 2000 : false;
    },
  });
}
```

Run: `npx vitest run src/routes/bookshelf` → PASS

- [ ] **Step 3: 페이지 이식.** `Paper Bookshelf Page.dc.html`을 변환표대로 옮긴다. 영역별:
  - **top bar overlay + Profile dropdown**: `useAuth().user` 표시, 로그아웃 → `signOut()` 후 `<Navigate to="/">`는 RequireAuth가 처리.
  - **R3 컨트롤**: 검색 input(`filterPapers`), 그리드/리스트 토글(`isGridView` state), 업로드 버튼(Task 11 Dialog open).
  - **R4 목록**: `usePapersQuery()` 데이터. 행 표시 매핑 — `UPLOAD_PENDING`/`UPLOADED`/`PROCESSING` → "분석 중" + **불확정 애니메이션 바**(spec §8-1, 목업의 % 진행률은 픽션), `COMPLETED` → 학습 열기(클릭 시 `/papers/:id`), `FAILED`/`EXPIRED` → 실패 표시(둘 다 사용자에겐 '실패', FT-002 Story 3). COMPLETED가 아닌 행은 학습 진입 불가.
  - **R5 페이지네이션**: `paginate(filtered, page, 10)`.
  - **빈 상태**(`noResults`/전체 빈 서재): 목업 마크업 그대로.
- 상태 모델: `keyword: string`, `isGridView: boolean`, `page: number`, `uploadOpen: boolean`, `toast: string | null`, `profileMenuOpen: boolean`. 서버 상태는 전부 TanStack Query에 위임.

- [ ] **Step 4: 시각 대조 + 검증·커밋**

`npm run dev`로 목업과 나란히 대조(그리드·리스트 각각). `npm run typecheck && npm test && npm run build`.

```bash
git add fe/src/routes
git commit -m "[YMC-289] feat(fe): 서재 페이지 — 목록·검색·토글·페이지네이션·상태 폴링"
```

---

### Task 11: 업로드 Dialog 이식

**Files:**
- Create: `fe/src/routes/bookshelf/UploadDialog.tsx`
- Modify: `fe/src/routes/BookshelfPage.tsx` (연결)

**Interfaces:**
- Produces: `<UploadDialog open onClose={fn} onUploaded={fn} />` — 완료 시 `queryClient.invalidateQueries({ queryKey: ['papers'] })` 후 Toast.
- Consumes: `createPaper`/`uploadToS3`/`completeUpload`/`ApiError` (Task 3).

- [ ] **Step 1: 이식.** 목업의 Upload dialog 마크업 + 기존 `App.jsx`의 업로드 플로우(파일 선택/드래그 → `createPaper` → `uploadToS3`(XHR 진행률 — 진짜 %) → `completeUpload`)를 합친다. 상태 모델(기존 DESIGN.md D4 승계):

```ts
type UploadPhase = 'idle' | 'file-selected' | 'uploading';
// state: phase, selectedFile: File | null, uploadPct: number, dragOver: boolean, error: ApiError | Error | null
```

에러는 Dialog 안에 그대로 노출(spec §7): `409 DUPLICATE_FILENAME`은 계약 message, presigned 만료·S3 PUT 실패는 `uploadToS3`의 Error, `complete` 4xx는 ApiError. PDF만 허용(`accept="application/pdf"` + 타입 검사). 업로드 성공 → `onUploaded()` → Dialog 닫기 + Toast "등록되었습니다 — 분석이 시작됩니다".

- [ ] **Step 2: 수동 검증.** local 스택(BE + LocalStack) 기동 후: PDF 업로드 → 목록에 행 추가·"분석 중" 표시 → `infra/local`의 `publish-parse-result.sh <paperId> COMPLETED` → 행이 완료로 전환. 같은 파일 재업로드 → 409가 Dialog에 표시.

- [ ] **Step 3: 검증·커밋** — `npm run typecheck && npm test && npm run build`

```bash
git add fe/src/routes
git commit -m "[YMC-289] feat(fe): 업로드 다이얼로그 — presigned PUT 플로우 이식"
```

---

### Task 12: Study 페이지 — 레이아웃·뷰어·TOC·Night mode

**Files:**
- Modify: `fe/src/routes/StudyPage.tsx`
- Create: `fe/src/routes/study/PaperViewer.tsx`, `fe/src/routes/study/TocRail.tsx`, `fe/src/routes/study/useScrollSpy.ts`, `fe/src/routes/study/scrollSpy.ts`
- Test: `fe/src/routes/study/scrollSpy.test.ts`

**Interfaces:**
- Produces: `pickActiveHeading(visibleIds: string[], tocOrder: string[]): string | null` (순수), `<PaperViewer blocks activeSetter />`, `<TocRail toc activeId onJump />`. StudyPage 상태: `tocOpen`, `nightMode`, `splitPct`.
- Consumes: `getPaperContent`·`PaperContent` (Task 5), `PaperMarkdown` (Task 6), `PaperSheet` (Task 7), `getStatus` (Task 3).

- [ ] **Step 1: 실패하는 테스트** — `scrollSpy.test.ts`

```ts
import { expect, test } from 'vitest';
import { pickActiveHeading } from './scrollSpy';

const ORDER = ['block-1', 'block-3', 'block-6'];

test('보이는 heading 중 문서 순서상 첫 번째를 고른다', () => {
  expect(pickActiveHeading(['block-6', 'block-3'], ORDER)).toBe('block-3');
});
test('보이는 heading이 없으면 null', () => {
  expect(pickActiveHeading([], ORDER)).toBeNull();
});
test('toc에 없는 id는 무시한다', () => {
  expect(pickActiveHeading(['block-99'], ORDER)).toBeNull();
});
```

- [ ] **Step 2: 실패 확인 후 구현** — `scrollSpy.ts`

```ts
export function pickActiveHeading(visibleIds: string[], tocOrder: string[]): string | null {
  const visible = new Set(visibleIds);
  return tocOrder.find((id) => visible.has(id)) ?? null;
}
```

`useScrollSpy.ts` — IntersectionObserver로 heading 블록 가시성을 추적하고 `pickActiveHeading` 결과를 상태로:

```ts
import { useEffect, useState } from 'react';
import { pickActiveHeading } from './scrollSpy';

export function useScrollSpy(containerRef: React.RefObject<HTMLElement | null>, tocOrder: string[]) {
  const [activeId, setActiveId] = useState<string | null>(null);
  useEffect(() => {
    const container = containerRef.current;
    if (!container || tocOrder.length === 0) return;
    const visible = new Set<string>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          const id = (e.target as HTMLElement).dataset.blockId!;
          if (e.isIntersecting) visible.add(id); else visible.delete(id);
        }
        setActiveId(pickActiveHeading([...visible], tocOrder));
      },
      { root: container, rootMargin: '0px 0px -60% 0px' },
    );
    for (const id of tocOrder) {
      const el = container.querySelector(`[data-block-id="${id}"]`);
      if (el) observer.observe(el);
    }
    return () => observer.disconnect();
  }, [containerRef, tocOrder]);
  return activeId;
}
```

- [ ] **Step 3: StudyPage 조립.** `Paper Study Page.dc.html` R1–R5를 변환표대로 이식:
  - 진입 가드: `useQuery(['paper-status', paperId], () => getStatus(paperId))` — `COMPLETED`가 아니면 `/library`로 `Navigate` + Toast(라우터 state로 메시지 전달). 본문은 `useQuery(['paper-content', paperId], () => getPaperContent(paperId))`.
  - **PaperViewer**: `PaperSheet` 위에 `blocks.map((b) => <section key={b.id} data-block-id={b.id} id={b.id}><PaperMarkdown>{b.markdown}</PaperMarkdown></section>)`.
  - **TocRail**: `content.toc` 목록, `activeId` 강조(Navy Ink), 클릭 시 `document.getElementById(blockId)?.scrollIntoView({ behavior: 'smooth', block: 'start' })`, `tocOpen` 접기.
  - **스플리터**: pointerdown/move/up으로 `splitPct`(뷰어 폭 %) 조정, 30–75%로 clamp. 챗 패널 접기(`chatCollapsed`)는 Task 13.
  - **Night mode**: 토글 시 StudyPage 루트 요소에 `data-theme="night"` 속성 — 토큰 CSS가 스왑을 처리한다(`colors.css`의 `[data-theme="night"]` 확인됨). 상태는 `localStorage('pt-night')`에 유지.

- [ ] **Step 4: 시각 대조 + 검증·커밋.** 픽스처 논문으로 수식(인라인·블록)·그림·표 렌더, TOC 이동·스파이, night 토글 확인.

Run: `npm run typecheck && npm test && npm run build`

```bash
git add fe/src/routes
git commit -m "[YMC-289] feat(fe): 학습 페이지 — 뷰어·TOC·스플리터·Night mode"
```

---

### Task 13: AI 튜터 챗 패널

**Files:**
- Create: `fe/src/routes/study/TutorPanel.tsx`
- Modify: `fe/src/routes/StudyPage.tsx` (연결), `docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md` (§8 픽션 기록)
- Delete: `fe/src/chat/ChatPanel.jsx` (UI 이식 완료 후)

**Interfaces:**
- Produces: `<TutorPanel paperId pendingContext onContextConsumed collapsed onToggleCollapse />` — `pendingContext: { text: string } | null` (Task 14의 인라인 "AI 질문"이 주입).
- Consumes: `chatReducer`/`initialChatState`/`streamChatMessage` (Task 4), `PaperMarkdown` (Task 6), `TutorNotebook`/`NotebookSection`/`StudentMessage` (Task 7).

- [ ] **Step 1: 이식·조립.** 목업 R5 + 기존 `ChatPanel.jsx`의 wiring을 합친다:
  - `useReducer(chatReducer, initialChatState)`. 전송: `crypto.randomUUID()`로 `clientMessageId` 생성 → `dispatch({type:'send',…})` → `streamChatMessage({ paperId, sessionId, clientMessageId, content, signal, onEvent: dispatch 매핑 })`. 언마운트 시 AbortController abort(기존 ChatPanel 방식 유지).
  - assistant 메시지는 `<PaperMarkdown>`으로 렌더(스트리밍 delta 즉시 재렌더). GENERATING 중에는 목업의 점 3개 바운스 표시. user 메시지는 `StudentMessage`.
  - FAILED + `retryable` && `pending` 존재 → "다시 시도" 버튼: `dispatch({type:'send', clientMessageId: pending.clientMessageId, content: pending.content, resend: true})` 후 같은 id로 재스트림 (멱등 계약).
  - **컨텍스트 칩**(목업 `hasContext`): `pendingContext`가 있으면 입력창 위에 칩 + 제거 버튼. 전송 시 content 조립: `` `다음은 논문에서 선택한 구절이다:\n> ${context.text}\n\n${질문}` `` — 계약 `content`는 자유 텍스트이므로 FE 포맷팅으로 전달(BE 무변경). 전송 후 `onContextConsumed()`.
  - **Ask popup**(목업 "현재 챗 vs 새 챗")은 Task 14에서 열지만, "새 대화에서" 선택 시 `dispatch({type:'reset'})` 후 칩 주입이 되도록 여기서 reset 경로를 지원한다.
  - **대화 히스토리 패널(목업 `historyOpen`)은 이식하지 않는다.** 과거 세션 목록을 줄 계약(GET 메시지/세션 목록)이 없다 — 픽션 조정 2호. Step 3에서 spec §8에 기록.

- [ ] **Step 2: 수동 검증.** local 스택으로 질문 전송 → started/delta 스트리밍 → completed 수렴. 스트림 중단(BE 정지) → "다시 시도" 동작.

- [ ] **Step 3: spec §8 갱신** — `2026-07-31-fe-v1-redesign-design.md` §8에 추가:

```markdown
2. **챗 히스토리 패널**: 과거 세션·메시지 목록을 조회할 계약이 없다 → 이번 범위에서 패널 생략,
   현재 세션 대화만 표시. 계약 확정 시 복원 (Jira 백로그).
```

- [ ] **Step 4: 검증·커밋**

Run: `npm run typecheck && npm test && npm run build`

```bash
git add fe/src docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md
git commit -m "[YMC-289] feat(fe): AI 튜터 패널 — 스트리밍·컨텍스트 칩·재시도"
```

---

### Task 14: 인라인 액션 — 선택 툴바·번역·AI 질문

**Files:**
- Create: `fe/src/routes/study/useTextSelection.ts`, `fe/src/routes/study/selectionPosition.ts`, `fe/src/routes/study/SelectionLayer.tsx`, `fe/src/api/translate.ts`
- Test: `fe/src/routes/study/selectionPosition.test.ts`
- Modify: `fe/src/routes/StudyPage.tsx` (연결)

**Interfaces:**
- Produces: `computeToolbarPosition(sel: Rect, container: Rect, popup: {width,height}): {top,left}`, `useTextSelection(ref): { text, rect, clear } | null`, `<SelectionLayer viewerRef onAsk={(text)=>…} />`, `translateSelection(text): Promise<{translation: string}>`.
- Consumes: `TutorPanel`의 `pendingContext` (Task 13).

- [ ] **Step 1: 실패하는 테스트** — `selectionPosition.test.ts`

```ts
import { expect, test } from 'vitest';
import { computeToolbarPosition } from './selectionPosition';

const container = { top: 100, left: 200, right: 800, bottom: 900, width: 600, height: 800 } as DOMRect;
const popup = { width: 180, height: 40 };

test('선택 영역 아래 중앙에 배치한다 (컨테이너 상대 좌표)', () => {
  const sel = { top: 300, left: 400, right: 500, bottom: 320, width: 100, height: 20 } as DOMRect;
  expect(computeToolbarPosition(sel, container, popup)).toEqual({ top: 228, left: 160 });
  // top: sel.bottom - container.top + 8 = 228, left: sel 중앙(450) - container.left - popup/2 = 160
});

test('오른쪽 경계를 넘으면 안쪽으로 clamp', () => {
  const sel = { top: 300, left: 760, right: 795, bottom: 320, width: 35, height: 20 } as DOMRect;
  const pos = computeToolbarPosition(sel, container, popup);
  expect(pos.left).toBe(600 - 180 - 8); // container.width - popup.width - 여백 8
});

test('왼쪽 경계도 clamp', () => {
  const sel = { top: 300, left: 205, right: 215, bottom: 320, width: 10, height: 20 } as DOMRect;
  expect(computeToolbarPosition(sel, container, popup).left).toBe(8);
});
```

- [ ] **Step 2: 실패 확인 후 구현** — `selectionPosition.ts`

```ts
const MARGIN = 8;

export function computeToolbarPosition(
  sel: DOMRect,
  container: DOMRect,
  popup: { width: number; height: number },
): { top: number; left: number } {
  const top = sel.bottom - container.top + MARGIN;
  const center = sel.left + sel.width / 2 - container.left;
  const left = Math.min(Math.max(MARGIN, center - popup.width / 2), container.width - popup.width - MARGIN);
  return { top, left };
}
```

Run: `npx vitest run src/routes/study/selectionPosition.test.ts` → PASS

- [ ] **Step 3: 훅·레이어 구현.**
  - `useTextSelection(viewerRef)`: `document.selectionchange` + `mouseup`에서 `window.getSelection()` 검사 — 비어 있지 않고 range가 뷰어 컨테이너 내부일 때 `{ text: sel.toString(), rect: range.getBoundingClientRect(), clear() }` 반환. 뷰어 밖 클릭·`clear()` 시 null.
  - `translate.ts`:

```ts
// 인라인 번역 계약 미확정 — 목 응답 (spec §6). 계약 확정 시 이 함수만 실제 fetch로 교체.
export async function translateSelection(text: string): Promise<{ translation: string }> {
  await new Promise((r) => setTimeout(r, 600));
  return { translation: `(번역 결과 자리 — 계약 확정 전 목 응답)\n${text}` };
}
```

  - `SelectionLayer.tsx`: 목업의 Selection popup / Ask popup / Direct translation popup 마크업을 변환표대로 이식. 상태기계: `idle → toolbar → (translating → translated) | askChoice`. "번역하기" → `translateSelection` 호출, 결과 팝업(같은 위치), 닫기 → `clear()`로 원문 읽기 복귀(FT-006 Story 2). "AI에게 질문" → Ask popup("현재 대화에서" | "새 대화에서") → `onAsk(text, mode)` — StudyPage가 `mode === 'new'`면 챗 reset 후 `pendingContext` 주입, 챗 패널 열림·입력 포커스(FT-006 Story 4).

- [ ] **Step 4: 수동 검증 + 시각 대조.** 픽스처 본문에서 선택 → 툴바 위치·번역 팝업·질문 칩 흐름을 목업과 대조. 수식·그림 위 선택, 뷰어 경계 근처 선택도 확인.

- [ ] **Step 5: 검증·커밋**

Run: `npm run typecheck && npm test && npm run build`

```bash
git add fe/src
git commit -m "[YMC-289] feat(fe): 인라인 액션 — 선택 툴바·번역(목)·AI 질문 연결"
```

---

### Task 15: 레거시 정리 + 최종 검증

**Files:**
- Delete: `fe/src/App.jsx`, `fe/src/Landing.jsx` (Task 8 이후 죽은 코드), 남은 `.js`/`.jsx` 전수 확인
- Modify: `fe/DESIGN.md`(대체 고지), `fe/README.md`(실행법 갱신), spec §8(최종)

**Interfaces:** 없음 (정리·검증 태스크)

- [ ] **Step 1: 레거시 삭제.** `git grep -l "App.jsx\|Landing.jsx" fe/src`로 참조 없음을 확인 후 삭제. `find fe/src -name "*.js" -o -name "*.jsx"`가 비어야 한다(전부 ts/tsx). 비어 있으면 `tsconfig.json`의 `allowJs`를 `false`로.

- [ ] **Step 2: 문서 갱신.**
  - `fe/DESIGN.md` 최상단에: `> **대체됨** — 이 문서가 설명하는 임시 UI는 폐기되었다. 현행 설계: docs/superpowers/specs/2026-07-31-fe-v1-redesign-design.md`
  - `fe/README.md`: 설치(`npm i`)·실행(`npm run dev`, BE/LocalStack 필요)·테스트(`npm test`)·타입체크(`npm run typecheck`) 갱신. 픽스처 본문·번역 목이 계약 미확정 구간이라는 안내 포함.
  - spec §8에 구현 중 발견된 추가 픽션 조정이 있으면 전부 기록됐는지 확인.

- [ ] **Step 3: 전체 검증 (spec §10 그대로).**

1. `npm run typecheck && npm test && npm run build` 전부 통과.
2. 3개 페이지를 목업 .dc.html과 브라우저에서 나란히 대조.
3. 픽스처 논문: 인라인·블록 수식, 그림, 표 렌더 + TOC 스크롤 스파이.
4. E2E 수동: 로그인 → 업로드 → 분석 중(불확정 바) → `publish-parse-result.sh <paperId> COMPLETED` → 행 전환 → Study 진입 → 챗 질문 스트리밍 → 텍스트 선택 → 번역(목) → AI 질문 → 컨텍스트 칩 전송. 실패 경로: 같은 파일 재업로드(409), `publish-parse-result.sh <paperId> FAILED`(실패 행), 스트림 중단(재시도).
5. Night mode 토글, `prefers-reduced-motion`(macOS 손쉬운 사용 → 동작 줄이기) 확인.

- [ ] **Step 4: Commit**

```bash
git add -A fe docs
git commit -m "[YMC-289] chore(fe): 임시 UI 잔재 제거·문서 갱신"
```

---

## Self-Review 결과 (계획 작성 시 수행)

- **Spec coverage**: §1–§11 전 항목이 태스크에 매핑됨. §8 픽션 기록은 Task 10(진행률 바)·13(히스토리 패널)에서 갱신.
- **계약 대조**: PaperStatus enum 6값·ChatMessageStatus 3값·SSE 이벤트 5종은 openapi.yaml에서 그대로 옮겼다. 본문·인라인 번역은 계약이 없으므로 어댑터 격리(Task 5·14).
- **타입 일관성**: `PaperContent`/`PaperBlock`(Task 5) ↔ 소비처(Task 12), `ChatStreamEvent`(Task 4) ↔ TutorPanel(Task 13), `pendingContext`(Task 13) ↔ SelectionLayer(Task 14) 시그니처 일치 확인.
