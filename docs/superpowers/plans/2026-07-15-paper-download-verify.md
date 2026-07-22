# 논문 다운로드 + throwaway 검증 UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 논문 원본 PDF 다운로드를 계약·BE에 정식으로 추가하고, 등록→폴링→서재→다운로드 여정 전체를 브라우저로 검증하는 throwaway UI를 만든다.

**Architecture:** 다운로드는 업로드(프리사인 PUT)와 대칭인 **프리사인 GET** 방식이다. BE는 `GET /api/papers/{paperId}/download`로 프리사인 GET URL을 JSON으로 발급하고, 브라우저가 `<a download>` 네비게이션으로 S3에서 직접 받는다(바이트는 BE를 거치지 않음, ADR-001 일관). UI는 temp/ 목업을 이식한 검증 도구지만 연동 층(`fe/src/api.js`)은 진짜 FE가 재사용할 실코드다.

**Tech Stack:** Spring Boot(Java) · AWS SDK v2 S3Presigner · PostgreSQL/LocalStack Testcontainers · Vite + React · LocalStack(awslocal/aws-cli)

## Global Constraints

- **3개 repo가 분리돼 있다.** 커밋 위치를 태스크마다 명시: `project-docs`(feature·계약), `app`(BE·FE), `infra`(스크립트).
- **feature → contract → code 순서.** 계약보다 feature가, 코드보다 계약이 앞선다.
- **계약 SSOT는 `project-docs/contracts/openapi.yaml`.** 에러 코드 enum도 계약 소유 — **새 코드 만들지 않음**. 기존 `PAPER_NOT_FOUND`(404)·`UPLOAD_NOT_FOUND`(409) 재사용.
- **계약 버전 0.1.0 → 0.1.1** (호환 엔드포인트 추가 = patch, `contracts/README.md` 규칙).
- **BE 패키지 규칙(be/CLAUDE.md):** `com.ymc.paper.{api,service,domain,infra}`. 빈은 `@RequiredArgsConstructor`+`final`. 포트 인터페이스는 `service/`, 구현은 `infra/`. 엔티티는 손대지 않는다.
- **프리사인은 로컬 서명 계산** — 외부 I/O 없음. `@Transactional(readOnly=true)` 안에서 안전.
- **SDK API(`presignGetObject`)는 기억 말고 context7로 해당 버전 문서 확인 후 작성** (be/CLAUDE.md).
- **다운로드 대상은 원본 PDF뿐.** 파일명은 프리사인의 `Content-Disposition: attachment`가 결정.
- **공유 값:** region `ap-northeast-2` · 버킷 `ymc-documents` · 큐 `parse-requests`/`parse-results` · FE origin `http://localhost:5173`.
- **참조 설계:** `app/docs/superpowers/specs/2026-07-15-paper-download-verify-ui-design.md`, `app/fe/DESIGN.md`(D1~D7).

---

## Setup — 브랜치 (티켓별, main 기준)

작업은 두 티켓으로 나뉜다. **YMC-223**(BE·계약·docs) → **YMC-221**(FE·infra) 순.

| repo | 티켓 | 브랜치 | 분기 기준 |
|---|---|---|---|
| project-docs | YMC-223 | `YMC-223-paper-download-list` | main (미커밋 작업 stash) |
| app (be/·docs) | YMC-223 | `YMC-223-paper-download-list` | main |
| infra | YMC-221 | `YMC-221-verify-ui` | main |
| app (fe/) | YMC-221 | `YMC-221-verify-ui` | **app의 YMC-223 브랜치 위** |

- [ ] **project-docs**: `git -C project-docs stash push -u` → `checkout main` → `checkout -b YMC-223-paper-download-list`
- [ ] **app**: `git -C app checkout -b YMC-223-paper-download-list` → 설계·계획 문서 커밋:
  ```bash
  git -C app add docs/superpowers/specs/2026-07-15-paper-download-verify-ui-design.md \
                 docs/superpowers/plans/2026-07-15-paper-download-verify.md
  git -C app commit -m "docs(YMC-223): 논문 다운로드+목록+검증 UI 설계·계획"
  ```
- [ ] **infra**: `git -C infra checkout main` → `checkout -b YMC-221-verify-ui`
- [ ] **Phase 3 착수 직전** app fe 브랜치: `git -C app checkout YMC-223-paper-download-list` → `checkout -b YMC-221-verify-ui` (be 코드 위에 스택 — fe 로컬 검증이 be 엔드포인트에 의존)

> app repo가 두 티켓(be=223, fe=221)을 담는다. be→fe 순서로 하고, fe 브랜치는 be 브랜치에서 뗀다. PR은 223 먼저, 221 나중.

---

## Phase 0 — project-docs (feature → contract) · YMC-223

### Task 0: FT-002에 다운로드 Story 추가

**Files:**
- Modify: `project-docs/features/FT-002-서재.md` (Stories 섹션 끝, Story 4 뒤)

**Interfaces:**
- Produces: 계약(Task 1)이 참조할 "완료된 논문 원본 PDF 다운로드" Story.

- [ ] **Step 1: Story 추가**

`## 4. Stories`의 Story 4 뒤에 삽입:

```markdown
### Story 5. 사용자는 완료된 논문의 원본 PDF를 다운로드할 수 있다 `MVP`

- type: USER
- Source Userflows: UF-002 Step 5
- Acceptance Criteria:
  - `COMPLETED` 상태 논문 행에서 원본 PDF 다운로드를 제공한다.
  - `PROCESSING`/`FAILED` 행에는 다운로드를 제공하지 않는다.
  - 저장되는 파일명은 등록 시 원본 파일명(`filename`)을 따른다(파싱이 추출한 제목이 아님).
  - 다운로드는 BE가 발급한 presigned GET URL로 브라우저가 S3에서 직접 받는다(ADR-001 대칭). 파일 바이트는 BE를 거치지 않는다.
- Depends on: Story 3
```

`### Out of Scope`에 한 줄 추가(삭제·이름변경과 나란히):

```markdown
- 다운로드 후 재분석 / 파일 교체 → 후속(post-MVP)
```

- [ ] **Step 2: 커밋 (project-docs repo)**

```bash
cd project-docs
git add features/FT-002-서재.md
git commit -m "docs(features): FT-002에 원본 PDF 다운로드 Story 추가"
```

---

### Task 1: openapi에 다운로드 엔드포인트 추가 (0.1.1)

**Files:**
- Modify: `project-docs/contracts/openapi.yaml` (version, paths, components.schemas)
- Modify: `project-docs/contracts/README.md` (버전 줄)

**Interfaces:**
- Produces: `GET /api/papers/{paperId}/download` → `PaperDownload{downloadUrl, expiresAt}`. BE(Phase 1)·FE(Phase 3)가 이 형태를 구현.

- [ ] **Step 1: version 올리기**

`info.version`을 `0.1.0` → `0.1.1`로 바꾼다.

- [ ] **Step 2: path 추가**

`paths:` 아래 `/api/papers/{paperId}/status` 뒤에 삽입:

```yaml
  /api/papers/{paperId}/download:
    get:
      operationId: getPaperDownloadUrl
      summary: 원본 PDF 다운로드 URL 발급
      description: |
        완료된 논문의 원본 PDF를 받기 위한 presigned GET URL을 발급한다.
        브라우저는 이 URL로 S3에서 직접 내려받는다 — 파일 바이트는 BE를 거치지 않는다 (ADR-001, 업로드와 대칭).
        URL에는 Content-Disposition(attachment; filename=원본파일명)이 서명돼 있어 원본 파일명으로 저장된다.

        가용 조건은 "S3에 원본 객체가 있음"이다(status가 UPLOADED 이상). UPLOAD_PENDING/EXPIRED는 409.
        "완료 시에만 다운로드"라는 제품 규칙(FT-002 Story 5)은 FE가 COMPLETED 행에서만 노출해 구현한다.
      tags: [papers]
      parameters:
        - name: paperId
          in: path
          required: true
          schema: { type: string, format: uuid }
      responses:
        "200":
          description: presigned GET URL
          content:
            application/json:
              schema: { $ref: "#/components/schemas/PaperDownload" }
        "404":
          description: 존재하지 않는 paperId. code PAPER_NOT_FOUND
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "409":
          description: 원본 객체가 아직 없음(UPLOAD_PENDING/EXPIRED). code UPLOAD_NOT_FOUND
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
```

- [ ] **Step 3: schema 추가**

`components.schemas`에 `PaperCreated` 뒤 등 적당한 위치에 삽입:

```yaml
    PaperDownload:
      type: object
      required: [downloadUrl, expiresAt]
      description: 원본 PDF를 받기 위한 presigned GET URL. FE는 이 URL로 브라우저가 직접 다운로드한다.
      properties:
        downloadUrl:
          type: string
          format: uri
          description: S3 presigned GET URL. Content-Disposition(attachment; filename)이 서명에 포함된다.
        expiresAt:
          type: string
          format: date-time
          description: presigned URL 만료 시각.
```

- [ ] **Step 4: README 버전 줄 갱신**

`contracts/README.md`의 구조 표에서 `openapi.yaml ... 0.1.0 확정`을 `0.1.1`로 갱신.

- [ ] **Step 5: 커밋 (project-docs repo)**

```bash
cd project-docs
git add contracts/openapi.yaml contracts/README.md
git commit -m "docs(contracts): GET /papers/{id}/download 추가 (0.1.1)"
```

---

## Phase 1 — BE (app repo) · YMC-223

### Task 2: FileStorage.presignDownload — 포트 + S3 구현

**Files:**
- Create: `app/be/src/main/java/com/ymc/paper/service/port/PresignedDownload.java`
- Modify: `app/be/src/main/java/com/ymc/paper/service/port/FileStorage.java`
- Modify: `app/be/src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java`
- Test: `app/be/src/test/java/com/ymc/paper/infra/storage/S3FileStorageDownloadIntegrationTest.java`

**Interfaces:**
- Produces: `FileStorage.presignDownload(String fileKey, String filename)` → `PresignedDownload(String url, Instant expiresAt)`. Task 3이 소비.

- [ ] **Step 1: context7로 SDK 확인**

context7로 `software.amazon.awssdk.services.s3.presigner.S3Presigner#presignGetObject`와 `GetObjectPresignRequest`·`GetObjectRequest.responseContentDisposition`의 현재 시그니처를 확인한다. (build.gradle의 AWS SDK 버전 기준)

- [ ] **Step 2: PresignedDownload 레코드 생성**

`PresignedUpload`와 대칭:

```java
package com.ymc.paper.service.port;

import java.time.Instant;

/** 발급된 presigned GET URL과 만료 시각 (계약 `PaperDownload.downloadUrl`·`expiresAt`). */
public record PresignedDownload(String url, Instant expiresAt) {
}
```

- [ ] **Step 3: 포트에 메서드 추가**

`FileStorage.java`에 추가:

```java
    /**
     * 원본 파일을 내려받는 presigned GET URL을 발급한다. 만료는 구현이 설정에서 정한다.
     *
     * <p>서명에 Content-Disposition(attachment; filename=주어진 filename)을 포함해
     * 브라우저가 원본 파일명으로 저장하게 한다.
     */
    PresignedDownload presignDownload(String fileKey, String filename);
```

(import 추가: `com.ymc.paper.service.port.PresignedDownload`는 같은 패키지라 불필요.)

- [ ] **Step 4: 실패하는 테스트 작성**

```java
package com.ymc.paper.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.service.port.PresignedDownload;
import com.ymc.support.IntegrationTest;

/** spec: paper-download (Task 2). presign GET URL에 content-disposition이 서명되는지. */
class S3FileStorageDownloadIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("presignDownload: attachment content-disposition과 원본 파일명이 서명 쿼리에 실린다")
    void signsContentDisposition() {
        PresignedDownload d = fileStorage.presignDownload(
                "papers/550e8400-e29b-41d4-a716-446655440000/original.pdf",
                "attention.pdf");

        assertThat(d.url()).contains("response-content-disposition");
        assertThat(d.url()).contains("attention.pdf");
        assertThat(d.url()).contains("X-Amz-Signature");
        assertThat(d.expiresAt()).isNotNull();
    }
}
```

- [ ] **Step 5: 테스트 실패 확인**

Run: `cd app/be && ./gradlew test --tests '*S3FileStorageDownloadIntegrationTest*'`
Expected: 컴파일 실패 (`presignDownload` 미구현).

- [ ] **Step 6: S3FileStorage에 구현 추가**

`S3FileStorage.java`에 메서드 추가 + import(`GetObjectRequest`, `GetObjectPresignRequest`, `PresignedGetObjectRequest`, `PresignedDownload`):

```java
    @Override
    public PresignedDownload presignDownload(String fileKey, String filename) {
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(props.s3().presignExpiry())
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(props.s3().bucket())
                                .key(fileKey)
                                .responseContentDisposition(
                                        "attachment; filename=\"" + filename + "\"")
                                .build())
                        .build());
        return new PresignedDownload(presigned.url().toString(), presigned.expiration());
    }
```

> 비ASCII(한글) 파일명은 이 단순 인용으로 헤더가 깨질 수 있다 — 검증용 테스트 PDF는 ASCII 파일명을 쓴다. RFC 5987(`filename*=UTF-8''...`) 처리는 진짜 FE 착수 시의 후속으로 남긴다.

- [ ] **Step 7: 테스트 통과 확인**

Run: `cd app/be && ./gradlew test --tests '*S3FileStorageDownloadIntegrationTest*'`
Expected: PASS

- [ ] **Step 8: 커밋 (app repo)**

```bash
cd app
git add be/src/main/java/com/ymc/paper/service/port/PresignedDownload.java \
        be/src/main/java/com/ymc/paper/service/port/FileStorage.java \
        be/src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java \
        be/src/test/java/com/ymc/paper/infra/storage/S3FileStorageDownloadIntegrationTest.java
git commit -m "feat(paper): S3 presigned 다운로드 URL 발급 포트/구현"
```

---

### Task 3: 다운로드 엔드포인트 — 서비스 + DTO + 컨트롤러

**Files:**
- Create: `app/be/src/main/java/com/ymc/paper/service/PaperDownloadService.java`
- Create: `app/be/src/main/java/com/ymc/paper/api/dto/PaperDownloadResponse.java`
- Modify: `app/be/src/main/java/com/ymc/paper/api/PaperController.java`
- Test: `app/be/src/test/java/com/ymc/paper/api/PaperDownloadIntegrationTest.java`

**Interfaces:**
- Consumes: `FileStorage.presignDownload` (Task 2), `PaperRepository.findById`, `ApiException`/`ErrorCode`.
- Produces: `GET /api/papers/{paperId}/download` → `PaperDownloadResponse(String downloadUrl, Instant expiresAt)`.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.ymc.paper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** spec: paper-download (Task 3). */
class PaperDownloadIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("업로드된(PROCESSING) 논문: 200과 {downloadUrl, expiresAt}")
    void returnsDownloadUrlForUploadedPaper() throws Exception {
        Paper paper = givenProcessingPaper("attention.pdf");

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("완료된 논문: 200과 다운로드 URL")
    void returnsDownloadUrlForCompletedPaper() throws Exception {
        Paper paper = givenProcessingPaper("done.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").isNotEmpty());
    }

    @Test
    @DisplayName("업로드 전(UPLOAD_PENDING): 409 UPLOAD_NOT_FOUND")
    void rejectsPendingPaper() throws Exception {
        Paper paper = givenPendingPaper("pending.pdf");

        mockMvc.perform(get("/api/papers/{id}/download", paper.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 paperId: 404 PAPER_NOT_FOUND")
    void rejectsUnknownPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/download", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("UUID가 아닌 paperId: 400 VALIDATION_ERROR")
    void rejectsMalformedPaperId() throws Exception {
        mockMvc.perform(get("/api/papers/{id}/download", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd app/be && ./gradlew test --tests '*PaperDownloadIntegrationTest*'`
Expected: 컴파일 실패 (엔드포인트/서비스 없음).

- [ ] **Step 3: PaperDownloadService 작성**

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.PresignedDownload;

import lombok.RequiredArgsConstructor;

/**
 * 원본 PDF 다운로드용 presigned GET URL 발급 (FT-002 Story 5).
 * 가용 조건은 "S3에 원본 객체가 있음" = status가 UPLOADED 이상이다.
 * 제품 규칙(완료 시에만 노출)은 FE가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PaperDownloadService {

    private final PaperRepository paperRepository;
    private final FileStorage fileStorage;

    /**
     * @throws ApiException {@code PAPER_NOT_FOUND} — 존재하지 않는 paperId
     * @throws ApiException {@code UPLOAD_NOT_FOUND} — 원본 객체가 아직 없음(UPLOAD_PENDING/EXPIRED)
     */
    @Transactional(readOnly = true)
    public PresignedDownload download(UUID paperId) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));

        PaperStatus status = paper.getStatus();
        if (status == PaperStatus.UPLOAD_PENDING || status == PaperStatus.EXPIRED) {
            throw new ApiException(
                    ErrorCode.UPLOAD_NOT_FOUND, "업로드된 원본 파일이 없습니다: " + paperId);
        }
        return fileStorage.presignDownload(paper.getFileKey(), paper.getFilename());
    }
}
```

- [ ] **Step 4: PaperDownloadResponse DTO 작성**

```java
package com.ymc.paper.api.dto;

import java.time.Instant;

import com.ymc.paper.service.port.PresignedDownload;

/** 계약 `PaperDownload`. */
public record PaperDownloadResponse(String downloadUrl, Instant expiresAt) {

    public static PaperDownloadResponse from(PresignedDownload presigned) {
        return new PaperDownloadResponse(presigned.url(), presigned.expiresAt());
    }
}
```

- [ ] **Step 5: 컨트롤러에 엔드포인트 추가**

`PaperController.java`에 필드·메서드 추가:

```java
    private final PaperDownloadService downloadService;
```

(생성자는 `@RequiredArgsConstructor`가 생성 — import `com.ymc.paper.service.PaperDownloadService`, `com.ymc.paper.api.dto.PaperDownloadResponse` 추가)

```java
    /** 원본 PDF 다운로드 URL 발급. */
    @GetMapping("/{paperId}/download")
    public PaperDownloadResponse download(@PathVariable UUID paperId) {
        return PaperDownloadResponse.from(downloadService.download(paperId));
    }
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd app/be && ./gradlew test --tests '*PaperDownloadIntegrationTest*'`
Expected: PASS (5 tests)

- [ ] **Step 7: 전체 BE 테스트 회귀 확인**

Run: `cd app/be && ./gradlew test`
Expected: BUILD SUCCESSFUL (기존 테스트 포함 전부 통과)

- [ ] **Step 8: 커밋 (app repo)**

```bash
cd app
git add be/src/main/java/com/ymc/paper/service/PaperDownloadService.java \
        be/src/main/java/com/ymc/paper/api/dto/PaperDownloadResponse.java \
        be/src/main/java/com/ymc/paper/api/PaperController.java \
        be/src/test/java/com/ymc/paper/api/PaperDownloadIntegrationTest.java
git commit -m "feat(paper): 원본 PDF 다운로드 엔드포인트 (GET /papers/{id}/download)"
```

---

### Task 3B: 서재 목록 조회 (GET /api/papers) · YMC-223

**Files:**
- Modify: `app/be/src/main/java/com/ymc/paper/domain/PaperRepository.java`
- Create: `app/be/src/main/java/com/ymc/paper/service/PaperListView.java`
- Create: `app/be/src/main/java/com/ymc/paper/service/PaperListService.java`
- Create: `app/be/src/main/java/com/ymc/paper/api/dto/PaperListResponse.java`
- Modify: `app/be/src/main/java/com/ymc/paper/api/PaperController.java`
- Test: `app/be/src/test/java/com/ymc/paper/api/PaperListIntegrationTest.java`

**Interfaces:**
- Consumes: `PaperRepository`, `AppProperties.fixedOwnerId()`, `Paper` getters.
- Produces: `GET /api/papers` → `PaperListResponse{papers:[{paperId, filename, status, createdAt, updatedAt}]}` (계약 `PaperList`). Task 6·7의 `listPapers()`가 소비.

계약(openapi 0.1.0)에 이미 `listPapers`가 있다 — **계약 변경 없음, BE 구현만**. 정렬·페이지네이션 없음(계약대로 단순 전체). 인증 없음 — 고정 owner 전체 반환.

- [ ] **Step 1: 실패하는 통합 테스트 작성**

```java
package com.ymc.paper.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

/** spec: paper-list (Task 3B). */
class PaperListIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("빈 서재: 200과 papers: []")
    void returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/papers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers").isArray())
                .andExpect(jsonPath("$.papers").isEmpty());
    }

    @Test
    @DisplayName("등록된 논문: paperId·filename·status·createdAt·updatedAt 행 반환")
    void returnsRegisteredPapers() throws Exception {
        Paper paper = givenProcessingPaper("attention.pdf");

        mockMvc.perform(get("/api/papers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(1))
                .andExpect(jsonPath("$.papers[0].paperId").value(paper.getId().toString()))
                .andExpect(jsonPath("$.papers[0].filename").value("attention.pdf"))
                .andExpect(jsonPath("$.papers[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.papers[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.papers[0].updatedAt").isNotEmpty());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd app/be && ./gradlew test --tests '*PaperListIntegrationTest*'`
Expected: 컴파일 실패 (엔드포인트 없음).

- [ ] **Step 3: repository 파생 쿼리 추가**

`PaperRepository.java`에 추가 (import `java.util.List` 필요):

```java
    /** 서재 목록. 고정 owner 전체 (정렬·페이징 없음, 계약대로 단순 전체). */
    List<Paper> findAllByOwnerId(UUID ownerId);
```

- [ ] **Step 4: PaperListView (service record) 생성**

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;

/** 목록 응답의 재료. 엔티티를 api로 넘기지 않기 위한 값 (be/CLAUDE.md). */
public record PaperListView(UUID paperId, String filename, PaperStatus status,
                            Instant createdAt, Instant updatedAt) {

    public static PaperListView from(Paper paper) {
        return new PaperListView(paper.getId(), paper.getFilename(), paper.getStatus(),
                paper.getCreatedAt(), paper.getUpdatedAt());
    }
}
```

- [ ] **Step 5: PaperListService 생성**

```java
package com.ymc.paper.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.config.AppProperties;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/** 서재 목록 조회 (FT-002). 인증 없음 — 고정 owner 전체. 정렬·페이지네이션 없음(계약). */
@Service
@RequiredArgsConstructor
public class PaperListService {

    private final PaperRepository paperRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public List<PaperListView> list() {
        return paperRepository.findAllByOwnerId(appProperties.fixedOwnerId())
                .stream()
                .map(PaperListView::from)
                .toList();
    }
}
```

- [ ] **Step 6: PaperListResponse DTO 생성**

```java
package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperListView;

/** 계약 `PaperList`. */
public record PaperListResponse(List<Item> papers) {

    public static PaperListResponse from(List<PaperListView> views) {
        return new PaperListResponse(views.stream().map(Item::from).toList());
    }

    /** 계약 `PaperListItem`. */
    public record Item(UUID paperId, String filename, PaperStatus status,
                       Instant createdAt, Instant updatedAt) {
        static Item from(PaperListView v) {
            return new Item(v.paperId(), v.filename(), v.status(), v.createdAt(), v.updatedAt());
        }
    }
}
```

- [ ] **Step 7: 컨트롤러에 GET 추가**

`PaperController.java`에 필드·메서드 추가 (import `PaperListService`, `PaperListResponse`):

```java
    private final PaperListService listService;
```

```java
    /** 서재 목록 조회. */
    @GetMapping
    public PaperListResponse list() {
        return PaperListResponse.from(listService.list());
    }
```

- [ ] **Step 8: 테스트 통과 + 회귀 확인**

Run: `cd app/be && ./gradlew test`
Expected: BUILD SUCCESSFUL (신규 2 + 기존 전부)

- [ ] **Step 9: 커밋 (app repo, YMC-223 브랜치)**

```bash
cd app
git add be/src/main/java/com/ymc/paper/domain/PaperRepository.java \
        be/src/main/java/com/ymc/paper/service/PaperListView.java \
        be/src/main/java/com/ymc/paper/service/PaperListService.java \
        be/src/main/java/com/ymc/paper/api/dto/PaperListResponse.java \
        be/src/main/java/com/ymc/paper/api/PaperController.java \
        be/src/test/java/com/ymc/paper/api/PaperListIntegrationTest.java
git commit -m "feat(paper): 서재 목록 조회 (GET /api/papers) [YMC-223]"
```

---

## Phase 2 — infra (infra repo) · YMC-221

### Task 4: 버킷 CORS(PUT) 추가

**Files:**
- Modify: `infra/local/bootstrap.sh`

**Interfaces:**
- Produces: `ymc-documents` 버킷이 `http://localhost:5173`에서의 PUT을 허용 → 브라우저 프리사인 업로드 가능(DESIGN.md D5). 다운로드는 네비게이션이라 GET CORS 불필요.

- [ ] **Step 1: CORS 규칙 추가**

`awslocal s3 mb s3://ymc-documents ...` 줄 **뒤에** 삽입:

```bash
# 브라우저 프리사인 PUT(업로드)은 CORS를 탄다 (DESIGN.md D5). 다운로드는 네비게이션이라 GET 불필요.
awslocal s3api put-bucket-cors --bucket ymc-documents --region "$REGION" --cors-configuration '{
  "CORSRules": [
    {
      "AllowedOrigins": ["http://localhost:5173"],
      "AllowedMethods": ["PUT"],
      "AllowedHeaders": ["*"],
      "ExposeHeaders": ["ETag"]
    }
  ]
}'
```

- [ ] **Step 2: 적용·검증**

Run:
```bash
cd infra/local && docker compose down && docker compose up -d
# LocalStack ready 후:
docker compose exec localstack awslocal s3api get-bucket-cors --bucket ymc-documents --region ap-northeast-2
```
Expected: `AllowedMethods: ["PUT"]`, `AllowedOrigins: ["http://localhost:5173"]` 출력.

- [ ] **Step 3: 커밋 (infra repo)**

```bash
cd infra
git add local/bootstrap.sh
git commit -m "feat(local): ymc-documents 버킷 CORS(PUT) — 브라우저 업로드용"
```

---

### Task 5: publish-parse-result.sh 신설

**Files:**
- Create: `infra/local/publish-parse-result.sh`

**Interfaces:**
- Consumes: `parse-results` 큐 · `contracts/schema/parse-result.schema.json` envelope.
- Produces: PROCESSING 논문을 COMPLETED/FAILED로 전이시키는 수동 발행 도구(AI 워커 대체). fe/README가 참조.

- [ ] **Step 1: 스크립트 작성**

```bash
#!/usr/bin/env bash
# 수동으로 parse-result를 발행해 PROCESSING 논문을 COMPLETED/FAILED로 전이시킨다.
# AI 파싱 워커가 없는 동안의 검증용. envelope: contracts/schema/parse-result.schema.json
#
# 사용법:
#   ./publish-parse-result.sh <paperId> COMPLETED
#   ./publish-parse-result.sh <paperId> FAILED [errorCode]
set -euo pipefail

PAPER_ID="${1:?usage: publish-parse-result.sh <paperId> <COMPLETED|FAILED> [errorCode]}"
STATUS="${2:?usage: publish-parse-result.sh <paperId> <COMPLETED|FAILED> [errorCode]}"
ERROR_CODE="${3:-PDF_UNREADABLE}"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-ap-northeast-2}"
ENDPOINT="${AWS_ENDPOINT_URL:-http://localhost:4566}"

QUEUE_URL=$(aws --endpoint-url "$ENDPOINT" sqs get-queue-url \
  --queue-name parse-results --query QueueUrl --output text)

if [ "$STATUS" = "FAILED" ]; then
  BODY=$(printf '{"paperId":"%s","status":"FAILED","error":{"code":"%s"}}' "$PAPER_ID" "$ERROR_CODE")
else
  BODY=$(printf '{"paperId":"%s","status":"COMPLETED"}' "$PAPER_ID")
fi

aws --endpoint-url "$ENDPOINT" sqs send-message --queue-url "$QUEUE_URL" --message-body "$BODY"
echo "[publish-parse-result] sent: $BODY"
```

- [ ] **Step 2: 실행 권한 부여**

Run: `chmod +x infra/local/publish-parse-result.sh`

- [ ] **Step 3: 검증 (BE·LocalStack 기동 상태에서)**

Run: 논문 하나를 PROCESSING까지 만든 뒤(등록→업로드→complete),
```bash
cd infra/local && ./publish-parse-result.sh <paperId> COMPLETED
curl -s http://localhost:8080/api/papers/<paperId>/status
```
Expected: status가 `COMPLETED`로 전이. `FAILED <id> PDF_UNREADABLE`도 동일하게 전이 확인.

- [ ] **Step 4: 커밋 (infra repo)**

```bash
cd infra
git add local/publish-parse-result.sh
git commit -m "feat(local): parse-result 수동 발행 스크립트 (AI 워커 대체)"
```

---

## Phase 3 — FE 검증 UI (app repo) · YMC-221

> **테스트 전략 — 연동 층은 TDD, 표현 층은 수동:** superpowers는 TDD 기반이다. 경계를 설계 §2의 연동/표현 선과 똑같이 긋는다.
> - **연동 층 `fe/src/api.js`** — 진짜 FE가 승계할 실코드 → **Vitest로 TDD**. `fetch`/XHR를 목킹해 요청 형태·Content-Type(D6)·에러 매핑을 브라우저 없이 검증한다. (Task 6)
> - **표현 층(React 껍데기)** — 버릴 목업 이식이고 목적이 "브라우저로 눈 검증"이라 자동 테스트를 세우지 않고 **수동 브라우저 체크리스트**로 검증한다. (Task 7·8)
>
> **포트 방식:** 화면 껍데기는 `app/fe/temp/논문 등록 처리 UI.html` 목업을 `fe/DESIGN.md`의 DSL→React 대응표(D1~D7)대로 **기계적 이식**한다.

### Task 6: Vite + Vitest 스캐폴드 + api.js (TDD)

**Files:**
- Create: `app/fe/package.json`, `app/fe/vite.config.js`
- Create: `app/fe/src/api.js`
- Test: `app/fe/src/api.test.js`

**Interfaces:**
- Produces: `api.js` — `createPaper(filename, contentType)`, `uploadToS3(uploadUrl, file, onProgress)`, `completeUpload(paperId)`, `getStatus(paperId)`, `getDownloadUrl(paperId)`, `listPapers()`. Task 7·8이 소비.

- [ ] **Step 1: Vite React + Vitest 스캐폴드**

Run: `cd app/fe && npm create vite@latest . -- --template react` (기존 DESIGN.md/README.md/temp/ 유지 — 덮어쓰기 프롬프트 신중히). 그 후:
```bash
npm install && npm install -D vitest jsdom
```
`package.json`의 `scripts`에 `"test": "vitest run"` 추가.

- [ ] **Step 2: vite proxy + vitest 환경 설정**

`vite.config.js`:

```js
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// /api를 BE(8080)로 프록시 → 브라우저 입장 same-origin(5173), preflight 없음 (DESIGN.md D1).
// S3 프리사인 PUT/GET은 프록시를 타지 않는다 — 브라우저가 S3로 직접 쏜다.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { '/api': 'http://localhost:8080' } },
  test: { environment: 'jsdom' },
});
```

- [ ] **Step 3: 실패하는 테스트 작성**

`app/fe/src/api.test.js`:

```js
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  createPaper, completeUpload, getStatus, getDownloadUrl, uploadToS3, listPapers,
} from './api';

function mockFetch({ ok = true, status = 200, body = {} }) {
  global.fetch = vi.fn().mockResolvedValue({ ok, status, json: async () => body });
}

describe('api.js — fetch 계열', () => {
  afterEach(() => vi.restoreAllMocks());

  it('createPaper: POST /api/papers에 filename·contentType을 JSON으로 보낸다', async () => {
    mockFetch({ body: { paperId: 'p1', uploadUrl: 'https://s3/put' } });
    const res = await createPaper('a.pdf', 'application/pdf');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename: 'a.pdf', contentType: 'application/pdf' }),
    }));
    expect(res.paperId).toBe('p1');
  });

  it('completeUpload: POST /complete', async () => {
    mockFetch({ body: { status: 'PROCESSING' } });
    await completeUpload('p1');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/complete', { method: 'POST' });
  });

  it('getStatus: GET /status', async () => {
    mockFetch({ body: { status: 'COMPLETED' } });
    const res = await getStatus('p1');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/status');
    expect(res.status).toBe('COMPLETED');
  });

  it('getDownloadUrl: GET /download → {downloadUrl, expiresAt}', async () => {
    mockFetch({ body: { downloadUrl: 'https://s3/get', expiresAt: '2026-07-15T00:00:00Z' } });
    const res = await getDownloadUrl('p1');
    expect(global.fetch).toHaveBeenCalledWith('/api/papers/p1/download');
    expect(res.downloadUrl).toBe('https://s3/get');
  });

  it('listPapers: GET /api/papers → {papers}', async () => {
    mockFetch({ body: { papers: [{ paperId: 'p1', status: 'COMPLETED' }] } });
    const res = await listPapers();
    expect(global.fetch).toHaveBeenCalledWith('/api/papers');
    expect(res.papers[0].paperId).toBe('p1');
  });

  it('실패 응답: code·httpStatus를 실은 Error를 던진다', async () => {
    mockFetch({ ok: false, status: 409, body: { code: 'DUPLICATE_FILENAME', message: '중복' } });
    await expect(createPaper('a.pdf', 'application/pdf')).rejects.toMatchObject({
      code: 'DUPLICATE_FILENAME', httpStatus: 409, message: '중복',
    });
  });
});

describe('api.js — uploadToS3 (XHR)', () => {
  it('PUT + Content-Type application/pdf 명시, 2xx에 resolve (D6)', async () => {
    const headers = {};
    let onload;
    const xhr = {
      open: vi.fn(),
      setRequestHeader: (k, v) => { headers[k] = v; },
      upload: {},
      send: vi.fn(function () { xhr.status = 204; onload(); }),
      set onload(fn) { onload = fn; },
      set onerror(_fn) { /* noop */ },
    };
    vi.stubGlobal('XMLHttpRequest', vi.fn(() => xhr));

    await uploadToS3('https://s3/put', new Blob(['x']));

    expect(xhr.open).toHaveBeenCalledWith('PUT', 'https://s3/put');
    expect(headers['Content-Type']).toBe('application/pdf');
    expect(xhr.send).toHaveBeenCalled();
  });
});
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `cd app/fe && npm test`
Expected: FAIL — `./api` 모듈이 없어 import 실패.

- [ ] **Step 5: api.js 구현**

`app/fe/src/api.js`:

```js
// BE 연동 단일 접점 (DESIGN.md D3). 표현 층과 분리 — 진짜 FE는 이 모듈을 그대로 승계한다.

export async function createPaper(filename, contentType) {
  const res = await fetch('/api/papers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ filename, contentType }),
  });
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, fileKey, uploadUrl, uploadExpiresAt, status, createdAt }
}

// presigned PUT. Content-Type은 서명값과 정확히 일치해야 한다 (DESIGN.md D6).
export function uploadToS3(uploadUrl, file, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', uploadUrl);
    xhr.setRequestHeader('Content-Type', 'application/pdf');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () =>
      xhr.status >= 200 && xhr.status < 300
        ? resolve()
        : reject(new Error(`S3 업로드 실패: ${xhr.status}`));
    xhr.onerror = () => reject(new Error('S3 업로드 네트워크 오류'));
    xhr.send(file);
  });
}

export async function completeUpload(paperId) {
  const res = await fetch(`/api/papers/${paperId}/complete`, { method: 'POST' });
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, status, updatedAt }
}

export async function getStatus(paperId) {
  const res = await fetch(`/api/papers/${paperId}/status`);
  if (!res.ok) throw await apiError(res);
  return res.json(); // { paperId, status, updatedAt }
}

// 원본 PDF 다운로드 URL 발급 (계약 0.1.1).
export async function getDownloadUrl(paperId) {
  const res = await fetch(`/api/papers/${paperId}/download`);
  if (!res.ok) throw await apiError(res);
  return res.json(); // { downloadUrl, expiresAt }
}

// 서재 목록 (FT-002, BE는 YMC-223). D3 폐기 — 실제 목록을 받는다.
export async function listPapers() {
  const res = await fetch('/api/papers');
  if (!res.ok) throw await apiError(res);
  return res.json(); // { papers: [{ paperId, filename, status, createdAt, updatedAt }] }
}

async function apiError(res) {
  let body = {};
  try { body = await res.json(); } catch { /* 비-JSON 응답 */ }
  const err = new Error(body.message || `HTTP ${res.status}`);
  err.code = body.code;
  err.httpStatus = res.status;
  return err;
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd app/fe && npm test`
Expected: PASS (7 tests)

- [ ] **Step 7: 커밋 (app repo)**

```bash
cd app
git add fe/package.json fe/package-lock.json fe/vite.config.js fe/src/api.js fe/src/api.test.js
git commit -m "feat(fe): 연동 층 api.js + Vitest(TDD) — 등록·업로드·폴링·다운로드"
```

---

### Task 7: 표현 층 이식 + 등록→폴링 배선 (수동 검증)

**Files:**
- Create: `app/fe/index.html`, `app/fe/src/main.jsx`, `app/fe/src/App.jsx`
- Create: `app/fe/public/` (목업에서 추출한 Pretendard woff2)

**Interfaces:**
- Consumes: Task 6의 `api.js`(`createPaper`/`uploadToS3`/`completeUpload`/`getStatus`/`listPapers`).
- Produces: `App`의 상태모델(DESIGN.md D4)과 서재 행 렌더. Task 8이 다운로드 버튼을 얹음.

- [ ] **Step 1: 폰트·목업 이식 (DESIGN.md D1~D7 기계적 전사)**

`temp/논문 등록 처리 UI.html`에서 Pretendard woff2를 `public/`로 추출하고, 마크업·`<style>`·인라인 스타일을 `App.jsx`로 옮긴다. DSL→React 변환은 DESIGN.md 표(`sc-if`→`{cond && …}`, `sc-camel-on-click`→`onClick` 등)를 따른다. 상태모델은 DESIGN.md D4:

```js
// App.jsx state (DESIGN.md D4)
// modal:        'closed' | 'idle' | 'file-selected' | 'uploading'
// selectedFile: File | null
// uploadPct:    0..100          (XHR progress — 진짜 숫자)
// papers:       []              (listPapers() 실제 목록. 등록분은 낙관적 추가 후 재조회로 수렴)
// error:        null
// dragOver:     false
```

행 표시는 서버가 준 `paper.status`가 정한다: `UPLOAD_PENDING`/`UPLOADED`/`PROCESSING`→진행 중, `COMPLETED`→완료, `FAILED`/`EXPIRED`→실패 (FT-002 Story 3 매핑). 파싱 진행 바는 불확정 애니메이션(DESIGN.md D2 — 진행률 데이터 없음).

- [ ] **Step 2: 목록 로드 + 등록→업로드→폴링 배선**

마운트 시 `listPapers()`로 서재를 채운다(실제 목록 — 새로고침해도 유지). 등록 모달에서: `createPaper` → `uploadToS3`(progress로 uploadPct 갱신) → `completeUpload` → `listPapers()` 재조회(또는 낙관적 추가) → 진행 중 행마다 `getStatus`를 주기 폴링(예: 2초)해 COMPLETED/FAILED에서 폴링 중단.

- [ ] **Step 3: 수동 브라우저 검증**

Run: BE(8080)·LocalStack 기동 → `cd app/fe && npm run dev` → `http://localhost:5173`.
확인:
- [ ] 빈 서재 → 등록 모달 → PDF 선택 → 업로드 바가 실제 %로 차오름.
- [ ] 개발자도구 Network에서 S3 PUT이 200 (CORS 통과, DESIGN.md D5).
- [ ] LocalStack에 객체 생성: `docker compose exec localstack awslocal s3 ls s3://ymc-documents/papers/ --recursive`.
- [ ] 서재 행이 "진행 중(PROCESSING)"으로 표시되고 status 폴링이 도는지(Network 반복 호출).

- [ ] **Step 4: 커밋 (app repo)**

```bash
cd app
git add fe/index.html fe/src/main.jsx fe/src/App.jsx fe/public/
git commit -m "feat(fe): 표현 층 이식 — 등록→업로드→폴링→서재 (temp/ 목업)"
```

---

### Task 8: 완료 행 다운로드 버튼 + 전체 여정 검증

**Files:**
- Modify: `app/fe/src/App.jsx` (완료 행 다운로드 버튼 + 핸들러)
- Modify: `app/fe/README.md` (다운로드 검증 시나리오 반영)

**Interfaces:**
- Consumes: Task 6의 `getDownloadUrl(paperId)` → `{downloadUrl, expiresAt}`, Task 3의 BE 엔드포인트.

- [ ] **Step 1: App.jsx에 다운로드 핸들러 추가**

`import { ..., getDownloadUrl } from './api';` 후:

```js
async function handleDownload(paperId) {
  try {
    const { downloadUrl } = await getDownloadUrl(paperId);
    // cross-origin 네비게이션 다운로드 — 파일명은 서버의 Content-Disposition이 결정한다.
    const a = document.createElement('a');
    a.href = downloadUrl;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
  } catch (e) {
    setError(e.message); // 숨기지 않고 화면에 노출 (DESIGN.md D7)
  }
}
```

- [ ] **Step 2: 완료 행에만 다운로드 버튼 노출**

서재 행 렌더에서 `paper.status === 'COMPLETED'`일 때만 다운로드 버튼을 그린다(`onClick={() => handleDownload(paper.paperId)}`). `PROCESSING`/`FAILED` 행에는 버튼 없음 (FT-002 Story 5).

- [ ] **Step 3: fe/README 갱신**

`app/fe/README.md`의 검증 시나리오에 다운로드 단계를 추가하고, 존재하지 않던 `publish-parse-result.sh`가 이제 `infra/local`에 있음을 반영(경로 확인).

- [ ] **Step 4: 전체 여정 수동 검증 (설계 §7)**

Run: BE·LocalStack·`npm run dev` 기동 상태에서:
- [ ] 등록→업로드→서재 "진행 중" 표시 (Task 7에서 확인).
- [ ] `cd infra/local && ./publish-parse-result.sh <paperId> COMPLETED` → 서재 행이 "완료"로 전환.
- [ ] **완료 행 다운로드 버튼 클릭 → 원본 파일명(예: attention.pdf)으로 저장됨** (핵심).
- [ ] `./publish-parse-result.sh <다른paperId> FAILED` → 실패 행, 다운로드 버튼 없음.
- [ ] complete 전(UPLOAD_PENDING) paperId로 `curl http://localhost:8080/api/papers/<id>/download` → 409 `UPLOAD_NOT_FOUND`.
- [ ] 같은 파일명 재업로드 → 409 `DUPLICATE_FILENAME`이 화면에 노출.

- [ ] **Step 5: 커밋 (app repo)**

```bash
cd app
git add fe/src/App.jsx fe/README.md
git commit -m "feat(fe): 완료 논문 원본 PDF 다운로드 버튼 + 여정 검증"
```

---

## 완료 기준

- **BE:** `./gradlew test` 전부 통과. `GET /papers/{id}/download`가 200(URL)/404/409/400을 계약대로 응답.
- **계약·feature:** openapi 0.1.1, FT-002 Story 5가 project-docs에 반영·커밋.
- **infra:** 버킷 CORS(PUT) 적용, `publish-parse-result.sh` 동작.
- **FE:** `npm test`(Vitest) 연동 층 6개 테스트 통과. 브라우저에서 등록→업로드→폴링→완료→**다운로드(원본 파일명)**가 눈으로 확인됨. 실패·409 경계도 관찰됨.
- **불변식:** 기존 BE 등록/complete/status 코드·테스트 무변경. `api.js`는 표현 층과 격리(진짜 FE 승계 가능).
- **브랜치:** 3개 repo 모두 `feat/paper-download-verify`에서 작업, `main` 직접 커밋 없음.
