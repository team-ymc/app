# 논문 본문 실연동 (BE 적재·서빙 + FE 픽스처 교체) — 설계

- Jira: YMC-298 (BE, 에픽 YMC-231) · YMC-299 (FE) / 선행 머지: YMC-289 (PR #28)
- 계약: `project-docs/contracts/frontend-backend/openapi.yaml`의 `getPaperContent` **초안**
  (YMC-293, project-docs 로컬 미커밋 — 신규 operation이므로 구현으로 검증 후 PR·확정한다)
- 메시지 계약: `project-docs/contracts/backend-ai/sqs/messaging.yml` **0.2.0 확정** (YMC-229,
  2026-08-05 main 머지) — snake_case wire·status 소문자·completed에 `manifest_key`+`message` 필수.
  현행 BE 메시지 코드(camelCase)는 구계약이라 **0.2.0 동기화가 Stage 1 범위에 포함**된다.
- 파서 산출물: ai repo `docs/S3_BUCKET_STRUCTURE_KO.md` · 샘플 패키지 `docs/0.0v3/`

## 1. 목적과 범위

FE 리디자인(YMC-289)에 남은 목 2개 중 **논문 본문**을 실제 API로 교체한다.

- In: BE 적재 파이프라인 + `GET /api/papers/{paperId}/content` (Stage 1) → FE 픽스처 교체 (Stage 2)
- Out: 인라인 번역(`translateSelection` 목 — 계약 신설부터 필요, 별도 설계), FT-005 전체 번역,
  AI 워커의 실제 SQS 발행(AI 소유)

접근 대안 비교(요청 시 S3 직독 lazy / API+수동 시딩)는 브레인스토밍에서 기각 —
lazy는 계약 서술과 어긋나고 결국 적재로 회귀(이중작업), 수동 시딩은 적재 로직과 사실상 동일해 절약이 없다.

## 2. 단계·브랜치

모두 main 기준. 순서대로 머지.

| Stage | 브랜치 | 내용 |
|---|---|---|
| 0 | (완료) | YMC-289 → main 머지 (PR #28, e8a3f17) |
| 1 | `YMC-298-be-paper-content` | BE 적재 + content API + 로컬 검증 스크립트 |
| 2 | `YMC-299-fe-paper-content` (예정) | FE 어댑터 교체·픽스처 제거 |

## 3. BE 설계 (Stage 1)

### 3.1 흐름

```
SQS parse-results (completed + manifest_key)    S3 papers/{paperId}/
        │                                        (manifest · frontend/document.json · assets)
        ▼                                              │
ParseResultService ──completed면──▶ PaperContentIngestService ◀─읽기─┘
  (상태 전이)                                (수식·표 인라인 → DB 적재)
                                                       │
                                                       ▼
                              paper_content · paper_content_block · paper_content_asset
                                                       │
GET /api/papers/{id}/content ◀─────────────────────────┘
  (DB 조립 + presigned GET URL) ──▶ FE 뷰어 (이미지는 FE가 S3 직접 GET)
```

### 3.2 데이터 모델 (신규 3테이블, 기존 테이블 무수정)

dev는 ddl-auto update(YMC-272)라 JPA 엔티티로 정의한다. 모두 `paper_id`로 `paper`에 연결.

- `paper_content` — paper당 1행: `paper_id`(PK), `title`, `schema_version`, `ingested_at`.
  행 존재 = 적재 완료의 기준.
- `paper_content_block` — 블록당 1행: `block_id`(파서 안정 id), `global_order`, `label`,
  `heading_level`, `section_path`(JSONB), `content`(JSONB — 수식 tex·표 html 인라인 포함).
  인덱스 `(paper_id, global_order)`.
- `paper_content_asset` — 이미지·차트당 1행: `asset_key`, `s3_key`, `media_type`.
  presigned URL은 저장하지 않고 조회 시 발급.

블록을 행으로 분리하는 이유: 계약이 예고한 `globalOrder` 커서 페이지네이션 대비
(paper당 JSONB 한 덩어리 대안은 그 시점에 재작업이 필요해 기각).

### 3.3 적재 — `PaperContentIngestService`

- 입력: `paperId` + `manifestKey`. S3에서 manifest → `frontend/document.json`(파서 schema v1,
  snake_case) 읽고 계약형(camelCase)으로 매핑해 저장.
- `display_formula`·`table` 블록은 asset 파일(tex·html)을 **적재 시점에 인라인** —
  조회 시 S3 접근은 이미지 presigned 발급뿐.
- 재적재 멱등: 기존 행 삭제 후 삽입.
- 리스너와 분리된 서비스 — SQS 외 경로(검증 스크립트·추후 어드민)에서도 호출 가능.
- **메시지 계약 0.2.0 동기화** (YMC-229 확정 반영): `ParseResultMessage`를 snake_case·소문자
  status·`manifest_key`(completed 필수)로 재작성하고, 발행 측 `ParseRequestMessage`도
  `paper_id`/`file_key`로 맞춘다. 구형 형식 메시지는 계약 위반으로 폐기(기존 ack 규칙).
  실제 발행 주체가 아직 검증 스크립트뿐이라 클린 컷오버가 가능하다.
- 리스너 연결: `ParseResultService.apply()`가 completed 수신 시 적재 호출. 적재 판정은 전이
  성공 여부와 독립 — "COMPLETED 상태 + 미적재"면 적재하므로, 전이 커밋 후 적재만 실패해
  재전달된 메시지도 복구된다. 적재 실패는 예외를 올려 SQS 재전달(5회 후 DLQ)에 태운다.

### 3.4 조회 — `GET /api/papers/{paperId}/content`

호출 1회 = SELECT 4개 고정 (블록 수와 무관, N+1 없음):

1. `paper` PK 조회 — 없으면 404, 소유자 아니면 403, COMPLETED 아니면 409 `PAPER_NOT_READY`
2. `paper_content` — 행 없으면(적재 전) 409 `PAPER_NOT_READY`
3. `paper_content_block` — `ORDER BY global_order` 전체 (MVP 페이지네이션 없음)
4. `paper_content_asset` — 행마다 presigned GET URL 서명(로컬 연산, S3 왕복 없음), `expiresAt` 포함.
   만료 창 내 동일 asset은 같은 URL 재사용(발급 시각 캐시).

## 4. FE 설계 (Stage 2)

교체 지점은 어댑터 하나 — `fe/src/markdown/paperContent.ts`가 유일한 접점(리디자인 spec §6의 격리).
뷰어·TOC·스크롤 스파이·선택 레이어·튜터 패널은 무수정. 같은 `PaperBlock[]` 모델을 소비한다.

- `getPaperContent`: 픽스처 반환 → `api/papers.ts`의 실제 fetch로 이동.
- 어댑터: remark AST 정규화 → 계약 label 매핑으로 교체.

| 계약 label | 뷰어 BlockType |
|---|---|
| doc_title · paragraph_title | heading / subheading (`headingLevel`로 구분) |
| abstract · text · reference_content · figure_title | para (인라인 `$…$`는 기존 PaperMarkdown 렌더) |
| display_formula (tex) | equation |
| table (html) | table — **DOMPurify 정화 후** 렌더 (계약 요구, 의존성 추가) |
| image · chart (assetKey) | figure — assets 맵의 presigned URL `<img>` |
| (미지 label) | para 강등 + 콘솔 경고. enum이 닫혀 있으니 새 label은 계약 PR부터 |

- **blockId 안정화**: `block-{i}` 인덱스 → 파서 안정 id(`p0002-b0006`). 다음 단계 인라인 번역
  계약이 선택 anchor로 쓸 예정이라 미리 정합.
- `title`: 응답 `title` 사용, null이면 첫 heading 폴백(기존 동작 유지).
- 이미지 `expiresAt` 경과 후 로드 실패 → content 1회 재조회(계약의 회복 경로).
- 409 `PAPER_NOT_READY`: 정상 진입(COMPLETED만 학습 페이지)에선 없지만 방어적으로 서재 안내.
- 제거: `parsePaperMarkdown`·`fixtures/sample-paper.md`(용도 소멸). `PaperMarkdown` 렌더러는 유지.
  어댑터 테스트는 계약 예시 payload 기반으로 재작성.

## 5. 테스트·검증

- BE 단위: 파서 샘플 `frontend/document.json` 픽스처 → 블록 매핑·인라인·재적재 멱등.
- BE 통합(LocalStack, 기존 패턴): 200 조립·404/403/409·presigned 발급.
  리스너: 0.2.0 형식 completed → 전이+적재, failed → 전이만, `manifest_key` 누락 completed·
  구형 형식 → 계약 위반 폐기. 기존 소비 테스트는 0.2.0 wire 형식으로 이관.
- FE 단위: 어댑터 label 전 종류 매핑·표 정화·미지 label 방어. 기존 뷰어 테스트가
  무수정 통과하는 것 자체가 "뷰어 안 건드림" 검증.
- Stage 1 완료 기준: 샘플 패키지 S3 시딩 → `publish-parse-result.sh`(manifestKey 추가) →
  적재 → `curl` content 200 + blocks/assets 확인.
- Stage 2 완료 기준: 브라우저 학습 페이지에서 실API 본문 렌더 — 수식·표·이미지·TOC·선택 확인,
  픽스처 파일 소멸.

## 6. 리스크·후속 (이번 범위 밖)

- ~~`messaging.yml` manifestKey 반영~~ — **해소** (YMC-229, sqs/messaging.yml 0.2.0 main 머지).
- `openapi.yaml` getPaperContent 확정 PR — Stage 2 검증 후. yaml `0.2.5` ↔ README "0.3.0" 표기
  어긋남도 이때 정리.
- AI 워커의 실제 SQS 발행 — AI 소유, 별도 티켓.
- 인라인 번역(Stage 3) — 계약 신설부터, 별도 브레인스토밍.
