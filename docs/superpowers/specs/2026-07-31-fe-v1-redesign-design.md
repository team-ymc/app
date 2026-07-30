# FE v1 재구축 — design/v1 이식 + 학습 뷰어·챗·인라인 기반

- Source: `project-docs/design/v1/` (목업 3종 + paper-teacher 디자인 시스템 번들)
- Features: FT-001(인증, 기존) · FT-002(서재) · FT-003(등록·분석, 기존 플로우 이식) · FT-004(학습 뷰어) · FT-006(인라인 액션) · FT-007(AI 튜터 채팅)
- 선행 문서: `fe/DESIGN.md` (BE 검증용 임시 UI — 본 작업으로 폐기·대체된다)
- Status: 승인됨 (2026-07-31, 설계 대화에서 섹션별 승인)

## 1. 목적과 범위

임시 UI를 폐기하고 `project-docs/design/v1`의 디자인으로 **진짜 FE**를 세운다.

**In scope**

- 기술 스택 확정: Vite + React + TypeScript SPA
- 디자인 시스템 이식 (토큰 CSS + 필요 컴포넌트)
- 3개 페이지 전부: Landing(`/`) · Bookshelf(`/library`) · Study(`/papers/:id`)
- 논문 본문 마크다운 렌더링 (수식·그림·표), AI 튜터 챗(SSE 스트리밍), 인라인 액션(번역·AI 질문)
- 기존 auth·chat·upload 로직의 TS 이식 (테스트 포함)

**Out of scope**

- 본문 조회·인라인 번역의 **계약 확정** — Jira 백로그로 관리, 코드에서는 어댑터로 격리 (§6)
- 전체 번역(FT-005), 위키(FT-008), 구조 맵(FT-009), 학습 기록(FT-010)
- 폰트 셀프호스팅 (CDN 유지, 후속)
- 하이라이트·노트 저장 등 post-MVP

## 2. 스택 결정

| 관심사 | 선택 | 근거 |
|---|---|---|
| 프레임워크 | Vite + React + **TypeScript** SPA | BE(Spring)·SSE 릴레이가 이미 있어 프론트 서버 런타임 불필요. 디자인 시스템 번들 자체가 React 컴포넌트. 기존 코드 이식 경로 최단. Next.js는 랜딩 SEO 외 이득 없이 배포·프록시 복잡도만 추가라 기각 |
| 라우팅 | react-router (SPA 모드) | `/` · `/library` · `/papers/:id` 3개 |
| 서버 상태 | TanStack Query | 목록 캐시, `PROCESSING` 행 status 폴링 |
| 마크다운 | react-markdown + remark-gfm + remark-math + **rehype-katex(KaTeX)** | 논문은 수식이 수백 개일 수 있어 KaTeX 속도 우선. MathJax는 커버리지가 넓지만 느려서 기각 — 실제 깨지는 수식이 관찰되면 macro 정의·부분 폴백으로 후속 대응 |
| 인라인 선택 | 브라우저 Selection API + 디자인 시스템 `SelectionToolbar` | react-markdown이 실제 DOM을 만들므로 범위·좌표 계산이 자연스러움. 별도 라이브러리 불필요 |
| 스타일 | 디자인 시스템 토큰 CSS + 컴포넌트별 CSS (Tailwind 없음) | 번들 구조와 1:1 |
| 아이콘 / 폰트 | Phosphor Icons(React) / Noto Serif KR·Pretendard CDN | 목업과 동일 소스 |
| 테스트 | vitest (유지) | 기존 테스트 이식 |

TS 전환: 새 코드는 전부 TS, 기존 auth·chat·upload 로직은 이식하면서 `.ts`로 전환. API 타입은 `contracts/frontend-backend/openapi.yaml` 기준으로 정의한다.

## 3. 디렉토리 구조

```
src/
  main.tsx                 진입점 (라우터 + QueryClient + 폰트/토큰 CSS)
  routes/
    LandingPage.tsx        /            월넛 랜딩 (비로그인)
    BookshelfPage.tsx      /library     서재 (목록·검색·업로드)
    StudyPage.tsx          /papers/:id  학습 (TOC 레일 + 뷰어 + 튜터 패널)
  design/
    tokens/*.css           번들 tokens/ 그대로 복사 — 수정 금지 (SSOT는 project-docs/design/v1)
    components/            Button, IconButton, Input, Dialog, Toast, GlobalNav,
                           PaperSheet, ArchivalFolio, TutorNotebook, StudentMessage,
                           TeachingAction, SelectionToolbar, PaperStackMark …
                           (이번 3페이지에 쓰이는 것만 이식 — YAGNI)
  markdown/
    PaperMarkdown.tsx      공용 렌더러 — 챗·뷰어가 공유
    paperContent.ts        본문 정규화 어댑터 (§6)
  api/                     기존 api.js·auth.js 이식 + openapi 기반 타입
  chat/                    기존 sseParser·chatStream·chatState 이식 (테스트 포함)
  upload/                  기존 presigned 업로드 플로우 이식 (Bookshelf의 Dialog로)
  fixtures/                샘플 논문 .md (인라인·블록 수식, 그림, 표 포함)
```

기존 `App.jsx`(임시 UI)와 인라인 스타일 일체는 폐기한다. `fe/DESIGN.md`는 본 문서로 대체를 명시한다.

## 4. 디자인 이식 원칙

- 목업(.dc.html)이 링크하는 **그 토큰 CSS를 그대로 복사**하고, 마크업·인라인 스타일을 1:1로 JSX 이식한다. 겉모습은 목업과 대조 가능한 수준으로 동일해야 한다 (임시 UI 때 검증된 방식: `sc-if` → `{cond && …}`, `sc-camel-on-click` → `onClick`, 스타일 객체 그대로).
- **목업 픽션은 지어내지 않는다.** 데이터가 뒷받침하지 않는 UI는 계약이 주는 정보 수준으로 조정하고, 조정 사항은 본 문서 §8에 기록한다.
- Night Study Mode는 토큰 스왑(`data-theme`)으로 구현, Study 페이지에서 토글.
- 모션은 조용하게(~200ms, darken-only hover), `prefers-reduced-motion` 존중.

## 5. 페이지 설계

### Landing `/`

목업 그대로: 월넛 top bar(PaperStackMark + 로그인) + 중앙 가치 선언문("논문을 이해하도록 가르치는 AI 리딩 튜터") + CTA. CTA·로그인은 기존 FT-001 소셜 인증 플로우로, 성공 시 `/library`. 로그인 상태로 진입 시 `/library` 리다이렉트.

### Bookshelf `/library`

GlobalNav(프로필 드롭다운) / 헤더 / 컨트롤(검색, 그리드·리스트 토글, 업로드) / 목록 / 페이지네이션 / 업로드 Dialog / Toast.

- 목록: `GET /api/papers`(계약 확정) + TanStack Query. `PROCESSING` 행만 2초 status 폴링.
- 파싱 진행률은 **불확정 애니메이션 바** — 계약에 progress 필드가 없다 (§8-1).
- 업로드: 기존 플로우 이식 — presigned PUT은 `Content-Type: application/pdf`를 **명시적으로** 싣는다(서명에 포함됨, 기존 D6). 업로드 진행률은 XHR `upload.onprogress`의 진짜 퍼센트.
- 검색·토글·페이지네이션은 클라이언트 사이드 (MVP의 목록 규모 전제).

### Study `/papers/:id`

GlobalNav / 접이식 TOC 레일 / 논문 뷰어(PaperSheet) / 드래그 리사이즈 스플리터 / AI 튜터 패널. `COMPLETED` 논문만 진입 허용 — 아니면 `/library`로 돌려보내고 Toast.

- **뷰어**: 본문을 블록 배열로 렌더(목업의 block 모델: heading/subheading/para/figure/equation/table), 각 블록에 `data-block-id`. TOC는 heading 블록에서 파생, 스크롤 스파이.
- **튜터 패널**: 기존 SSE 파이프 + TutorNotebook·StudentMessage 스타일. 스트리밍 delta를 마크다운으로 즉시 렌더. 인라인 "AI 질문"에서 넘어온 선택 텍스트는 입력창 위 **컨텍스트 칩**으로 표시, "현재 대화에 잇기 vs 새 대화" 선택 팝업 포함(목업 `askPopupVisible`). 대화 히스토리 패널(목업 `historyOpen`), 패널 접기 포함.
- **인라인 액션**(FT-006): 뷰어에서 텍스트 선택 → 선택 rect 옆 SelectionToolbar(번역/AI 질문). 번역 결과 팝업은 선택 영역 옆 표시, 닫으면 원문 읽기 복귀. AI 질문은 chat 계약이 확정돼 있어 **처음부터 실제 동작**.

## 6. 마크다운 렌더러와 계약 공백 격리

### PaperMarkdown (공용)

react-markdown + remark-gfm + remark-math + rehype-katex. 커스텀 컴포넌트: heading(앵커 id), `img`(아카이벌 폴리오 프레임 + 캡션 — 실제 첫 페이지/원본 이미지만, 생성 커버 아트 금지), `table`(디자인 시스템 표 스타일). 챗 말풍선과 뷰어 본문이 이 하나를 공유한다 — 챗 계약이 이미 `contentFormat: "markdown"`이므로.

### paperContent 어댑터 — 계약 공백의 유일한 접점

`DocumentParseResponse`의 본문 구조는 **미확정**(FT-004 블로커, Jira 백로그). FE는 기다리지 않는다:

- `getPaperContent(paperId)` — 지금은 `fixtures/`의 샘플 논문 .md 반환. 어댑터가 마크다운을 AST 최상위 노드 기준으로 **블록 배열로 정규화**해 뷰어에 공급한다.
- 계약이 확정되면(블록 JSON이든 통 markdown이든) **이 어댑터 내부만** 바뀌고 뷰어·TOC·인라인은 그대로다.
- `translateSelection(text)` — 인라인 번역 엔드포인트도 계약에 없다. 지금은 목 응답(지연 + 표시용 더미)으로 UI·플로우를 완성해 두고, 계약 확정 시 fetch만 교체한다.

미확정 계약을 추측으로 `project-docs/contracts/`에 채우지 않는다. 확정은 contracts PR로만.

## 7. 에러 처리

- 공통: 계약 `Error` 스키마(`code`+`message`) 단일 파서. 맥락 있는 곳은 인라인(업로드 Dialog, `FAILED` 행, 챗 패널), 맥락 없는 일시 오류만 Toast.
- 업로드: `409 DUPLICATE_FILENAME`, presigned 만료(403), S3 PUT 실패, `UPLOAD_NOT_FOUND`, 파싱 `FAILED`(+`error.code`) 전부 관찰 가능하게 유지.
- 챗 SSE: 중단·error 이벤트 시 해당 메시지 자리에 오류 + 재시도. "delta append, completed로 replace" 수렴 규칙 유지.
- 인증: 401 → refresh → 최종 실패 시 랜딩.
- 렌더러: KaTeX `throwOnError: false` (깨진 수식은 원문 노출, 앱은 계속), 이미지 실패 시 프레임 유지 + 파일명 대체.

## 8. 목업 픽션 조정 기록

1. **파싱 진행률 바**: 계약 `PaperStatusResponse`에 progress 없음 → 불확정 애니메이션 (기존 D2 승계).
2. (추가 발견 시 여기에 기록)

## 9. 테스트

- 이식: sseParser·chatStream·chatState·auth·api 테스트를 TS 전환과 함께 유지.
- 신규(TDD, 로직 계층): paperContent 어댑터(블록 분할·TOC 파생), 수식·표·이미지 픽스처 렌더 확인(testing-library), SelectionToolbar 위치 계산.
- 목업 1:1 마크업은 단위 테스트가 아니라 시각 대조 대상.

## 10. 검증 기준 (완료 선언 전 직접 수행)

1. 3개 페이지를 목업(.dc.html)과 브라우저에서 나란히 대조 — 겉모습 동일.
2. 픽스처 논문(인라인·블록 수식, 그림, 표)이 Study 뷰어에 정상 렌더, TOC 스크롤 스파이 동작.
3. E2E 수동: 로그인 → 업로드 → `PROCESSING` 불확정 바 → `publish-parse-result.sh` → `COMPLETED` → Study 진입 → 챗 스트리밍 수신 → 텍스트 선택 → 번역(목) 팝업 → AI 질문 → 컨텍스트 칩 전송.
4. Night Study Mode 토글, `prefers-reduced-motion`.
5. `tsc --noEmit` · `vite build` · `vitest run` 통과.

## 11. 리스크

- **계약 지연**: 본문·번역 계약이 늦어지면 픽스처·목 상태로 출고 불가. 어댑터 격리로 전환 비용은 최소화했으나 백로그 우선순위 관리 필요.
- **KaTeX 커버리지**: 희귀 LaTeX 매크로는 안 깨질 때까지가 아니라 깨지는 게 관찰될 때 대응(macro 정의·부분 폴백).
- **라이브러리 버전**: 구현 착수 시 context7으로 각 라이브러리(react-router, TanStack Query, react-markdown, remark/rehype 플러그인) 현재 버전 API를 확인하고 계획에 버전을 박는다 (CLAUDE.md 규칙).
- **폰트 CDN 의존**: 오프라인·CDN 장애 시 폴백 서체로 표시됨. 셀프호스팅은 후속.
