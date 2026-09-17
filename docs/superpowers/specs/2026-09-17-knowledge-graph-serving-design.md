# 지식 그래프 viz.html 앱 서빙 설계

- 날짜: 2026-09-17
- 티켓: YMC-394
- 범위: BE(`app/be`) + FE(`app/fe`) + project-docs(계약·FT-009·아트보드). AI·infra 변경 없음.
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml` 0.6.0 → **0.7.0**. `contracts/backend-ai/sqs/messaging.yml` 0.3.0은 변경 없음. 별도 project-docs PR 선행.
- 선행: YMC-372(ai#25, 컴파일 워커가 `knowledge-bundle/viz.html` 생성), YMC-389(app#67, compile_status·번역 사이드카 적재), YMC-353(학습 아트보드에 `지식 그래프` 버튼)

## 1. 배경과 목표

컴파일 워커는 논문마다 구조 맵·섹션 리더·번역 토글이 내장된 단독 HTML(`knowledge-bundle/viz.html`)을 만들고 중앙 manifest의 `artifacts.knowledge_bundle_viz`에 정확한 S3 키를 적는다. BE·FE는 아직 이 산출물을 소비하지 않는다.

이 설계는 학습 화면 상단 바의 `지식 그래프` 버튼으로 앱 안 새 화면을 열고, AI가 만든 viz.html을 그대로 띄우게 한다. FE는 구조 맵을 직접 그리지 않는다.

viz.html의 성질(조사 결과):
- 논문 데이터(트리·본문·이미지)를 `<script id="bundle-data" type="application/json">`으로 내장한다. 패키지 자산을 추가로 요청하지 않는다.
- 외부 요청은 jsdelivr(D3·marked·DOMPurify·KaTeX)와 Google Fonts뿐이다.
- localStorage는 번역 모드(`ymc-viz-translation-mode`) 저장에만 쓴다. `window.parent`·postMessage·location 조작은 없다.
- 워커가 객체에 `Content-Type: text/html; charset=utf-8`을 붙여 올린다.

## 2. 결정

| 항목 | 결정 | 근거 |
|---|---|---|
| 서빙 방식 | BE가 소유권·준비 상태를 검증하고 S3 presigned GET URL을 발급한다. FE는 그 URL을 iframe `src`로 넣는다 | `/download`·이미지 자산과 같은 패턴이라 BE 코드가 가장 적고 HTML 본문이 BE·ALB를 지나지 않는다. URL 유출 창(만료 10분)은 PDF 다운로드에서 이미 수용한 수준이다. BE 프록시(srcdoc)와 CloudFront signed URL은 기각 |
| 키 저장 | `document.knowledge_graph_key`에 viz.html의 S3 키만 저장. 컴파일 COMPLETED 반영 시 manifest에서 읽는다 | `/status` READY 판정이 DB만 보고 끝나고 요청 경로에 S3 읽기가 없다. `manifest_key`는 지금 소비자가 없어 저장하지 않는다. FT-008 등 산출물을 요청 때 찾는 소비자가 생기면 그때 붙인다 |
| 준비 신호 | `/status`에 `knowledgeGraphStatus`(PENDING / READY / FAILED / null) 추가. compile_status가 null·REQUESTED → PENDING, COMPLETED이고 키 있음 → READY, 그 외 → FAILED. Document 연결 전(UPLOAD_PENDING·EXPIRED)은 null | translationStatus와 같은 모양이라 FE 폴링 로직을 그대로 확장한다. 한국어 논문은 번역이 NOT_APPLICABLE이어도 그래프는 있으므로 별도 필드가 필요하다 |
| COMPLETED인데 키 없음 | FAILED로 응답. compile_status는 COMPLETED 유지 | dev에서 SQL로 COMPLETED 처리한 옛 문서와 manifest에 artifact가 빠진 경우 모두 그래프가 없다. 번역은 유효하므로 컴파일 상태를 바꾸지 않는다. `translationStatus` READY와 `knowledgeGraphStatus` FAILED가 함께 나오는 것은 정상 상태다 |
| artifact 누락 | manifest에 `knowledge_bundle_viz`가 없거나 key가 비어 있으면 WARN만 남기고 키 null로 COMPLETED 종결. 계약 위반(예외·DLQ)으로 다루지 않는다 | 번역 사이드카 누락과 같은 정책이다. 예외로 올리면 재전달 끝에 DLQ로 가고 Document는 REQUESTED에 머물러 번역까지 영원히 준비 중이 된다 |
| 종결 상태 불변 | compile_status가 COMPLETED·FAILED가 된 뒤에는 `knowledge_graph_key`를 채우는 경로를 두지 않는다. 결과 메시지 재전달은 기존대로 무시한다 | 재전달은 첫 처리가 이미 키를 저장했으므로 보정할 것이 없고, 첫 처리에서 artifact가 빠진 경우는 재컴파일 경로가 없어 보정 경로가 있어도 쓸 데가 없다. 옛 문서는 FAILED로 남는다 |
| manifest 두 번 읽기 | 결과 처리에서 번역 사이드카용과 viz 키용으로 manifest를 두 번 읽는다 | 두 읽기 사이에 manifest가 바뀌려면 같은 문서의 재파싱이 겹쳐야 하는데 BE에 재파싱 발행 경로가 없다(체크섬 중복 제거, 컴파일 실패 자동 재요청 없음). 재파싱(YMC-354)을 만들 때 한 번 읽기로 바꿀지 재검토한다 |
| 라우트 | 새 라우트 `/papers/:paperId/graph`. 학습 화면과 상단 바를 공유하는 별도 페이지 | 뒤로 가기·URL 공유·새로고침이 자연스럽고 아트보드 초안과 일치한다. 본문 ↔ 그래프 전환 시 그래프 상태가 초기화되는 것은 수용 |
| 상단 바 | 두 화면 모두 `본문 \| 지식 그래프` 쌍을 두고 현재 화면을 눌린 상태로 표시. `번역`·야간 모드는 학습 화면에만 | viz.html이 자체 번역 토글과 라이트 테마를 갖는다. 학습 아트보드도 쌍으로 맞춘다 |
| 409 코드 | `KNOWLEDGE_GRAPH_NOT_READY` 하나로 PENDING·FAILED·키 없음을 모두 덮는다 | 사용자 안내 문구는 `/status`가 결정한다. 이 에러는 방어선일 뿐이다 |
| iframe | `sandbox="allow-scripts allow-same-origin"` | 문서가 S3 오리진에서 로드되므로 앱 오리진의 쿠키·토큰·DOM과 격리된다. 허용되는 것은 jsdelivr·Google Fonts에서 받는 스크립트·폰트(CDN 공급망 신뢰)와 S3 오리진의 localStorage다. viz.html의 번역 모드 저장값은 같은 S3 오리진의 모든 논문이 공유하며 의도된 동작이다. `allow-same-origin`을 빼면 localStorage가 막혀 번역 모드를 기억하지 못한다. 폼 제출·팝업·상위 탐색은 막힌다. iframe 안 로드 실패는 cross-origin이라 FE가 감지할 수 없다 |
| 스키마 반영 | Flyway가 아직 app에 없으므로 기존 방식: `be/docs/db/document.sql` 갱신 + 주석의 ALTER를 dev에 수동 반영 | ADR-009는 Accepted이나 app 도입은 별도 티켓. 도입되면 그 마이그레이션에 포함한다 |

제외: 위키 Markdown 화면(FT-008), 선행지식 하이라이트, 컴파일 재요청 UI, `document-package.yml`(AI 담당자 결정으로 public repo에 두지 않음 — BE가 읽는 manifest 필드는 FT-009에서 ai 저장소 문서를 가리킨다), viz.html 야간 모드, presigned URL 만료 뒤 iframe 갱신(로드가 끝난 문서는 만료의 영향을 받지 않는다. 발급 시점에 `AssetUrlCache` 여유로 최소 1분이 남아 있어 546KB 로드에 충분하다), viz.html의 CDN 스크립트 SRI·번들링(AI 담당 영역).

## 3. 계약 변경 (project-docs)

### 3.1 `contracts/frontend-backend/openapi.yaml` 0.7.0

- 0.7.0은 #62(0.6.0, `translationStatus`, 2026-09-17 머지됨) 위에 얹는다. project-docs 브랜치는 origin/main 기준으로 만든다.
- 새 스키마 `KnowledgeGraphStatus`: `PENDING` / `READY` / `FAILED`. 설명에 다음을 적는다.
  - 파싱 `status`·`translationStatus`와 별개다. 컴파일이 만드는 지식 그래프(viz.html)의 준비 상태다.
  - PENDING: 컴파일 전이거나 진행 중. READY: viz.html을 열 수 있음. FAILED: 컴파일 실패 또는 산출물 없음, 자동 재시도 없음.
  - FE는 PENDING일 때만 폴링한다. 서재에는 표시하지 않는다.
- `PaperStatusResponse`에 `knowledgeGraphStatus` 필수 필드 추가. `type: [string, "null"]`(enum은 `$ref`)로 두고 "Document 연결 전(UPLOAD_PENDING·EXPIRED)에는 null. 컴파일 상태를 판단할 근거가 아직 없다는 뜻이며 FE는 폴링하지 않는다"를 적는다.
- 새 경로 `GET /api/papers/{paperId}/knowledge-graph` (operationId `getKnowledgeGraphView`, tag `papers`):
  - 설명: 컴파일이 만든 지식 그래프 HTML을 여는 presigned GET URL을 발급한다. BE는 소유권과 준비 상태만 검증하고 HTML 본문은 S3에서 직접 받는다. FE는 이 URL을 iframe `src`로 쓴다. 만료 전에 로드가 끝나면 이후 만료는 표시에 영향이 없다. 다시 진입하면 새로 호출한다.
  - 200 `KnowledgeGraphView { url: string(uri), expiresAt: date-time }`. `PaperDownload`와 같은 꼴이되 Content-Disposition을 싣지 않아 브라우저가 인라인으로 렌더한다.
  - 401 `UNAUTHORIZED` · 403 `FORBIDDEN` · 404 `PAPER_NOT_FOUND` · 409 `KNOWLEDGE_GRAPH_NOT_READY`(`knowledgeGraphStatus`가 READY가 아님).

### 3.2 `features/FT-009-구조-맵.md`

TBD 틀을 채운다. Summary·Status(In Progress)·Tracking(YMC-394)·Depends on(FT-003, FT-004 Story 1)을 적고, Userflow References는 deprecated이므로 아트보드를 가리킨다.

- Story 1(USER, MVP). 사용자는 학습 화면 상단 바에서 지식 그래프 화면으로 오갈 수 있다.
  - 상단 바의 `본문 | 지식 그래프` 쌍은 현재 화면을 눌린 상태로 표시한다.
  - `지식 그래프`는 `knowledgeGraphStatus`가 READY일 때만 활성이다. PENDING 툴팁 "지식 그래프를 준비하고 있습니다", FAILED 툴팁 "지식 그래프를 준비하지 못했습니다".
  - 지식 그래프 화면은 상단 바 아래 전체를 viz.html iframe으로 채운다. 준비되지 않은 상태로 URL 진입하면 같은 문구와 `본문으로` 링크를 보여준다. URL 발급에 실패하면 "지식 그래프를 불러오지 못했습니다"와 `다시 시도`.
- Story 2(SYSTEM, MVP). 시스템은 컴파일이 만든 viz.html을 소유자에게만 제공한다.
  - 컴파일 COMPLETED 반영 시 manifest `artifacts.knowledge_bundle_viz.key`를 저장한다. 없으면 저장하지 않고 WARN.
  - `/status`가 `knowledgeGraphStatus`를 계산해 준다.
  - `GET /api/papers/{paperId}/knowledge-graph`는 소유자·READY일 때만 presigned GET URL을 발급한다.
- Done Criteria: 위 AC + BE·FE 테스트 green + dev에서 실논문으로 버튼 활성 전환과 iframe 렌더 확인.
- Open Questions에 Resolved로 적는다: BE가 읽는 manifest 필드는 ai 저장소 `docs/S3_BUCKET_STRUCTURE_KO.md`(`knowledge_bundle_viz`)가 정의한다. project-docs에 패키지 스키마 사본을 두지 않는다.
- `feature-spec.md` Registry: FT-009 Status In Progress, Tracking YMC-394.

### 3.3 `design/v2`

- `Paper Knowledge Graph Page.dc.html`(초안)을 커밋하고 README의 화면 표에 등록한다. iframe이 가리키는 `knowledge-graph/ymc372/viz.html`(546KB, YMC-372 산출물 사본)도 함께 커밋한다. 옛 `knowledge-graph/0.0v3/`는 자체 상단 바가 있는 구 디자인이라 더 이상 참조하지 않는다.
- `Paper Study Page.dc.html` 상단 바: `지식 그래프` 단일 링크를 `본문(눌림) | 지식 그래프` 쌍으로 바꾼다. `지식 그래프` 링크는 새 아트보드를 가리킨다. `번역`·야간 모드 버튼은 그대로.
- README에 "Screen States — 지식 그래프 (FT-009 / YMC-394)" 절을 추가한다. 데이터 출처는 `/status`의 `knowledgeGraphStatus`와 `GET /api/papers/{paperId}/knowledge-graph`.

## 4. BE 설계

### 4.1 스키마 (`be/docs/db/document.sql`)

- `knowledge_graph_key varchar(255)` null 허용. 주석의 "기존 DB 반영" 절에 `alter table document add column if not exists knowledge_graph_key varchar(255);` 추가.
- 기존 행은 null로 둔다. dev의 옛 문서는 재컴파일 없이 FAILED로 표시된다. 별도 SQL 없음.

### 4.2 도메인

- `Document`: `knowledgeGraphKey` 필드. `knowledgeGraphStatus()`는 `KnowledgeGraphStatus.of(compileStatus, knowledgeGraphKey)`.
- 새 enum `KnowledgeGraphStatus { PENDING, READY, FAILED }`와 정적 `of(CompileStatus, String key)`: null·REQUESTED → PENDING, COMPLETED이고 key가 null·빈 문자열이 아니면 READY, 그 외 → FAILED.
- `DocumentRepository.markCompiled`에 `knowledgeGraphKey` 파라미터 추가(대상 컬럼만 갱신하는 기존 JPQL 유지). FAILED 경로는 null을 넘긴다.
- `DocumentTransitions.markCompiled(documentId, status, errorCode, knowledgeGraphKey)`.

### 4.3 컴파일 결과 수신

- `PaperPackageReader`에 `Optional<String> readKnowledgeGraphKey(String manifestKey)` 추가. manifest `artifacts.knowledge_bundle_viz.path`를 패키지 prefix에 붙여 키를 만든다(다른 artifact와 같은 규칙, manifest의 `key` 필드는 쓰지 않는다 — 테스트 픽스처가 paperId별 절대 키를 가질 수 없다). artifact가 없거나 path가 null·빈 문자열이면 WARN 후 empty. manifest 자체를 못 읽으면 예외(기존 `readTranslations`와 같은 기준 — 일시 장애는 재전달).
- `S3PaperPackageReader.Manifest.Artifacts`에 `@JsonProperty("knowledge_bundle_viz") Artifact knowledgeBundleViz` 추가. `Artifact` record는 그대로(`path`만).
- `KnowledgeCompileResultService.apply`: COMPLETED 분기에서 `merge` 뒤 `readKnowledgeGraphKey`를 호출하고 `markCompiled(documentId, COMPLETED, null, key)`. manifest는 `merge`가 이미 한 번 읽지만 호출 하나를 더 하는 것으로 두고 리더 안에서 캐시하지 않는다(논문당 한 번 오는 이벤트, 결정 표 "manifest 두 번 읽기"). 이미 종결된 문서는 기존대로 처음에 return하므로 키 보정 경로가 없다.
- 로그: 완료 INFO에 `knowledgeGraphKey` 유무를 함께 남긴다.

### 4.4 응답

- `PaperStatusView`·`PaperStatusResponse`에 `knowledgeGraphStatus` 추가. `PaperDocumentViews.statusView`: document가 없으면 null. `PaperStatusView.knowledgeGraphStatus`는 null 허용이고 Jackson이 `null`로 직렬화한다.
- `ErrorCode.KNOWLEDGE_GRAPH_NOT_READY(HttpStatus.CONFLICT)`.
- 새 서비스 `KnowledgeGraphViewService.view(paperId, ownerId)`: `PaperDownloadService`와 같은 순서로 PAPER_NOT_FOUND → FORBIDDEN → Document가 없거나(미연결·행 유실 모두) `knowledgeGraphStatus()`가 READY가 아니면 KNOWLEDGE_GRAPH_NOT_READY(409로 통일, `UPLOAD_NOT_FOUND`는 쓰지 않는다) → `AssetUrlCache.issue(knowledgeGraphKey)`로 presigned GET. 캐시를 쓰므로 만료 창 안에서는 같은 URL이 나가 브라우저 캐시가 산다.
- `PaperController`: `@GetMapping("/{paperId}/knowledge-graph")` → `KnowledgeGraphViewResponse(url, expiresAt)`.
- `FileStorage`는 변경 없음. `presignAssetGet`은 Content-Disposition을 싣지 않고 객체의 Content-Type을 그대로 쓴다.

## 5. FE 설계

- `api/types.ts`: `KnowledgeGraphStatus`, `PaperStatusResponse.knowledgeGraphStatus`. `api/papers.ts`: `getKnowledgeGraphView(paperId): Promise<{ url; expiresAt }>`.
- `routes/study/translationStatus.ts` 옆에 `knowledgeGraphStatus.ts`: `knowledgeGraphDisabledReason(status)`와 두 문구(null·그 외는 FAILED 문구). 폴링 판정은 `statusRefetchInterval(data)`로 합쳐 번역·그래프 중 하나라도 `'PENDING'`이면 5초, 아니면 false. null은 자연히 제외된다.
- `routes/study/usePaperStatusQuery.ts`: `['paper-status', paperId]` 키와 `refetchInterval: statusRefetchInterval`을 고정한 훅. StudyPage와 KnowledgeGraphPage가 모두 이 훅을 쓴다. 두 화면은 다른 라우트라 동시에 마운트되지 않지만 옵션이 어긋나지 않게 한 곳에 둔다.
- `routes/study/StudyTopBar.tsx`: 학습 화면 R1 상단 바를 추출한다. props는 `paperId`, `title`, `current: 'content' | 'graph'`, `knowledgeGraphStatus`, `rightSlot?`(학습 화면이 번역·야간 버튼을 넘긴다). `본문`은 `/papers/:paperId`, `지식 그래프`는 `/papers/:paperId/graph` 링크. 현재 화면은 `aria-current="page"`와 눌린 배경. `지식 그래프`는 READY가 아니면 비활성(`aria-disabled`, 클릭 무시)과 `title` 툴팁.
- `routes/KnowledgeGraphPage.tsx`: `useParams`로 paperId. `usePaperStatusQuery`로 상태를 읽는다(캐시 공유). 파싱 status가 COMPLETED가 아니면 StudyPage와 같은 규칙으로 `/library`로 돌려보낸다. `knowledgeGraphStatus`가 READY일 때만 `['knowledge-graph-view', paperId]` 쿼리(`staleTime: 0`, `gcTime: 0` — 진입마다 새 URL)를 켜고 iframe을 렌더한다. READY가 아니면 캔버스 영역 가운데에 문구와 `본문으로` 링크. URL 발급 실패는 문구와 `다시 시도`(refetch). iframe 자체의 로드 실패는 cross-origin이라 감지하지 못하므로 `다시 시도`는 발급 실패에만 붙는다.
- iframe: `title="지식 그래프"`, `sandbox="allow-scripts allow-same-origin"`, 상단 바 아래 전체(`flex:1`, border 없음, 배경 `--color-bg-canvas`).
- `main.tsx`: `RequireAuth` 자식에 `{ path: '/papers/:paperId/graph', element: <KnowledgeGraphPage /> }`.
- 아이콘: `본문`은 Phosphor `BookOpen`을 StudyPage의 `ArrowLeft`처럼 직접 import한다(`icons.ts`는 IconButton 전용 레지스트리라 건드리지 않는다). `지식 그래프`는 아트보드가 쓰는 인라인 SVG(노드 여섯 개와 연결선)를 `StudyTopBar` 안에 그대로 옮긴다.

## 6. 테스트

BE(통합, LocalStack):
- 컴파일 COMPLETED 수신 시 manifest의 viz 키가 저장되고 `/status`가 READY.
- manifest에 `knowledge_bundle_viz`가 없으면 키 null·compile_status COMPLETED·`/status` FAILED·WARN 로그.
- `/status` 판정: compile_status null → PENDING, REQUESTED → PENDING, FAILED → FAILED, COMPLETED+키 빈 문자열 → FAILED, Document 미연결 → null.
- 조합: 영어 문서 COMPLETED+키 없음 → `translationStatus` READY + `knowledgeGraphStatus` FAILED. 한국어 문서 COMPLETED+키 있음 → NOT_APPLICABLE + READY.
- 결과 메시지 재전달: 이미 COMPLETED인 문서에 completed가 다시 오면 키·상태 모두 그대로.
- `GET /knowledge-graph`: 200(url이 viz 키를 가리키고 `response-content-disposition` 없음), 403 타인, 404 없는 paperId, 409 PENDING·FAILED·Document 미연결.
- 기존 `KnowledgeCompileResultService`·`S3PaperPackageReader` 테스트에 새 파라미터·필드 반영.

FE(vitest):
- `statusRefetchInterval`·`knowledgeGraphDisabledReason` 단위 테스트.
- `StudyTopBar`: current 표시, READY 아닐 때 비활성·툴팁, 링크 경로.
- `KnowledgeGraphPage`: READY → URL 요청 후 iframe src 설정, PENDING·FAILED → 문구와 링크, 발급 실패 → 문구와 다시 시도.
- `StudyPage` 기존 테스트: 상단 바 추출 뒤에도 제목 말줄임, 야간 모드 `data-theme`, 계정 메뉴, 번역 버튼, 번역 READY 전환 시 본문 재조회가 유지.
- `KnowledgeGraphPage`: 발급 403·404·409 각각의 처리(404는 서재로, 409는 준비 문구).

## 7. 배포 순서

1. project-docs PR(openapi 0.7.0·FT-009·아트보드) 머지.
2. dev DB에 `knowledge_graph_key` ALTER 반영(dev는 `ddl-auto: update`라 BE 기동 시 자동 추가되지만 prod 절차와 맞추기 위해 document.sql 주석에 남긴다).
3. app PR(BE+FE) 머지 → CD.
4. dev 확인: 새 논문 업로드 → 컴파일 완료 → 학습 화면 버튼 활성 → 그래프 화면 렌더. 옛 문서는 FAILED 툴팁.
