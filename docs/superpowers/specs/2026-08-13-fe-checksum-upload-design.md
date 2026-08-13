# FE 업로드 checksum 전송 (YMC-282)

FE↔BE 계약 0.3.0의 checksum 검증 업로드를 FE에 반영한다. 업로드 전 SHA-256을 계산해
create 요청에 싣고, presigned PUT에는 create 응답의 `uploadHeaders`를 그대로 전송한다.
검증은 S3가 하고(ADR-003 §3), 중복 여부는 업로드 완료 전에 노출하지 않는다.

BE(app#41, main 머지됨)는 `checksumSha256` 없는 create를 400으로 거절하므로,
이 변경 전의 FE는 main BE와 맞물리지 않는다. dev 배포는 BE와 동시에 한다.

## 변경

### `fe/src/routes/bookshelf/fileChecksum.ts` (신규)

`sha256Base64(file: Blob): Promise<string>` 하나만 둔다.
`file.arrayBuffer()` → `crypto.subtle.digest('SHA-256', …)` → 표준 Base64(44자, `=` padding).

50 MiB 상한에서 전체 계산은 통상 0.5초 미만(네이티브 digest ~수십 ms)이라
스트리밍·워커·% 진행률을 두지 않는다. 티켓 AC도 이에 맞춰 완화됨(2026-08-13).

### `fe/src/api/types.ts`, `fe/src/api/papers.ts`

- `createPaper(filename, contentType, size, checksumSha256)` — body에 `checksumSha256` 추가.
- `CreatePaperResponse`에 `uploadHeaders` 추가. 타입은 계약의 필수 헤더를 그대로 강제한다:

  ```ts
  type PaperUploadHeaders = {
    'Content-Type': 'application/pdf';
    'x-amz-checksum-sha256': string;
  } & Record<string, string>;
  ```

- `uploadToS3(uploadUrl, file, headers, onProgress?)` — Content-Type 하드코딩을 제거하고
  `headers` 맵 전체를 `setRequestHeader`로 전송. 계약이 헤더를 추가해도 FE 무수정.
- `uploadToS3`는 status 400이면서 응답 body에 `BadDigest`가 있으면 UI 문구를 실은
  `Error`를 던진다: "파일 검증에 실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다".
  전용 에러 클래스는 두지 않는다 — 다이얼로그는 분기 없이 message를 그대로 표시한다.

### `fe/src/routes/bookshelf/UploadDialog.tsx`

phase·state는 그대로 두고 해시를 `startUpload` 안에서 계산한다 — **추가 state 없음**.

- 업로드 클릭 → 'uploading' 전환 → `sha256Base64(selectedFile)` → `createPaper(…, checksum)`
  → `uploadToS3(…, created.uploadHeaders, …)`. 다이얼로그 수정은 이 함수 한 곳이다.
- 계산 ~0.5초는 기존 "업로드 중… 0%" 표시가 덮고, uploading phase가 이미 모든
  상호작용을 잠근다. 선택~사용 사이 비동기 틈이 없으므로 stale 해시 경합이 성립하지
  않는다 — 잠금·라벨·세대 토큰 전부 불필요.
- 해시 실패(비정상 브라우저 등)는 기존 catch가 에러 표시 + file-selected 복귀로 처리.

## 에러 처리

- **S3 400 BadDigest** (선택~PUT 사이 파일 내용 변경, 극히 드묾): "파일 검증에
  실패했습니다. 파일이 업로드 중 변경되었을 수 있습니다"로 표시만 한다. 재선택·재해시
  유도는 하지 않는다 — create가 이미 만든 UPLOAD_PENDING 레코드가 같은 파일명 재등록을
  DUPLICATE_FILENAME으로 막고(BE 중복 검사는 상태 무관, paper 삭제 API 없음), 기존
  presigned URL도 새 해시와 서명이 안 맞아 제자리 복구가 불가능하다. 같은 파일명
  재업로드 미지원은 계약(FT-003 Out of Scope)이 선언한 기존 MVP 갭.
- 그 외 S3 실패·complete 4xx는 기존대로 다이얼로그 안에 노출.

**스코프 제외** (모두 기존 갭 또는 후속 티켓 후보):

- complete 409 `UPLOAD_NOT_FOUND` 자동 재시도(동시 경합 시 일시 409, YMC-282 Jira 코멘트).
- create 후 실패 시 같은 파일명 재등록 불가(위 BadDigest 항목 참고) — re-presign/삭제
  API가 필요하며 이 티켓 밖.
- complete가 기존 Document 재사용으로 즉시 COMPLETED/FAILED를 반환하는 경우의
  상태 인지형 토스트(현재는 일괄 "분석이 시작됩니다"). 목록 invalidate로 실제 상태는
  서가에 반영되므로 FAILED 재파싱 UX 후속 티켓에 묶는다.
- BE 통합 테스트의 checksum 불일치 단언 보강(FE 티켓 밖).

## 테스트

- `fileChecksum.test.ts` — 표준 검증 벡터(예: `"abc"`, 정답 공개된 SHA-256 벡터)로
  Base64 44자 형식·값 검증. 빈 입력 벡터는 두지 않는다 — 계약이 `size >= 1`이라 도달
  불가. jsdom `Blob`엔 `arrayBuffer()`가 없으므로(실측 2026-08-13) 테스트 셋업에서
  `Blob.prototype.arrayBuffer`를 shim한다. `crypto.subtle`은 Node webcrypto로 존재.
- `papers.test.ts` — create body의 `checksumSha256`, `uploadToS3`의 헤더 전송·
  400 BadDigest → UI 문구 Error 단언.
- `UploadDialog` — checksum·uploadHeaders 배선(create·PUT에 올바른 값 전달),
  BadDigest 에러 문구 표시. 2건.

## 검증

`npm run test`, `npm run typecheck` + 로컬 스택(BE main + LocalStack)에서 실제 업로드
1회 확인. 로컬 버킷 CORS는 `AllowedHeaders: ["*"]`라 추가 설정 불필요(확인 2026-08-13).
