# FE 본문 실연동 (YMC-299) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 학습 페이지의 픽스처 본문(`sample-paper.md`)을 `GET /api/papers/{paperId}/content` 실호출로 교체한다.

**Architecture:** 교체 지점은 어댑터 하나 — `paperContent.ts`가 계약 응답을 뷰어 블록 모델로 변환한다. 제목·본문·수식·이미지는 markdown 문자열로 변환해 기존 `PaperMarkdown` 렌더러를 그대로 쓰고, 표(html)만 DOMPurify 정화 후 렌더하는 새 경로를 만든다. 뷰어의 `section[data-block-id]` 구조·TOC·스크롤 스파이·선택 레이어는 무수정.

**Tech Stack:** React 19 · TanStack Query · react-markdown(+KaTeX) · DOMPurify(신규 의존성) · vitest

**Spec:** `docs/superpowers/specs/2026-08-05-paper-content-integration-design.md` §4 (Stage 2)

## Global Constraints

- 계약: openapi.yaml `getPaperContent` 초안 — 응답 `{ paperId, title(null 가능), schemaVersion, blocks[], assets{} }`, block `{ blockId, globalOrder, label, headingLevel, sectionPath, content }`, content는 `format` 판별 union(text/formula/table/image), asset `{ url, mediaType, expiresAt }`.
- 표 html은 **렌더 전 반드시 DOMPurify 정화** (계약 요구).
- blockId는 파서 안정 id(`p0002-b0006`)를 DOM id·data-block-id로 그대로 쓴다 (기존 `block-{i}` 대체).
- 미지 label/format은 깨지지 않게 강등(para) + console.warn — enum 확장은 계약 PR부터.
- 409 PAPER_NOT_READY → /library 리다이렉트 + 토스트 (기존 status 분기 패턴).
- 이미지 expiresAt 경과 후 로드 실패 → content 1회 재조회 (무한 루프 가드).
- BE 호출은 `authFetch` 경유 (자동 Bearer + 401 재시도).
- 새 주석에 (YMC-…)·(spec §…) 인용 괄호 금지. 커밋 `[YMC-299] type(scope): subject`, attribution 금지.
- 테스트: `cd fe && npm test` (vitest), `npm run typecheck`, `npm run build`.
- 검증 데이터: BE 실행 + `infra/local/seed-paper-package.sh`(로컬 전용) + `publish-parse-result.sh`로 준비.

---

### Task 1: 계약 타입 + 실 fetch — api 층

**Files:**
- Modify: `fe/src/api/types.ts` (계약 타입 추가)
- Modify: `fe/src/api/papers.ts` (`fetchPaperContent` 추가)
- Test: `fe/src/api/papers.test.ts` (케이스 추가)

**Interfaces:**
- Produces: `fetchPaperContent(paperId): Promise<PaperContentResponse>` — 인증 fetch, 비정상 응답은 기존 `ApiError` throw.
- 타입: `PaperContentResponse`, `PaperContentBlockDto`, `PaperBlockContentDto`(format union), `PaperContentAssetDto` — Task 2의 어댑터가 소비.

- [ ] **Step 1: 실패하는 테스트 작성** — `papers.test.ts`에 추가 (기존 mockFetch 헬퍼 재사용):

```ts
it('fetchPaperContent: GET /content, 본문 응답을 그대로 돌려준다', async () => {
  mockFetch({ body: { paperId: 'p1', title: 'T', schemaVersion: 1, blocks: [], assets: {} } });
  const res = await fetchPaperContent('p1');
  expect(globalThis.fetch).toHaveBeenCalledWith('/api/papers/p1/content', expect.anything());
  expect(res.title).toBe('T');
});

it('fetchPaperContent: 409는 ApiError(code)로 던진다', async () => {
  mockFetch({ ok: false, status: 409, body: { code: 'PAPER_NOT_READY', message: '' } });
  await expect(fetchPaperContent('p1')).rejects.toMatchObject({ code: 'PAPER_NOT_READY' });
});
```

- [ ] **Step 2: 실패 확인** — Run: `cd fe && npx vitest run src/api/papers.test.ts` / Expected: FAIL (`fetchPaperContent` 미정의)

- [ ] **Step 3: 타입·fetch 구현**

`types.ts`에 추가:

```ts
// 계약 getPaperContent — content는 format으로 판별한다.
export type PaperBlockContentDto =
  | { format: 'text'; text: string }
  | { format: 'formula'; tex: string }
  | { format: 'table'; html: string }
  | { format: 'image'; assetKey: string };

export interface PaperContentBlockDto {
  blockId: string;
  globalOrder: number;
  label: string;               // 파서 분류 — 새 label이 올 수 있어 string으로 받는다
  headingLevel: number | null;
  sectionPath: string[];
  content: PaperBlockContentDto;
}

export interface PaperContentAssetDto { url: string; mediaType: string; expiresAt: string; }

export interface PaperContentResponse {
  paperId: string;
  title: string | null;
  schemaVersion: number;
  blocks: PaperContentBlockDto[];
  assets: Record<string, PaperContentAssetDto>;
}
```

`papers.ts`에 추가:

```ts
// 파싱된 논문 본문 조회 (blocks는 globalOrder 오름차순으로 온다).
export async function fetchPaperContent(paperId: string): Promise<PaperContentResponse> {
  const res = await authFetch(`/api/papers/${paperId}/content`);
  if (!res.ok) throw await apiError(res);
  return res.json();
}
```

- [ ] **Step 4: 통과 확인** — Run: `npx vitest run src/api/papers.test.ts` / Expected: PASS
- [ ] **Step 5: (커밋은 체크포인트에서 — 워킹트리 유지)**

---

### Task 2: 어댑터 재작성 — 계약 → 뷰어 블록 모델

**Files:**
- Rewrite: `fe/src/markdown/paperContent.ts`
- Delete: `fe/src/fixtures/sample-paper.md`
- Rewrite: `fe/src/markdown/paperContent.test.ts` (기존 remark 정규화 테스트 대체)

**Interfaces:**
- Consumes: `fetchPaperContent`, `PaperContentResponse` (Task 1)
- Produces (뷰어·TOC가 소비 — 기존 이름 유지):
  - `interface PaperBlock { id: string; type: BlockType; markdown?: string; tableHtml?: string; headingText?: string; headingLevel?: number }` — `markdown`이 optional로 바뀌고 `tableHtml` 추가 (표 전용)
  - `interface TocEntry { blockId: string; text: string; level: number }` (불변)
  - `interface PaperContent { title: string | null; blocks: PaperBlock[]; toc: TocEntry[]; assetExpiresAt: string | null }` — title·assetExpiresAt 추가
  - `getPaperContent(paperId): Promise<PaperContent>` (시그니처 불변 — StudyPage 무수정 호환)
  - `adaptPaperContent(res: PaperContentResponse): PaperContent` (테스트용 export)

- [ ] **Step 1: 실패하는 테스트 작성** — 계약 예시 payload → 매핑 검증. 커버: doc_title→heading(`# ` 프리픽스), paragraph_title level 3→subheading, text 인라인 `$…$` 보존, formula→`$$` 블록, table→tableHtml(markdown 없음), image/chart→`![](presignedUrl)`, assets에 없는 assetKey→other 강등+warn, 미지 format→para 강등+warn, toc는 level≥2 heading 계열만, title passthrough, assetExpiresAt = assets 중 최소 expiresAt.

```ts
import { describe, it, expect, vi } from 'vitest';
import { adaptPaperContent } from './paperContent';
import type { PaperContentResponse } from '../api/types';

function res(partial: Partial<PaperContentResponse>): PaperContentResponse {
  return { paperId: 'p1', title: null, schemaVersion: 1, blocks: [], assets: {}, ...partial };
}

describe('adaptPaperContent', () => {
  it('제목 계열은 headingLevel대로 #을 붙이고 toc는 level 2 이상만 담는다', () => {
    const out = adaptPaperContent(res({
      title: 'Fixture',
      blocks: [
        { blockId: 'b0', globalOrder: 0, label: 'doc_title', headingLevel: 1, sectionPath: [], content: { format: 'text', text: 'Fixture' } },
        { blockId: 'b1', globalOrder: 1, label: 'paragraph_title', headingLevel: 2, sectionPath: [], content: { format: 'text', text: 'Intro' } },
        { blockId: 'b2', globalOrder: 2, label: 'paragraph_title', headingLevel: 3, sectionPath: [], content: { format: 'text', text: 'Detail' } },
      ],
    }));
    expect(out.title).toBe('Fixture');
    expect(out.blocks[0]).toMatchObject({ id: 'b0', type: 'heading', markdown: '# Fixture' });
    expect(out.blocks[2]).toMatchObject({ type: 'subheading', markdown: '### Detail' });
    expect(out.toc).toEqual([
      { blockId: 'b1', text: 'Intro', level: 2 },
      { blockId: 'b2', text: 'Detail', level: 3 },
    ]);
  });

  it('수식은 $$ 블록으로, 표는 tableHtml로, 이미지는 presigned URL 마크다운으로 변환한다', () => {
    const out = adaptPaperContent(res({
      blocks: [
        { blockId: 'f0', globalOrder: 0, label: 'display_formula', headingLevel: null, sectionPath: [], content: { format: 'formula', tex: 'E=mc^2' } },
        { blockId: 't0', globalOrder: 1, label: 'table', headingLevel: null, sectionPath: [], content: { format: 'table', html: '<table><tr><td>x</td></tr></table>' } },
        { blockId: 'i0', globalOrder: 2, label: 'image', headingLevel: null, sectionPath: [], content: { format: 'image', assetKey: 'image_0' } },
      ],
      assets: { image_0: { url: 'https://s3/img.jpg?sig=1', mediaType: 'image/jpeg', expiresAt: '2026-08-05T00:00:00Z' } },
    }));
    expect(out.blocks[0]).toMatchObject({ type: 'equation', markdown: '$$\nE=mc^2\n$$' });
    expect(out.blocks[1]).toMatchObject({ type: 'table', tableHtml: '<table><tr><td>x</td></tr></table>' });
    expect(out.blocks[1].markdown).toBeUndefined();
    expect(out.blocks[2]).toMatchObject({ type: 'figure', markdown: '![](https://s3/img.jpg?sig=1)' });
    expect(out.assetExpiresAt).toBe('2026-08-05T00:00:00Z');
  });

  it('레지스트리에 없는 assetKey·미지 format은 깨지지 않게 강등하고 경고한다', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const out = adaptPaperContent(res({
      blocks: [
        { blockId: 'x0', globalOrder: 0, label: 'image', headingLevel: null, sectionPath: [], content: { format: 'image', assetKey: 'missing' } },
        { blockId: 'x1', globalOrder: 1, label: 'text', headingLevel: null, sectionPath: [], content: { format: 'mystery', text: 'raw' } as never },
      ],
    }));
    expect(out.blocks[0].type).toBe('other');
    expect(out.blocks[1].type).toBe('para');
    expect(warn).toHaveBeenCalledTimes(2);
    warn.mockRestore();
  });
});
```

- [ ] **Step 2: 실패 확인** — Run: `npx vitest run src/markdown/paperContent.test.ts` / Expected: FAIL
- [ ] **Step 3: 어댑터 전면 재작성** (`paperContent.ts` 전체 교체 — remark/unified import·`parsePaperMarkdown`·픽스처 import 제거):

```ts
// 본문 계약(getPaperContent) 응답을 뷰어 블록 모델로 정규화하는 유일한 접점.
// 제목·본문·수식·이미지는 markdown 문자열로 변환해 기존 PaperMarkdown 렌더러를 재사용하고,
// 표는 html 그대로 넘겨 렌더 측에서 정화한다.
import { fetchPaperContent } from '../api/papers';
import type { PaperContentBlockDto, PaperContentResponse } from '../api/types';

export type BlockType = 'heading' | 'subheading' | 'para' | 'figure' | 'equation' | 'table' | 'other';

export interface PaperBlock {
  id: string;
  type: BlockType;
  markdown?: string;
  tableHtml?: string;
  headingText?: string;
  headingLevel?: number;
}

export interface TocEntry { blockId: string; text: string; level: number; }

export interface PaperContent {
  title: string | null;
  blocks: PaperBlock[];
  toc: TocEntry[];
  /** 이미지 presigned URL 중 가장 이른 만료 시각. asset이 없으면 null. */
  assetExpiresAt: string | null;
}

export async function getPaperContent(paperId: string): Promise<PaperContent> {
  return adaptPaperContent(await fetchPaperContent(paperId));
}

export function adaptPaperContent(res: PaperContentResponse): PaperContent {
  const blocks = res.blocks.map((b) => adaptBlock(b, res));
  const toc = blocks
    .filter((b) => (b.type === 'heading' || b.type === 'subheading') && (b.headingLevel ?? 0) >= 2)
    .map((b) => ({ blockId: b.id, text: b.headingText!, level: b.headingLevel! }));
  const expiries = Object.values(res.assets).map((a) => a.expiresAt).sort();
  return { title: res.title, blocks, toc, assetExpiresAt: expiries[0] ?? null };
}

function adaptBlock(b: PaperContentBlockDto, res: PaperContentResponse): PaperBlock {
  const c = b.content;
  switch (c.format) {
    case 'text': {
      if (b.label === 'doc_title' || b.label === 'paragraph_title') {
        const level = b.headingLevel ?? 1;
        return {
          id: b.blockId,
          type: level <= 2 ? 'heading' : 'subheading',
          markdown: `${'#'.repeat(Math.min(level, 6))} ${c.text}`,
          headingText: c.text,
          headingLevel: level,
        };
      }
      return { id: b.blockId, type: 'para', markdown: c.text };
    }
    case 'formula':
      return { id: b.blockId, type: 'equation', markdown: `$$\n${c.tex}\n$$` };
    case 'table':
      return { id: b.blockId, type: 'table', tableHtml: c.html };
    case 'image': {
      const asset = res.assets[c.assetKey];
      if (!asset) {
        console.warn(`assets에 없는 assetKey — 블록을 건너뜀: ${c.assetKey} (${b.blockId})`);
        return { id: b.blockId, type: 'other', markdown: '' };
      }
      return { id: b.blockId, type: 'figure', markdown: `![](${asset.url})` };
    }
    default: {
      console.warn(`알 수 없는 content format — para로 강등: ${(c as { format: string }).format} (${b.blockId})`);
      const text = (c as { text?: string }).text ?? '';
      return { id: b.blockId, type: 'para', markdown: text };
    }
  }
}
```

`fe/src/fixtures/sample-paper.md` 삭제. (fixtures/ 디렉토리가 비면 함께 삭제)

- [ ] **Step 4: 통과 확인 + typecheck** — Run: `npx vitest run src/markdown/paperContent.test.ts && npm run typecheck` / Expected: PASS. (typecheck에서 `parsePaperMarkdown` 참조 잔재가 드러나면 그 지점은 Task 3·4에서 수정 대상인지 확인 — 어댑터 밖 참조는 이 시점엔 없어야 정상)

---

### Task 3: 표 렌더 경로 — DOMPurify 정화

**Files:**
- Create: `fe/src/markdown/SanitizedHtmlTable.tsx`
- Modify: `fe/src/routes/study/PaperViewer.tsx` (table 분기)
- Modify: `fe/package.json` (dompurify 추가)
- Test: `fe/src/markdown/SanitizedHtmlTable.test.tsx`

**Interfaces:**
- Consumes: `PaperBlock.tableHtml` (Task 2)
- Produces: `<SanitizedHtmlTable html={string} />` — 정화된 html을 기존 `.pt-markdown .pt-table-scroll` 스타일로 렌더

- [ ] **Step 1: 의존성 추가** — Run: `cd fe && npm install dompurify` (v3+는 타입 내장)
- [ ] **Step 2: 실패하는 테스트 작성**

```tsx
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { SanitizedHtmlTable } from './SanitizedHtmlTable';

describe('SanitizedHtmlTable', () => {
  it('표 마크업은 그대로 렌더한다', () => {
    const { container } = render(
      <SanitizedHtmlTable html="<table><tr><td>BLEU</td></tr></table>" />,
    );
    expect(container.querySelector('table')).not.toBeNull();
    expect(container.textContent).toContain('BLEU');
  });

  it('script·이벤트 핸들러는 제거한다', () => {
    const { container } = render(
      <SanitizedHtmlTable html={'<table><tr><td onmouseover="alert(1)">x</td></tr></table><script>alert(2)</script>'} />,
    );
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('td')?.getAttribute('onmouseover')).toBeNull();
  });
});
```

- [ ] **Step 3: 실패 확인** — Run: `npx vitest run src/markdown/SanitizedHtmlTable.test.tsx` / Expected: FAIL
- [ ] **Step 4: 컴포넌트 + 뷰어 분기 구현**

`SanitizedHtmlTable.tsx`:

```tsx
// 파서가 추출한 표 html을 정화해 렌더한다 — 계약이 렌더 전 정화를 요구한다.
import { useMemo } from 'react';
import DOMPurify from 'dompurify';
import './markdown.css';

export function SanitizedHtmlTable({ html }: { html: string }) {
  const clean = useMemo(() => DOMPurify.sanitize(html), [html]);
  return (
    <div className="pt-markdown">
      <div className="pt-table-scroll" dangerouslySetInnerHTML={{ __html: clean }} />
    </div>
  );
}
```

`PaperViewer.tsx`의 블록 렌더만 교체 (section 구조·data-block-id 불변):

```tsx
{blocks.map((b) => (
  <section key={b.id} data-block-id={b.id} id={b.id} style={{ scrollMarginTop: '24px' }}>
    {b.type === 'table' && b.tableHtml != null ? (
      <SanitizedHtmlTable html={b.tableHtml} />
    ) : (
      <PaperMarkdown>{b.markdown ?? ''}</PaperMarkdown>
    )}
  </section>
))}
```

- [ ] **Step 5: 통과 확인** — Run: `npx vitest run src/markdown/SanitizedHtmlTable.test.tsx && npm run test` / Expected: PASS (전체 회귀 포함)

---

### Task 4: StudyPage 통합 — title·로딩/에러 분기·이미지 만료 재조회

**Files:**
- Modify: `fe/src/routes/StudyPage.tsx`
- Modify: `fe/src/markdown/PaperMarkdown.tsx` (optional `onImageError` prop)
- Modify: `fe/src/routes/study/PaperViewer.tsx` (prop 전달)

**Interfaces:**
- Consumes: `PaperContent.title/assetExpiresAt` (Task 2)
- Produces: `PaperMarkdown({ children, onImageError? })`, `PaperViewer({ blocks, containerRef, onImageError? })`

- [ ] **Step 1: title 교체** — `StudyPageContent`의 titleText를:

```tsx
const titleText =
  contentQuery.data?.title
  ?? blocks.find((b) => b.type === 'heading')?.headingText
  ?? 'Paper Teacher';
```

(주석 "문서 제목 필드가 계약에 없다 …"는 삭제 — 이제 있다)

- [ ] **Step 2: 로딩·에러 분기 추가** — `StudyPageContent` 상단에 (statusQuery 분기와 같은 스타일):

```tsx
if (contentQuery.isPending) {
  return (
    <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
      본문을 불러오는 중…
    </div>
  );
}
if (contentQuery.isError) {
  // 진입 게이트(status COMPLETED)를 통과했는데 409면 적재 지연 등 일시 상태 — 서재로 안내
  if (contentQuery.error instanceof ApiError && contentQuery.error.code === 'PAPER_NOT_READY') {
    return <Navigate to="/library" replace state={{ toast: '본문 준비 중입니다. 잠시 후 다시 열어주세요' }} />;
  }
  return (
    <div style={{ padding: 48, textAlign: 'center', fontFamily: 'var(--font-sans)', color: 'var(--color-text-muted)' }}>
      본문을 불러오지 못했습니다{' '}
      <button onClick={() => contentQuery.refetch()} style={{ marginLeft: 8 }}>다시 시도</button>
    </div>
  );
}
```

주의: 이 분기는 hooks 규칙상 `useState`·`useRef`·`useMemo` 선언 **뒤**, JSX return 앞에 둔다.

- [ ] **Step 3: 이미지 만료 재조회** — `PaperMarkdown`의 img `onError`에서 optional 콜백 호출:

```tsx
export function PaperMarkdown({ children, onImageError }: { children: string; onImageError?: () => void }) {
  // …img 컴포넌트의 onError를:
  onError={(e) => { e.currentTarget.classList.add('pt-figure-broken'); onImageError?.(); }}
```

`PaperViewer`는 `onImageError`를 그대로 `PaperMarkdown`에 전달. `StudyPageContent`에서:

```tsx
const expiredRefetched = useRef(false);
function handleImageError() {
  const expiresAt = contentQuery.data?.assetExpiresAt;
  if (!expiresAt || expiredRefetched.current) return;      // 재조회는 1회만 — 무한 루프 방지
  if (Date.now() > Date.parse(expiresAt)) {
    expiredRefetched.current = true;
    contentQuery.refetch();
  }
}
// <PaperViewer blocks={blocks} containerRef={viewerRef} onImageError={handleImageError} />
```

- [ ] **Step 4: 전체 검증** — Run: `npm run test && npm run typecheck && npm run build` / Expected: 전부 통과

---

### Task 5: 정리 + 실환경 검증

**Files:**
- Modify: `fe/package.json` (`unified`·`remark-parse` 의존성 제거 — 어댑터 재작성 후 직접 사용처 없음. `remark-gfm`·`remark-math`는 PaperMarkdown이 계속 씀)
- Verify: 픽스처·`parsePaperMarkdown` 참조 잔재 0건

- [ ] **Step 1: 의존성·잔재 정리** — Run: `npm uninstall unified remark-parse` 후 `grep -rn "parsePaperMarkdown\|sample-paper" fe/src` → 0건, `npm run test && npm run build` green
- [ ] **Step 2: 실환경 E2E** (Stage 2 완료 기준 — spec §5):

```bash
cd infra/local && ./up.sh
cd app/be && ./gradlew bootRun          # 별도 터미널
cd app/fe && npm run dev                # 별도 터미널
# 브라우저: 로그인 → 서재 → PDF 업로드 → PROCESSING
./seed-paper-package.sh <paperId> <패키지 경로>       # 로컬 전용 스크립트
./publish-parse-result.sh <paperId> COMPLETED papers/<paperId>/manifest.json
# 서재에서 COMPLETED 전환 확인 → 학습 페이지 진입
```

브라우저에서 확인: 실제 본문 렌더(제목=response.title), 수식 KaTeX, 표 렌더(정화), 이미지 표시, TOC 이동·스크롤 스파이, 텍스트 선택→AI 질문 칩, Night mode. 픽스처 파일이 repo에 없음.

- [ ] **Step 3: 후속 기록** — `getPaperContent` 계약 확정 PR(project-docs)은 이 검증 통과 후 별도로.
