# 서재 논문 관리 — 삭제·이름 변경·파일명 중복 허용 설계

- 날짜: 2026-09-05
- 티켓: YMC-369
- 범위: BE(`app/be`) + FE(`app/fe`) + project-docs(계약·Feature·WF·design/v2). AI·인프라 변경 없음.
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml` 0.3.0 → **0.4.0**(이 스펙이 요구하는 변경, 별도 project-docs PR 선행)
- UI 시안: A안(hover 아이콘)·B안(케밥 메뉴) 비교 후 **B안** 확정. 이름 변경은 메뉴에서만 진입(제목 더블클릭은 Codex 리뷰로 제외 — §1)

## 1. 목표와 결정

서재에서 사용자가 자기 논문을 정리할 수 있게 한다.

| 기능 | 결정 | 근거 |
|---|---|---|
| 삭제 | **논리 삭제** (`paper.deleted_at`) | Document·S3는 다른 사용자와 공유. 채팅 세션(이미 논리 삭제)·usage `source_id`·파싱 중 `request_paper_id`가 그대로 유효 |
| 삭제 시 채팅 | 해당 논문의 세션을 같은 트랜잭션에서 일괄 논리 삭제 | 논문이 404가 되면 어차피 닿지 않지만 데이터 정합을 맞춘다 |
| 삭제 시 사용량 | 복구하지 않음 | FT-011 Story: "완료된 대화·메시지·문서를 삭제해도 확정된 사용 횟수는 복구하지 않는다" |
| 진행 중 논문 삭제 | 허용 | 내부 경로는 삭제 행을 계속 보므로 파싱·정산이 그대로 끝난다 (§3.2) |
| 이름 변경 범위 | `.pdf` 확장자를 뺀 이름만 편집, 저장 시 FE가 `.pdf`를 다시 붙임 | 확장자 훼손 방지. BE는 접미를 강제하지 않는다 |
| 이름 변경 트리거 | 행 메뉴 "이름 변경"만 | 행 단일 클릭이 학습 페이지 진입이라 제목 더블클릭은 첫 클릭에서 이미 이동해 버려 성립하지 않는다. 지연 열기(250ms)는 이상한 체감을 만들어 채택하지 않음 |
| 파일명 중복 | 허용 — 유니크 제약·사전 조회·`409 DUPLICATE_FILENAME` 제거 | 같은 이름의 다른 파일을 올릴 수 없던 제약 해소. 동일 바이트 중복 제거는 Document(ADR-003)가 이미 담당 |
| 행 액션 UI | 케밥(⋯) 메뉴: 다운로드 / 이름 변경 / 삭제 | 삭제 오클릭이 낮고 액션 추가 여지. 다운로드는 API만 있고 버튼이 없던 것을 이번에 붙인다 |

제외(후속 티켓): 삭제 복원, 논리 삭제 행 물리 정리 스윕, 실패 논문 재분석.

## 2. 계약 변경 (openapi 0.4.0)

### 추가

`PATCH /api/papers/{paperId}` — `renamePaper`

- 요청 `RenamePaperRequest { filename: string }` — trim 후 1~255자.
- 응답 `200 PaperListItem` (변경된 행 그대로. FE가 캐시의 해당 항목만 교체한다)
- 오류: `400 VALIDATION_ERROR`(공백·255자 초과), `401`, `403 FORBIDDEN`, `404 PAPER_NOT_FOUND`(없음·삭제됨)
- 상태와 무관하게 허용. `updatedAt`은 "상태가 바뀐 시각"이므로 갱신하지 않는다.
- 다운로드 `Content-Disposition`은 항상 현재 `filename`을 쓰므로 이름 변경이 저장 파일명에 그대로 반영된다.
- `S3FileStorage.presignDownload`는 파일명을 `attachment; filename="…"`에 그대로 잇고 있다. 헤더에 넣기 직전 `"`와 제어 문자만 제거하는 정리 함수를 둔다(DB 값은 그대로, BE 검증 추가 없음). 비ASCII는 지금처럼 그대로 둔다.

`DELETE /api/papers/{paperId}` — `deletePaper`

- 응답 `204`
- 오류: `401`, `403 FORBIDDEN`, `404 PAPER_NOT_FOUND`(없음·이미 삭제됨 — 채팅 세션 삭제와 같은 규칙)
- 설명: 논리 삭제. 이후 목록·status·content·download·chat 전부 404. 소속 채팅 세션도 논리 삭제. 원본·파싱 결과는 다른 사용자와 공유되므로 유지. 사용량 미복구.

### 제거·정정

- `POST /api/papers` 409 응답과 `Error.code` enum의 `DUPLICATE_FILENAME` 제거.
- `POST /api/papers` description의 "파일명 중복만 판정한다 (FT-003 Story 2)" 문단 → "이 시점에 내용 기반 판정은 하지 않는다. 파일명 중복은 허용한다" 로 정정.
- `PaperListItem.filename` description에 "사용자가 변경할 수 있다" 한 줄 추가.

## 3. BE 설계

### 3.1 스키마 (`be/docs/db/paper.sql`)

| 변경 | 내용 |
|---|---|
| 추가 | `deleted_at timestamp(6) with time zone` null. 값이 있으면 사용자 경로에서 보이지 않는다 |
| 제거 | `constraint uk_paper_owner_filename unique (owner_id, filename)` 및 그 주석 |
| 유지 | `filename varchar(255) not null` — 엔티티에서 `updatable = false`만 푼다 |

`Paper` 엔티티: `@UniqueConstraint` 제거, `filename` updatable, `deletedAt` 필드, `rename(String)`·`markDeleted(Instant)` 메서드.

local·dev 반영: `ddl-auto: update`는 컬럼을 추가하지만 **유니크 제약은 지우지 않는다**. 배포 전에 수동 실행:

```sql
alter table paper drop constraint if exists uk_paper_owner_filename;
```

prod: 갱신된 `paper.sql`이 산출물(첫 배포 전 DDL, 기존 규칙 동일).

### 3.2 삭제 행 가시성 — 전역 필터 대신 명시 조회

`@SQLRestriction` 같은 전역 필터를 쓰지 않는다. 사용량 정산(`DocumentTransitions.markParsedAndSettle` → `findIdsByDocumentId`)과 정체 정리(`StalePaperCleanup`)가 삭제 행을 못 보면 예약이 영원히 닫히지 않기 때문이다.

| 경로 | 조회 | 삭제 행 |
|---|---|---|
| 사용자 경로 6곳: `PaperStatusService`, `PaperContentQueryService`, `PaperDownloadService`, `PaperChatAccessValidator`, `PaperAccessRecorder`, `PaperUploadCompletionService` | `findActiveById(id)` = `findByIdAndDeletedAtIsNull` (신규) | 안 보임 → `PAPER_NOT_FOUND` |
| 목록 `findAllByOwnerIdOrderByRecentAccess` | `and p.deletedAt is null` 추가 | 안 보임 |
| 내부 경로: `PaperDocumentLinkService.verifyIdempotentLinkOrThrowExpired`, `DocumentTransitions.findIdsByDocumentId`, `StalePaperCleanup.findStaleUploadPendingIds` | 기존 `findById`·쿼리 유지 | 보임 |

이 분리로 얻는 동작:

- **PROCESSING 논문 삭제** → Document 파싱은 계속되고 종결 시 삭제된 paper의 예약도 함께 confirm/release 된다. 삭제 로직에 사용량 특수 처리가 없다.
- **UPLOAD_PENDING 논문 삭제**(다른 탭에서 업로드 중) → `complete`는 404. 레코드는 `document_id null`로 남아 정체 정리가 만료 처리하며 예약을 반납한다.
- `linkDocument`·`markExpired` CAS는 `deleted_at`을 보지 않는다 — 삭제 뒤에도 내부 전이는 그대로 진행되어야 하므로 의도된 동작이다.

### 3.3 서비스

`PaperManagementService` (신규, `paper/service`)

```
@Transactional
void delete(UUID paperId, UUID ownerId)
  paper = findActiveById → 없으면 PAPER_NOT_FOUND
  owner 불일치 → FORBIDDEN
  paperRepository.markDeleted(paperId, now) == 0 → PAPER_NOT_FOUND   // 동시 삭제 경쟁
  chatSessionRepository.markDeletedByPaperId(paperId, now)             // bulk update, deleted_at is null만

@Transactional
PaperListView rename(UUID paperId, UUID ownerId, String filename)
  normalized = filename.strip(); 비어 있거나 255자 초과 → VALIDATION_ERROR
  paper = findActiveById → PAPER_NOT_FOUND / owner 불일치 → FORBIDDEN
  paper.rename(normalized)  // dirty checking
  return views.listView(paper)
```

- `PaperRepository.markDeleted`: `update Paper set deletedAt=:now where id=:id and deletedAt is null` (`@Modifying`, CAS).
- `ChatSessionRepository.markDeletedByPaperId`: `update ChatSession set deletedAt=:now where paperId=:paperId and deletedAt is null`. FT-011 "논리 삭제된 세션도 생성 중이면 활성으로 센다" 규칙과 충돌 없음(동시 실행 카운트는 `deleted_at`을 보지 않는다).
- 컨트롤러: `PaperController`에 `@PatchMapping("/{paperId}")`(`@Valid` DTO `RenamePaperRequest`)·`@DeleteMapping("/{paperId}")` → `204`.

### 3.4 등록 (`PaperRegistrationService`)

- `deleteExpiredByOwnerAndFilename`·`existsByOwnerIdAndFilename` 호출과 두 repository 메서드 삭제.
- `saveAndFlush` + `DataIntegrityViolationException → 409` 변환 제거 → `save`.
- `ErrorCode.DUPLICATE_FILENAME` 삭제. 관련 테스트 삭제, "같은 파일명 두 번 등록 성공" 테스트 추가.
- 만료(EXPIRED) 잔재는 더 이상 자동으로 사라지지 않고 '실패' 행으로 남는다 → 사용자가 삭제로 정리한다. 이 동작 변화를 FT-002 Story 3 비고에 한 줄 남긴다.

## 4. FE 설계

### 4.1 API 클라이언트 (`api/papers.ts`)

```ts
renamePaper(paperId, filename): Promise<Paper>   // PATCH
deletePaper(paperId): Promise<void>                // DELETE, 204
```

`ApiError`에 `code`가 이미 실려 오므로 404 분기는 호출 측에서 한다.

### 4.2 컴포넌트

`routes/bookshelf/PaperRowMenu.tsx` — 케밥 버튼 + 드롭다운

- 목록 행·격자 카드가 같은 컴포넌트를 쓴다. 클릭·키 이벤트는 `stopPropagation`으로 행의 열기 핸들러와 분리.
- 닫기: 바깥 `mousedown`(AccountMenu와 같은 패턴), `Esc`, 항목 선택 후.
- 항목: **원본 PDF 다운로드**(`status !== 'COMPLETED'`면 disabled + title "분석 완료 후 다운로드할 수 있습니다") / **이름 변경** / 구분선 / **삭제**(danger 톤).
- 다운로드: `getDownloadUrl` → `window.location.assign(downloadUrl)`. presigned URL에 `Content-Disposition: attachment`가 서명돼 있어 페이지 이탈 없이 저장된다.
- 메뉴는 행 안에서 `position: absolute`로 연다(AccountMenu의 fixed와 다름 — 스크롤 목록이라).

`routes/bookshelf/PaperTitleEditor.tsx` — 인라인 이름 편집

- 표시 모드: 기존 제목 스타일 그대로. 편집 모드 진입은 부모가 메뉴 "이름 변경"에서 켜는 `editing` prop으로만 한다(제어 컴포넌트). 제목 자체에는 클릭·더블클릭 핸들러를 두지 않는다.
- 편집 모드: `<input value={stem}>` + 고정 텍스트 `.pdf`. 마운트 시 `focus()`·`select()`.
- 저장 조건: `Enter` 또는 blur. 취소: `Esc`. `stem.trim()`이 비었거나 원래와 같으면 요청 없이 표시 모드 복귀.
- 편집 중 `Enter`·`Space`·클릭은 `stopPropagation` — 행의 `role=button` 핸들러가 학습 페이지로 보내지 않게.
- 순수 함수 `splitPdfName(filename) → { stem, ext }`, `joinPdfName(stem, ext)`: 끝이 `.pdf`(대소문자 무관)면 분리, 아니면 `ext = ''`로 두고 저장 시 그대로 붙인다(확장자 없던 이름을 억지로 `.pdf`로 만들지 않는다).

`routes/bookshelf/ConfirmDialog.tsx` — 삭제 확인

- UploadDialog와 같은 오버레이 방식의 소형 모달. 제목 "논문을 삭제할까요?", 본문에 파일명·"채팅 기록도 함께 사라지며 되돌릴 수 없습니다. 등록 횟수는 복구되지 않습니다.", 버튼 취소 / 삭제(danger). 요청 중에는 버튼 disabled.

### 4.3 상태·캐시 (`BookshelfPage`)

| 동작 | 성공 | 실패 |
|---|---|---|
| 이름 변경 | 캐시 형태는 `{ papers: Paper[] }`. `setQueryData(['papers'], prev => ({ papers: prev.papers.map(...) }))`로 해당 항목의 `filename`만 교체 | 원복 + 토스트(에러 message) |
| 삭제 | 같은 방식으로 `papers`에서 항목 제거 + 토스트 "삭제했습니다". 검색 필터를 거친 결과(`filterPapers`) 기준으로 현재 페이지가 비면 `page - 1` | `404 PAPER_NOT_FOUND`면 이미 지워진 것 → 조용히 `invalidateQueries`. 그 외 토스트 |

낙관적 업데이트는 하지 않는다(응답이 빠르고 실패 원복이 더 복잡하다).

### 4.4 기타

- `design/components/icons.ts`에 `DotsThree`·`DownloadSimple`·`PencilSimple`·`Trash` 등록.
- `StudyPage`: 삭제된 논문 URL로 진입하면 status 404 → 기존 가드가 서재로 보낸다. 토스트 문구를 "삭제되었거나 없는 논문입니다"로 조정(404일 때만).
- `UploadDialog`의 `DUPLICATE_FILENAME` 언급 주석 정리. 처리 코드는 없다(공통 ApiError 표시).
- 격자 카드: 케밥은 썸네일 우상단, 제목은 목록과 같은 에디터를 쓴다.

## 5. project-docs 변경 (선행 PR)

| 문서 | 변경 |
|---|---|
| `contracts/frontend-backend/openapi.yaml` | §2 그대로. version 0.4.0 |
| `features/FT-002-서재.md` | Out of Scope에서 "논문 삭제 / 이름 변경" 제거. Story 5 AC에 진입점(행 메뉴) 추가. **Story 6 삭제**·**Story 7 이름 변경** 추가(`MVP` 아님, 태그는 기존 문서 관례 확인 후). Story 3 비고에 만료 행이 자동 정리되지 않음을 추가 |
| `features/FT-003-논문-등록-분석.md` | Story 2를 "시스템은 등록 요청을 검증하고 레코드를 생성한다"로 AC 정정(파일명 중복 판정 제거). Resolved 노트에 이력. In Scope의 "파일명/논문 중복 판정" 문구 정정 |
| `decisions/ADR-003-*.md` | Option C 아래에 "2026-09-05: 파일명 409 규칙은 YMC-369로 폐지" 한 줄 |
| `wireframes/frames/WF-016-*.md` | R4 Component Inventory에 C17 행 메뉴(케밥) 추가, 스케치에 `⋯` |
| `design/v2/Paper Bookshelf Page.dc.html` | 행에 케밥 버튼, 메뉴 열림 상태와 인라인 편집 상태 아트보드 |

## 6. 테스트

BE (`be/src/test`)

- `PaperManagementServiceTest`: 삭제 — 소유자 OK/타인 403/없음 404/재삭제 404/세션 마킹 확인/목록 제외. 이름 변경 — 정상/공백 400/256자 400/trim 적용/`updatedAt` 불변.
- 기존 중복 파일명 테스트 정리: `PaperRegistrationIntegrationTest`의 `rejectsDuplicateFilename`·`pendingRecordAlsoCountsAsDuplicate` 삭제 후 "같은 파일명 두 번 등록 성공" 추가. `PaperExpiryIntegrationTest`의 `reregisterReplacesExpiredRow`는 "만료 row 옆에 새 row가 추가된다(count 2)"로 재작성, `nonExpiredDuplicateStillRejected` 삭제.
- 기존 사용자 경로 서비스 테스트에 "삭제된 paper → 404" 케이스 1개씩. `PaperAccessRecorder`는 삭제 행을 갱신하지 않는지.
- §3.2의 약속 검증 2개: `DocumentTransitions` 종결 정산이 삭제된 PROCESSING paper도 포함하는지, `StalePaperCleanup`이 삭제된 UPLOAD_PENDING을 만료·반납하는지(기존 테스트 파일에 케이스 추가).
- `S3FileStorage` 정리 함수: `"`·CR/LF 제거, 한글은 그대로.
- 컨트롤러 슬라이스 테스트가 있는 패턴이면 PATCH/DELETE 각 1개.

FE (`fe/src`)

- `pdfName.test.ts`: `splitPdfName`/`joinPdfName` 경계(대문자 `.PDF`, 확장자 없음, 점 여러 개).
- `PaperRowMenu.test.tsx`: 열기/바깥 클릭 닫기/Esc/COMPLETED 아니면 다운로드 disabled/행 클릭 전파 차단.
- `PaperTitleEditor.test.tsx`: `editing` 진입 시 focus·select, Enter 저장, Esc 취소, 빈 값·동일 값은 onSave 미호출.
- `papers.test.ts`: `renamePaper`·`deletePaper` 요청 형태. 기존 실패 응답 테스트의 예시 코드 `DUPLICATE_FILENAME`을 다른 코드로 교체.

## 7. 수용 갭·열린 질문

- 삭제 후 같은 파일을 다시 올리면 Document가 이미 있으므로 즉시 COMPLETED로 연결된다(재파싱 없음). 의도된 동작.
- 삭제된 논문의 usage 기록은 남는다(사용량 미복구 규칙).
- **삭제 ↔ complete 경쟁**: `complete`가 살아 있는 행을 읽은 직후 삭제가 커밋되면 `linkDocument` CAS가 `deleted_at`을 보지 않아 삭제된 행에 Document가 연결되고 사용량이 정산될 수 있다. 사용자에게는 보이지 않고(이미 404) 사용량은 어차피 미복구 규칙이라 수용한다. CAS에 조건을 더하지 않는다.
- FT-002 신규 Story의 태그(`MVP` 여부)와 Story 번호는 문서 작성 시 Registry·기존 관례를 확인해 정한다.
