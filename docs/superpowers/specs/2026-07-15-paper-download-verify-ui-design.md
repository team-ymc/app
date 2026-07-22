# DESIGN — 논문 다운로드 + throwaway 검증 UI

Source: project-docs/features/FT-002-서재.md, project-docs/features/FT-003-논문-등록-분석.md
Decision: project-docs/decisions/ADR-001-pdf-upload-presigned-url.md
Jira: 신규(미발급) · 관련 에픽 YMC-179 · 기존 throwaway UI YMC-221

> 이 문서는 **왜/무엇**의 SSOT다. 계약·BE·FE·infra가 한 몸인 단일 슬라이스라 하나로 둔다.
> 구현 순서(계약 → BE → FE → infra)는 writing-plans의 계획이 담는다.

## 1. 배경

사용자 여정은 **PDF 등록 → 진행 상태 확인 → 완료 시 서재에서 확인 → 다운로드**다.
등록·진행·상태는 BE(`POST /api/papers`, `/complete`, `GET /{id}/status`)와 `ParseResultListener`까지 이미 구현돼 있다. 그러나:

- **다운로드는 어디에도 없다.** `openapi.yaml`·BE·프리사인 GET 어디에도 없다. BE엔 `presignUpload`(업로드)만 있다.
- **fe에 앱이 없다.** DESIGN.md(fe/)에 throwaway UI 설계만 있고 `src`·`package.json`이 전부 없다. 등록/서재 화면조차 브라우저로 돌려본 적이 없다.
- **AI 워커가 없다.** 실제로는 `PROCESSING`에서 멈춘다 — 버그가 아니다. 완료/실패는 `infra/local/publish-parse-result.sh`로 수동 발행한다.

이 작업은 다운로드를 정식으로 추가하고, 여정 전체를 브라우저로 처음 굴려 검증한다.

## 2. 이 작업의 두 성격 (핵심 프레이밍)

fe 코드는 두 층으로 갈리고, 성격이 다르다.

| 층 | 내용 | throwaway? |
|---|---|---|
| **연동 층** (`fe/src/api.js`) | createPaper → 프리사인 PUT(Content-Type 서명) → complete → status 폴링 → **download** | ❌ **진짜다.** 실제 FE가 그대로 재사용한다. |
| **표현 층** (React 컴포넌트·스타일) | temp/ 목업 이식한 화면 껍데기 | ✅ 진짜 FE 착수 시 폐기 |

**다운로드 BE 기능(계약·엔드포인트·프리사인)도 throwaway가 아니라 진짜 제품 코드다.**
"버릴 것"은 오직 **UI 셸(표현 층)**뿐이다. 그래서 `api.js`는 격리·에러 처리를 제대로 인코딩한다 (DESIGN.md D3의 단일 접점 원칙이 이걸 위한 것 — 진짜 FE는 이 모듈만 들고 간다).

## 3. 목표 / 비목표

**목표**

- 원본 PDF 다운로드를 계약(openapi)에 신설하고 BE에 정식 구현한다.
- 브라우저에서 여정 전체(등록 → 업로드 → 폴링 → COMPLETED → **다운로드**)가 실제로 도는 것을 눈으로 확인한다.
- 성공 경로뿐 아니라 실패·경계(다운로드 409, FAILED 행)도 관찰 가능하게 한다.
- **서재 목록 `GET /api/papers`(FT-002)를 BE에 구현**하고, FE 서재가 로컬 상태가 아니라 실제 목록을 받아 새로고침해도 유지되게 한다. (계약엔 이미 있음 — BE 구현만; **이로써 D3 폐기** — §10 참조)

**비목표**

- 인증(FT-001), AI 워커, seen 기록(FT-002).
- `parse-result.result`(분석 산출물 본문) 다운로드 — 미확정·AI 소유라 다운로드할 실체가 없다. **다운로드 대상은 원본 PDF뿐이다.**
- 진짜 FE의 구조·표현층 완성도.

## 4. 설계

구현 순서는 워크스페이스 규칙(**feature → contract → code**)을 따른다: FT-002 Story 추가 → openapi → BE/FE/infra.

### 4.0 feature 문서 — `project-docs/features/FT-002-서재.md` (먼저)

다운로드는 사용자가 **서재의 완료된 행에서 하는 액션**이므로 FT-002(서재)가 소유한다. (FT-003은 원본의 입력=업로드 쪽, 다운로드는 소비 쪽이라 서재가 맞다.) 새 Story 추가:

> **Story (신규). 사용자는 완료된 논문의 원본 PDF를 다운로드할 수 있다** `MVP`
> - `COMPLETED` 행에서 원본 PDF 다운로드를 제공한다. `PROCESSING`/`FAILED` 행에는 제공하지 않는다.
> - 저장 파일명은 등록 시 원본 파일명(`filename`)을 따른다.

FT-002 Out of Scope에 삭제·이름변경은 있으나 다운로드는 없었다 — 이 Story가 그 공백을 채운다. project-docs PR로 먼저 반영한 뒤 계약이 이를 참조한다.

### 4.1 계약 — `project-docs/contracts/openapi.yaml`

규칙상 코드보다 계약이 앞선다. 새 엔드포인트:

```
GET /api/papers/{paperId}/download        operationId: getPaperDownloadUrl
  200 → PaperDownload { downloadUrl: uri, expiresAt: date-time }
  404 → Error(PAPER_NOT_FOUND)
  409 → Error(UPLOAD_NOT_FOUND)   # S3에 객체 없음 = UPLOAD_PENDING/EXPIRED. 기존 코드 재사용.
```

- **응답은 프리사인 GET URL을 JSON으로** 돌려준다 (create의 `uploadUrl`과 대칭). 파일 바이트는 BE를 거치지 않는다 (ADR-001 일관).
- 프리사인에 `response-content-disposition: attachment; filename="<원본 filename>"`을 실어, 저장되는 파일명이 S3 key가 아니라 원본 이름이 되게 한다.
- **버전:** `0.1.0 → 0.1.1`. 새 엔드포인트는 기존 클라이언트를 깨지 않는 **호환 변경**이므로 `contracts/README.md` 규칙상 patch다. (minor는 breaking 전용)
- **참조:** §4.0에서 추가한 FT-002 다운로드 Story를 계약이 참조한다 (feature가 계약보다 앞섬).

`PaperDownload` 스키마:

```yaml
PaperDownload:
  type: object
  required: [downloadUrl, expiresAt]
  properties:
    downloadUrl: { type: string, format: uri }   # S3 presigned GET. content-disposition=attachment 포함.
    expiresAt:   { type: string, format: date-time }
```

### 4.2 BE 구현 (기존 패턴 그대로)

- **포트** `FileStorage`에 `presignDownload(fileKey, filename)` 추가 → `PresignedDownload(url, expiresAt)` 반환.
- **infra** `S3FileStorage`에서 `S3Presigner.presignGetObject`로 구현. `GetObjectRequest`에 `responseContentDisposition("attachment; filename=\"...\"")` 지정. 만료는 `props.s3().presignExpiry()` 재사용.
  - be/CLAUDE.md 규칙: SDK API(`presignGetObject`·`GetObjectPresignRequest`)는 기억에 의존하지 말고 **context7로 해당 버전 문서 확인 후** 작성한다.
- **service** `PaperDownloadService` 신설: paper 로드 → 상태 가드(객체 존재 여부) → `presignDownload` → 반환. `PaperStatusService`와 같은 결.
  - 상태 가드: `UPLOAD_PENDING`/`EXPIRED`(S3 객체 없음)면 `UPLOAD_NOT_FOUND`(409). 그 외(`UPLOADED`/`PROCESSING`/`COMPLETED`/`FAILED`)는 URL 발급.
  - 존재하지 않는 paperId면 `PAPER_NOT_FOUND`(404).
- **api** `PaperController`에 `GET /{paperId}/download` + `PaperDownloadResponse` DTO.
- **기존 등록/complete/status 코드는 손대지 않는다.**

가용 조건은 "S3 객체 존재"만 본다(엔드포인트는 재사용 가능하게 단순). "완료 시에만 다운로드"라는 제품 의도는 **UI가 COMPLETED 행에서만 버튼을 노출**해 구현한다.

### 4.3 FE — throwaway 검증 UI (`fe/`, DESIGN.md 그대로 신규 구축)

- Vite + React. temp/ 목업을 기계적 이식(DESIGN.md D1~D7). 상태모델 D4, `src/api.js` 단일 접점, Vite dev proxy로 BE CORS 우회(D1).
- **풀 플로우:** 빈 서재 → 등록 모달 → 파일 선택 → 프리사인 PUT 업로드(XHR progress·Content-Type 명시, D6) → complete → local-state 서재 행 → `status` 폴링 → 진행중/완료/실패 표시(FT-002 Story 3 매핑).
- **다운로드 추가 (연동 층):** `api.js`에 `downloadPaper(paperId)` — `GET /{id}/download` 호출해 `downloadUrl` 획득.
- **다운로드 추가 (표현 층):** COMPLETED 행에 다운로드 버튼 → `downloadUrl`을 `<a href download>` **네비게이션**으로 받는다.
  - 네비게이션 방식이라 **S3 GET에 CORS가 필요 없다.** (fetch-blob였다면 GET CORS 필요) 파일명은 프리사인의 content-disposition이 결정한다.
- `PROCESSING`/`FAILED` 행에는 다운로드 버튼을 노출하지 않는다.

### 4.4 infra — `infra/local/bootstrap.sh`

- **버킷 CORS(PUT) 추가** — DESIGN.md D5가 정했으나 아직 안 들어간 상태다. 브라우저의 프리사인 PUT(업로드)에 필요하다. `AllowedOrigins: http://localhost:5173`, `AllowedMethods: PUT`, `ExposeHeaders: ETag`.
- 다운로드는 네비게이션이라 **GET CORS는 넣지 않는다.** (필요해지면 그때 추가)
- 이는 임시 조치가 아니다 — ADR-001의 브라우저 직접 업로드를 쓰는 한 진짜 FE도 동일하게 필요하다.

## 5. 데이터 흐름 (다운로드)

```
[브라우저] COMPLETED 행 다운로드 클릭
   → GET /api/papers/{id}/download        (Vite proxy → BE:8080)
      → BE: paper 로드 · 상태 가드 · S3Presigner.presignGetObject
      ← 200 { downloadUrl, expiresAt }
   → <a href=downloadUrl download> 네비게이션
      → S3(LocalStack)가 원본 PDF 반환 (content-disposition=attachment; filename=원본명)
   → 브라우저가 원본 파일명으로 저장
```

## 6. 에러 처리

- **404 PAPER_NOT_FOUND** — 없는 paperId. `GlobalExceptionHandler`가 기존처럼 매핑.
- **409 UPLOAD_NOT_FOUND** — 객체가 S3에 없음(UPLOAD_PENDING/EXPIRED). 기존 에러 코드 재사용.
- **프리사인 만료** — `downloadUrl`은 짧은 만료. 만료 후 클릭하면 S3가 403. UI는 에러를 숨기지 않고 그대로 노출(DESIGN.md D7). 자동 재발급 없음.

## 7. 검증 시나리오

1. 업로드 → LocalStack S3에 객체 생성 확인.
2. `status` 폴링으로 `PROCESSING` 확인 (AI 워커 없어 여기서 멈춤 — 정상).
3. `./publish-parse-result.sh <id> COMPLETED` → 행이 완료로 전환.
4. **다운로드 버튼 클릭 → 원본 파일명으로 저장 확인** (핵심).
5. `./publish-parse-result.sh <id> FAILED` → 실패 행, 다운로드 버튼 없음 확인.
6. `UPLOAD_PENDING` 상태(complete 전)로 `/download` 직접 호출 시 **409 UPLOAD_NOT_FOUND** 확인.

## 8. 결정 사항

- **D1. 프리사인 GET URL을 JSON으로 반환** (BE 바이트 스트리밍 아님). create의 `uploadUrl`과 대칭, ADR-001 일관, BE가 대용량 바이트 프록시를 안 함.
- **D2. 다운로드 대상은 원본 PDF.** `parse-result.result`는 미확정·AI 소유라 실체 없음.
- **D3. 가용 조건은 객체 존재.** 제품 게이팅(COMPLETED)은 UI가 담당 — 엔드포인트는 단순·재사용 가능하게.
- **D4. 다운로드는 `<a download>` 네비게이션.** S3 GET CORS 불필요, content-disposition이 파일명 결정.
- **D5. 연동 층(api.js)은 진짜 FE 재사용 대상, 표현 층만 폐기.** api.js는 버릴 코드가 아니라 선투자.
- **D6. 계약 버전 0.1.1 (patch).** 새 엔드포인트는 호환 변경 — repo 규칙상 patch.

## 9. 미해결 / 후속

- 진짜 FE 착수 시 표현 층 폐기, `api.js` 승계.
- AI 워커 도입 시 수동 publish 단계 제거.
- (다운로드 feature 반영은 §4.0으로 **이번 스코프에 포함**됨.)

## 10. 변경 이력 (2026-07-15 갱신)

두 가지가 이 문서 본문의 일부 결정을 **덮어쓴다**. 아래가 최신이다.

### 10.1 `GET /api/papers` 목록을 이번 스코프에 포함 — **D3 폐기**

- 본문 §3 비목표·§4.3·§8 **D3**는 "서재는 local state, `listPapers()`는 stub" 이었다. 이제 **BE가 `GET /api/papers`(FT-002)를 실제 구현**하고, FE `api.js`의 `listPapers()`는 **실제 `fetch`**로 서재 목록을 받는다.
- 계약(`openapi.yaml`)엔 `listPapers`가 **이미 0.1.0에 있음** → 계약 변경 없음, **BE 구현만**.
- BE: `PaperListService` + `GET /api/papers` + `PaperRepository.findAllByOwnerId(fixedOwnerId)` + `PaperListResponse`/`PaperListItem`(paperId·filename·status·createdAt·updatedAt). fixed owner(인증 없음).
- FE: 로드 시 목록 fetch → 폴링으로 각 행 status 갱신. 새로고침해도 서재 유지(D3의 "새로고침하면 빈다" 한계 해소).

### 10.2 Jira 티켓 매핑

- **YMC-221** [FE] — app/fe 검증 UI(업로드→상태→**목록 연동**→**다운로드 버튼**) + **infra**(CORS + publish 스크립트). 표현 층은 throwaway, `api.js`는 진짜 FE 승계.
- **YMC-223** [BE] — app/be(**목록** `GET /api/papers` + **다운로드** `GET /{id}/download`) + **계약**(openapi 0.1.1: download) + **docs**(FT-002 다운로드 Story).
- 브랜치: project-docs·app(be)=`YMC-223-*`, infra·app(fe)=`YMC-221-*`. app은 be(223)→fe(221) 순, fe 브랜치는 be 브랜치 위에 스택(로컬 검증이 BE 코드에 의존).
