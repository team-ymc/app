# DESIGN — BE 검증용 임시 업로드 UI

Source: project-docs/features/FT-003-논문-등록-분석.md / Jira: YMC-221 (에픽 YMC-179)
Decision: project-docs/decisions/ADR-001-pdf-upload-presigned-url.md

이 문서는 **왜 이렇게 만들었나**를 적는다. 실행법은 [README.md](./README.md)에 있다.

## 이게 뭔가

**BE 검증용 임시 UI다. 제품 기능이 아니라 개발 도구다. 진짜 FE 착수 시 폐기한다.**

FT-003 BE 구간(`openspec/changes/ft-003-paper-registration-api`)은 구현이 끝났고 LocalStack에서 E2E도 통과했다. 그런데 그 검증은 전부 서버 쪽에서 한 것이다. **브라우저로는 한 번도 굴려본 적이 없다.**

ADR-001이 정한 업로드 방식은 "브라우저가 presigned URL로 S3에 직접 PUT"이다. 즉 브라우저가 없으면 재현되지 않는 실패 경로가 있고(아래 D5·D6), 이 UI는 그걸 드러내려고 존재한다.

기반 목업은 `temp/논문 등록 처리 UI.html`(등록 플로우 클릭 데모)이다. 마크업·스타일을 이식하고 가짜 동작을 실제 API 호출로 교체했다.

## Context

- **BE는 준비돼 있다.** `PaperController`에 `POST /api/papers`, `POST /{paperId}/complete`, `GET /{paperId}/status`가 있고 `ParseResultListener`(SQS `parse-results` 소비)까지 있다. 인증은 없다(`openapi.yaml`의 `security: []`).
- **`GET /api/papers`는 없다.** 계약(`listPapers`)에는 있지만 컨트롤러에 없다. FT-002 소유다.
- **AI 파싱 워커가 없다.** 따라서 실제로는 `PROCESSING`에서 멈춘다 — 이건 버그가 아니다.

### 목업의 DSL은 React에 1:1로 대응한다

번들(Claude 아티팩트)을 풀어 확인한 결과 사용된 DSL 표면이 매우 작다. 이식은 기계적이다.

| 목업 | React |
|---|---|
| `<sc-if value="{{ isModalOpen }}">` (9개) | `{isModalOpen && (…)}` |
| `sc-camel-on-click="{{ openModal }}"` (이벤트 8개) | `onClick={openModal}` |
| `style="{{ uploadBarStyle }}"` (4개) | `style={uploadBarStyle}` — 이미 React camelCase 스타일 객체다 |
| `>{{ processingPct }}<` (3개) | `{processingPct}` |
| `class Component extends DCLogic` | React 클래스 컴포넌트 — `state`/`setState`/`componentWillUnmount` 이름이 동일하다 |

CSS는 평범한 인라인 스타일 + `<style>` 블록 하나다. 폰트(Pretendard Variable woff2, 2MB)는 번들에서 추출해 `public/`에 넣으면 렌더링이 동일하다. **겉모습은 원본과 대조 가능한 수준으로 같아야 한다.**

## Goals / Non-Goals

**Goals:**

- 브라우저에서 PDF를 골라 올리면 `POST /api/papers` → presigned PUT → `complete` → `status` 폴링이 **실제로** 도는 것을 눈으로 확인한다.
- 브라우저가 없으면 재현되지 않는 두 실패 경로(버킷 CORS, presign의 Content-Type 서명)를 드러낸다.
- 성공 경로뿐 아니라 실패 경로(`409 DUPLICATE_FILENAME`, `FAILED`)도 관찰 가능하게 한다.
- BE 프로덕션 코드를 한 줄도 바꾸지 않는다.

**Non-Goals:**

- `GET /api/papers` 구현(FT-002), 인증(FT-001), 자동 테스트, 진짜 FE의 구조. `parse-result.result` 본문 해석(미확정, AI 소유).

## Decisions

### D1. Vite dev server proxy로 BE CORS를 우회한다

- 선택: `vite.config.js`에서 `/api` → `http://localhost:8080` 프록시. 브라우저 입장에서 same-origin(5173)이 되어 preflight 자체가 없다.
- 대안 — BE에 `@CrossOrigin`/`CorsConfigurationSource` 추가: 버릴 UI 때문에 프로덕션 코드에 개발용 설정이 남는다. 나중에 지우는 걸 잊으면 운영에 개발 origin이 열린 채로 나간다.
- **주의: 프록시는 BE 호출에만 적용된다.** S3 presigned PUT은 프록시를 타지 않는다 — presigned URL은 특정 host(`localhost.localstack.cloud:4566`)에 대해 서명돼 있어 프록시가 Host를 바꾸면 서명이 깨진다. 브라우저가 그 URL로 직접 쏴야 하고, 그래서 D5가 필수다.

### D2. 파싱 진행률은 불확정(indeterminate) 바로 만든다

- 계약의 `PaperStatusResponse`는 `paperId`·`status`·`updatedAt`뿐이다. **진행률 필드가 없다.** 폴링으로 알 수 있는 건 `PROCESSING`이냐 `COMPLETED`냐뿐이다.
- 목업의 "0%→100%로 차오르는 바"는 뒷받침할 데이터가 없는 UI 픽션이다. BE가 진짜 도는지 보려는 도구가 가짜 숫자를 보여주면 도구로서 실격이다.
- 선택: 좌우로 흐르는 불확정 애니메이션. 레이아웃과 겉모습은 유지하면서 없는 정보를 지어내지 않는다.
- 대안 — 계약에 `progress` 필드 추가: `openapi.yaml` + BE + AI 워커까지 바뀐다. contract 변경은 project-docs PR로만 가능하고 MVP 범위 밖이다. 임시 UI를 위해 계약을 흔들지 않는다.
- **업로드 진행률은 반대다.** XHR `upload.onprogress`가 주는 실제 바이트 수이므로 진짜 퍼센트를 쓴다.

### D3. 서재 목록은 로컬 상태로 두되, 접점 한 곳에 격리한다

- `GET /api/papers`가 없으므로(FT-002 소유) 등록한 논문을 React state에 들고 화면에 뿌린다. 그 행의 상태만 `GET /{paperId}/status`로 폴링한다.
- 한계를 명시한다: 새로고침하면 서재가 빈다. "BE가 목록을 돌려주는가"는 검증하지 못한다. (영속성 자체는 status 폴링이 증명한다.)
- 격리: `src/api.js`의 `listPapers()`가 유일한 접점이다. FT-002에서 엔드포인트가 생기면 **이 함수 하나만** 실제 `fetch`로 바꾸면 되고 `App.jsx`는 그대로다.

### D4. 상태 모델은 목업의 `screen` 단일 필드를 분해한다

목업은 `screen` 문자열 하나로 6개 화면을 표현했다(`library-empty` … `library-completed`). 그러나 실제로는 **모달의 상태**와 **서버가 소유한 논문의 상태**가 서로 다른 축이다. 뒤섞어두면 폴링 결과를 반영할 자리가 없다.

```js
modal:        'closed' | 'idle' | 'file-selected' | 'uploading'
selectedFile: File | null      // 진짜 File 객체
uploadPct:    0..100           // XHR progress — 진짜 숫자
papers:       []               // api.listPapers() 결과
error:        null             // 409 등
dragOver:     false
```

행의 표시는 서버가 준 `paper.status`가 정한다. 목업의 `library-empty`는 `papers.length === 0`, `library-processing`/`library-completed`는 각각 `PROCESSING`/`COMPLETED` 행에 대응된다.

### D5. S3 버킷 CORS는 infra에 넣는다 — 버릴 코드가 아니다

- `infra/local/bootstrap.sh`에 `awslocal s3api put-bucket-cors` 추가. `AllowedOrigins: http://localhost:5173`, `AllowedMethods: PUT`, `ExposeHeaders: ETag`.
- 이건 임시 UI를 위한 임시 조치가 아니다. **ADR-001의 브라우저 직접 업로드 방식을 쓰는 한 진짜 FE도 똑같이 필요하다.** 지금 없는 것은 지금까지 브라우저가 없었기 때문일 뿐이다.

### D6. presigned PUT은 Content-Type을 명시적으로 실어야 한다

- `S3FileStorage.presignUpload()`가 `.contentType(contentType)`을 서명에 포함한다(코드에서 확인). 즉 브라우저의 PUT은 `Content-Type: application/pdf`(= `CreatePaperRequest.contentType`로 보낸 값)를 **정확히** 실어야 하고, 아니면 S3가 `SignatureDoesNotMatch`로 거부한다.
- XHR에서 `setRequestHeader('Content-Type', 'application/pdf')`로 명시한다. 브라우저가 `File.type`에서 알아서 채우도록 두지 않는다 — 파일에 따라 빈 문자열이거나 다른 값일 수 있다.
- 추측이 아니라 코드에서 확인한 제약이고, **이 UI가 존재하는 이유 중 하나**다.

### D7. 에러는 숨기지 않고 화면에 그대로 노출한다

- 하네스의 일은 예쁘게 보이는 게 아니라 **어디서 깨지는지 보여주는** 것이다. 계약의 `Error` 스키마(`code` + `message`)를 받은 그대로 모달/행에 띄운다.
- 대상: `409 DUPLICATE_FILENAME`(create), presigned 만료, S3 PUT 실패, `complete`의 4xx(객체 없음 → `UPLOAD_NOT_FOUND`), 파싱 `FAILED`(+ `error.code`).

### D8. TypeScript·자동 테스트를 넣지 않는다

- 버릴 코드다. 타입 생성 파이프라인(openapi → TS)과 테스트 하네스를 얹을 이유가 없다.
- 검증 대상은 BE지 이 UI가 아니다. 테스트가 있어야 할 곳은 BE의 통합 테스트이며, 이미 있다.

## Risks

- **폰트 2MB.** Pretendard Variable woff2를 번들에서 추출해 `public/`에 커밋한다. 부담이면 CDN 링크로 대체 가능하나 오프라인에서 목업과 대조가 안 된다. 추출본을 쓴다.
- **presigned URL 만료(10분).** 파일 선택 후 오래 방치하면 PUT이 403난다. 하네스는 이 에러를 그대로 노출한다(D7). 자동 재발급은 하지 않는다 — 진짜 FE의 몫이다.
- **`PROCESSING` 정체가 정상이다.** AI 워커가 없어서다. `publish-parse-result.sh`로 완료시킨다(README 참조).
- **LocalStack 재기동 시 CORS 유실.** 버킷을 다시 만들면 `bootstrap.sh`가 다시 돌아야 한다.

## Verification

성공 기준은 UI가 아니라 **BE가 브라우저에서 도는 것**이다. 손으로 훑는다:

1. PDF 업로드 → LocalStack에 객체 실존 (`awslocal s3 ls s3://ymc-documents --recursive`)
2. `GET /{paperId}/status` → `PROCESSING`
3. `parse-requests` 큐에 메시지 실존 (`awslocal sqs receive-message`)
4. `publish-parse-result.sh <paperId> COMPLETED` → 화면이 완료 행으로 전환
5. `publish-parse-result.sh <paperId> FAILED` → 실패 행 + `error.code` 표시
6. 같은 파일명 재업로드 → `409 DUPLICATE_FILENAME`이 모달에 표시

## 작업 목록

- [ ] **infra 1** — `bootstrap.sh`에 S3 버킷 CORS 추가 (D5)
- [ ] **infra 2** — `publish-parse-result.sh <paperId> [COMPLETED|FAILED]` 작성 (`parse-result.schema.json` envelope 준수)
- [ ] **fe 1** — Vite + React 스캐폴드, proxy `/api` → `:8080`, Pretendard 폰트 연결 (D1·D8)
- [ ] **fe 2** — `src/api.js`: `createPaper` / `uploadToS3`(Content-Type 명시, D6) / `completeUpload` / `getStatus` / `listPapers`(로컬 격리, D3)
- [ ] **fe 3** — `src/App.jsx`: 목업 이식(9 `sc-if` + 8 이벤트 + 스타일 객체), 상태 모델 재구성(D4), 진짜 file input, 실제 업로드 진행률, 불확정 파싱 바(D2), 2초 폴링
- [ ] **fe 4** — 목업에 없던 화면: `FAILED` 행, `409 DUPLICATE_FILENAME` (D7)
- [ ] **마무리** — README 작성, Verification 6개 시나리오 수동 검증(특히 D5·D6), 발견 문제는 별도 이슈로(BE 무변경 원칙)
