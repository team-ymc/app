# app/fe — 임시 업로드 UI (BE 검증용)

> ⚠️ **이건 진짜 FE가 아니다.** FT-003 백엔드가 브라우저에서 실제로 도는지 확인하려고 만든 **버릴 개발 도구**다. 진짜 FE 착수 시 이 폴더째 삭제한다.
>
> - **실행법** → [`app/README.md`](../README.md) (환경 → be → fe 순서). 여기엔 fe에만 해당하는 것만 적는다.
> - **왜 이렇게 만들었나**(설계 결정) → [`DESIGN.md`](./DESIGN.md)
> - Jira: [YMC-221](https://geunhh.atlassian.net/browse/YMC-221) · 기반 목업: `temp/논문 등록 처리 UI.html`

## 무엇을 검증하나

브라우저에서 PDF를 올려 `POST /api/papers` → S3 presigned PUT → `complete` → `status` 폴링이 **실제로** 도는지 눈으로 확인한다. 서버 간 테스트로는 안 잡히던 두 가지가 핵심이다:

- **S3 버킷 CORS** — 브라우저의 presigned PUT은 CORS를 탄다. (`infra`의 `bootstrap.sh`가 버킷 CORS를 넣는다)
- **presign의 Content-Type 서명** — PUT의 `Content-Type`이 서명값과 어긋나면 `SignatureDoesNotMatch`.

## 실행 중 알아둘 것

### `PROCESSING`에서 멈추는 건 정상이다

AI 파싱 워커가 아직 없어서, 업로드가 끝나면 논문은 `PROCESSING`에 머문다. **버그가 아니다.** 파싱 결과를 손으로 발행해 완료시킨다:

```bash
cd ../../infra/local
./publish-parse-result.sh <paperId> COMPLETED   # 완료 행으로 전환
./publish-parse-result.sh <paperId> FAILED      # 실패 행 + error.code
```

`<paperId>`는 업로드 후 화면 또는 `POST /api/papers` 응답에서 얻는다. (진짜 FE에는 이 단계가 없다 — 그땐 AI 워커가 결과를 발행한다.)

### 새로고침해도 서재가 유지된다

`GET /api/papers`(FT-002, BE는 YMC-223)가 이제 있다. `src/api.js`의 `listPapers()`가 마운트 시 실제 목록을 받아오므로 새로고침해도 서재가 비지 않는다. DESIGN.md D3("로컬 상태로 들고 있는다")는 이 엔드포인트가 생기며 폐기됐다 — `listPapers()` 접점은 그대로 두고 내부만 실제 `fetch`로 교체됐다.

### presigned URL은 10분 뒤 만료된다

파일 선택 후 오래 두면 PUT이 403난다. 화면에 에러가 그대로 뜬다. 자동 재발급은 하지 않는다.

### 완료 논문은 원본 PDF를 다운로드할 수 있다

서재 행이 `COMPLETED`면 다운로드 버튼이 뜬다. `GET /api/papers/{id}/download`로 presigned GET URL(`downloadUrl`)을 받아 `<a download>` 네비게이션으로 받는다 — 파일명은 서버의 `Content-Disposition`이 정한다(브라우저가 임의로 붙이지 않는다). `PROCESSING`/`FAILED` 행에는 버튼이 없다(FT-002 Story 5).

## 검증 시나리오

`DESIGN.md`의 Verification 6개 + 다운로드 단계를 손으로 훑는다:

1. 업로드 → S3 객체 확인(`awslocal s3 ls s3://ymc-documents --recursive`) → 서재 행이 "진행 중"
2. `GET /{paperId}/status` → `PROCESSING`, 큐 메시지 확인(`awslocal sqs receive-message`)
3. 수동 발행으로 완료 전환:
   ```bash
   cd ../../infra/local
   ./publish-parse-result.sh <paperId> COMPLETED
   ```
   서재 행이 "완료"로 바뀌고 다운로드 버튼이 뜬다.
4. **다운로드 버튼 클릭 → 원본 파일명(예: attention.pdf)으로 저장되는지 확인** (핵심 시나리오)
5. `./publish-parse-result.sh <다른paperId> FAILED` → 실패 행, 다운로드 버튼 없음
6. complete 전(`UPLOAD_PENDING`) paperId로 `curl http://localhost:8080/api/papers/<id>/download` → 409 `UPLOAD_NOT_FOUND`
7. 같은 파일명 재업로드 → `409 DUPLICATE_FILENAME`이 화면에 노출

## 원칙

**BE 코드는 건드리지 않는다.** 검증 중 BE/계약 문제를 발견하면 여기서 고치지 말고 별도 이슈로 등록한다.
