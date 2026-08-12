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
- `CreatePaperResponse`에 `uploadHeaders: Record<string, string>` 추가.
- `uploadToS3(uploadUrl, file, headers, onProgress?)` — Content-Type 하드코딩을 제거하고
  `headers` 맵 전체를 `setRequestHeader`로 전송. 계약이 헤더를 추가해도 FE 무수정.
- `uploadToS3`는 status 400이면서 응답 body에 `BadDigest`가 있으면 checksum 불일치로
  식별되는 에러를 던진다(아래 에러 처리).

### `fe/src/routes/bookshelf/UploadDialog.tsx`

phase는 3단계 그대로 두고 `checksum: string | null` state를 추가한다.

- 파일 선택 시 즉시 해시 시작. 완료 전엔 업로드 버튼 비활성화, 파일 카드 보조 라벨을
  "파일 검사 중…"으로 표시.
- 파일 교체·제거 시 진행 중인 해시 결과는 버린다 — resolve 시점에 현재 선택 파일과
  동일한지 확인하고 다르면 무시.
- 해시 실패(비정상 브라우저 등)는 에러 표시 후 idle 복귀.
- `startUpload`는 checksum이 준비된 뒤에만 진행하고, S3 PUT에는
  `created.uploadHeaders`를 그대로 넘긴다.

## 에러 처리

- **S3 400 BadDigest** (선택 후 파일 내용 변경 등): "파일 내용이 선택 시점과 달라졌습니다.
  파일을 다시 선택해 주세요"로 표시하고 checksum을 비워 재선택·재해시를 유도.
- 그 외 S3 실패·complete 4xx는 기존대로 다이얼로그 안에 노출.

**스코프 제외**: complete 409 `UPLOAD_NOT_FOUND`의 자동 재시도(동시 경합 시 일시 409,
YMC-282 Jira 코멘트)는 이번 범위에서 뺀다. 수동 재시도가 createPaper부터 다시 타서
DUPLICATE_FILENAME에 걸리는 문제는 이 변경 전부터 있던 기존 갭으로, 후속 티켓 후보.

## 테스트

- `fileChecksum.test.ts` — 알려진 벡터(빈 입력, 고정 바이트열)로 Base64 44자 형식·값 검증.
- `papers.test.ts` — create body의 `checksumSha256`, `uploadToS3`의 헤더 전송·BadDigest 식별 단언.
- `UploadDialog` — 해시 완료 전 업로드 버튼 비활성화 1건.

## 검증

`npm run test`, `npm run typecheck` + 로컬 스택(BE main + LocalStack)에서 실제 업로드
1회 확인. 로컬 버킷 CORS는 `AllowedHeaders: ["*"]`라 추가 설정 불필요(확인 2026-08-13).
