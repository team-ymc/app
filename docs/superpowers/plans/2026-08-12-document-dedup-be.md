# Document 기반 PDF 중복 제거 (BE) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 SHA-256의 PDF를 여러 사용자가 등록해도 원본·파싱을 Document 단위로 공유하고, checksum 검증 업로드 계약(0.3.0)을 구현한다.

**Architecture:** Paper(사용자 소유) N→1 Document(파일 공유) 분리. 상태는 Document가 단독 소유하고 조회 시 join으로 파생. 발행은 "UPLOADED→PROCESSING CAS 선점 승자만" 규칙 하나로 통일(구제 발행 포함). 전환은 expand(조회를 document-인지로 확장) → migrate(쓰기 전환) → contract(Paper 컬럼 제거) 순서로 태스크마다 전체 테스트가 green을 유지한다.

**Tech Stack:** Spring Boot 3 + JPA(Hibernate), PostgreSQL 16, AWS SDK v2 (S3·SQS), Testcontainers + LocalStack.

**Spec:** `docs/superpowers/specs/2026-08-12-document-dedup-be-design.md` (머지됨). 결정 근거: ADR-003(구제 발행 보강 포함).

## Global Constraints

- 작업 디렉터리: `app/be`. 테스트: `./gradlew test` (단일 클래스: `./gradlew test --tests 'com.ymc.paper.api.PaperRegistrationIntegrationTest'`). 통합 테스트는 Docker 필요(Testcontainers + LocalStack).
- 커밋: `[YMC-280] type(scope): subject` (Task 8만 `[YMC-281]`). subject에 "리뷰 반영" 같은 출처 서술 금지 — 변경 내용을 적는다. Co-Authored-By 등 attribution 금지.
- 코드 주석: 코드가 못 보여주는 제약만 짧게. `(YMC-XXX)`·`(spec §…)` 출처 괄호 금지.
- 로그: checksum 전체·presigned URL 금지. Document 관련 로그는 `paperId={}, documentId={}` 병기.
- `documentId`·checksum·공유 S3 key를 API 응답에 넣지 않는다 (응답 DTO 스키마 무변경).
- BE↔AI 메시지 스키마(`paper_id`, `file_key`) 무변경.
- DB 스키마는 ddl-auto(update)가 만들되, prod validate용 DDL 산출물을 `docs/db/*.sql`에 함께 갱신한다. 데이터 이관 없음(초기화 확정).
- checksum 형식: 표준 Base64 SHA-256, `^[A-Za-z0-9+/]{43}=$` (44자).

---

### Task 1: 등록 계약 확장 — checksumSha256 필수 + presign 서명 + uploadHeaders

**Files:**
- Modify: `src/main/java/com/ymc/paper/api/dto/CreatePaperRequest.java`
- Modify: `src/main/java/com/ymc/paper/api/dto/PaperCreated.java`
- Modify: `src/main/java/com/ymc/paper/api/PaperController.java` (create에서 checksum 전달)
- Modify: `src/main/java/com/ymc/paper/service/PaperRegistrationResult.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperRegistrationService.java`
- Modify: `src/main/java/com/ymc/paper/service/port/FileStorage.java`
- Modify: `src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java`
- Modify: `src/test/java/com/ymc/support/IntegrationTest.java` (헬퍼 추가)
- Test: `src/test/java/com/ymc/paper/api/PaperRegistrationIntegrationTest.java`

**Interfaces:**
- Produces: `FileStorage.presignUpload(String fileKey, String contentType, long contentLength, String checksumSha256)` / `PaperRegistrationResult.uploadHeaders(): Map<String,String>` / IntegrationTest 헬퍼 `TEST_PDF_BYTES`, `checksumOf(byte[])`, `randomChecksum()`, `createPaperJson(String filename)`
- Consumes: 기존 `PresignedUpload`, `PaperUploadPolicy`

- [ ] **Step 1: IntegrationTest에 checksum 헬퍼 추가**

`src/test/java/com/ymc/support/IntegrationTest.java`에 추가 (기존 `givenUploadedObject` 근처):

```java
/** 등록·업로드 테스트가 공유하는 가짜 PDF 바이트. checksum·size가 이 값 기준으로 맞아야 S3가 받는다. */
protected static final byte[] TEST_PDF_BYTES =
        "%PDF-1.4\n%fake pdf for test\n".getBytes(StandardCharsets.UTF_8);

/** 표준 Base64 SHA-256 (32 bytes → 44 chars). */
protected static String checksumOf(byte[] bytes) {
    try {
        return java.util.Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
    }
}

/** 형식만 유효한 무작위 checksum — 픽스처 간 unique 충돌 회피용. */
protected static String randomChecksum() {
    byte[] bytes = new byte[32];
    java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes);
    return java.util.Base64.getEncoder().encodeToString(bytes);
}

/** TEST_PDF_BYTES와 정합인 등록 요청 본문. */
protected String createPaperJson(String filename) {
    return """
            {"filename":"%s","contentType":"application/pdf","size":%d,"checksumSha256":"%s"}"""
            .formatted(filename, TEST_PDF_BYTES.length, checksumOf(TEST_PDF_BYTES));
}
```

- [ ] **Step 2: 실패 테스트 작성**

`PaperRegistrationIntegrationTest`에 추가:

```java
@Test
void checksum이_없으면_400_VALIDATION_ERROR() throws Exception {
    mockMvc.perform(post("/api/papers").with(userJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"filename":"a.pdf","contentType":"application/pdf","size":1024}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
}

@Test
void checksum_형식이_틀리면_400_VALIDATION_ERROR() throws Exception {
    mockMvc.perform(post("/api/papers").with(userJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"filename":"a.pdf","contentType":"application/pdf","size":1024,
                             "checksumSha256":"not-base64!"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
}

@Test
void 등록_응답에_uploadHeaders가_들어있다() throws Exception {
    mockMvc.perform(post("/api/papers").with(userJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createPaperJson("headers.pdf")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.uploadHeaders.Content-Type").value("application/pdf"))
            .andExpect(jsonPath("$.uploadHeaders.x-amz-checksum-sha256")
                    .value(checksumOf(TEST_PDF_BYTES)));
}

@Test
void uploadHeaders대로_PUT하면_S3가_받고_다른_바이트면_거절한다() throws Exception {
    String body = mockMvc.perform(post("/api/papers").with(userJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createPaperJson("put.pdf")))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    String uploadUrl = objectMapper.readTree(body).get("uploadUrl").asText();
    String checksum = objectMapper.readTree(body).get("uploadHeaders")
            .get("x-amz-checksum-sha256").asText();

    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

    // 정상: 서명된 헤더 + 정확한 바이트
    var ok = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(uploadUrl))
                    .header("Content-Type", "application/pdf")
                    .header("x-amz-checksum-sha256", checksum)
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(TEST_PDF_BYTES))
                    .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(ok.statusCode()).isEqualTo(200);

    // 같은 헤더로 다른 바이트 → S3가 BadDigest로 거절 (400)
    byte[] different = "%PDF-1.4\n%tampered\n".getBytes(StandardCharsets.UTF_8);
    var bad = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(uploadUrl))
                    .header("Content-Type", "application/pdf")
                    .header("x-amz-checksum-sha256", checksum)
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(different))
                    .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(bad.statusCode()).isNotEqualTo(200);

    // checksum 헤더를 빼고 PUT → 서명 불일치로 거절
    var missing = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(uploadUrl))
                    .header("Content-Type", "application/pdf")
                    .PUT(java.net.http.HttpRequest.BodyPublishers.ofByteArray(TEST_PDF_BYTES))
                    .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(missing.statusCode()).isNotEqualTo(200);
}
```

주의: 다른-바이트 PUT은 서명이 `Content-Length`도 고정하므로 **같은 길이**여야 checksum 검증까지 도달한다. `"%PDF-1.4\n%tampered\n"`가 `TEST_PDF_BYTES`와 길이가 다르면 padding으로 맞춘다:
`byte[] different = TEST_PDF_BYTES.clone(); different[different.length - 1] ^= 1;` (한 바이트 뒤집기 — 같은 길이, 다른 checksum).

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperRegistrationIntegrationTest'`
Expected: 신규 테스트 FAIL (checksumSha256 필드 없음 → 400이지만 기존 요청도 깨질 수 있음. uploadHeaders 없음)

- [ ] **Step 4: 구현**

`CreatePaperRequest.java` — checksum은 계약상 형식 위반이 `VALIDATION_ERROR`라 DTO에서 잡는다 (contentType·size와 달리 전용 에러 코드가 없음):

```java
public record CreatePaperRequest(
        @NotBlank(message = "필수 항목입니다.") String filename,
        @NotBlank(message = "필수 항목입니다.") String contentType,
        @NotNull(message = "필수 항목입니다.") @Positive(message = "0보다 커야 합니다.") Long size,
        @NotNull(message = "필수 항목입니다.")
        @Pattern(regexp = "^[A-Za-z0-9+/]{43}=$", message = "표준 Base64 SHA-256이어야 합니다.")
        String checksumSha256) {
}
```

(import `jakarta.validation.constraints.Pattern` 추가. 기존 클래스 주석의 "값 판정은 서비스" 문단에 checksum 예외를 한 줄 덧붙인다.)

`FileStorage.presignUpload` 시그니처 확장:

```java
PresignedUpload presignUpload(String fileKey, String contentType, long contentLength, String checksumSha256);
```

`S3FileStorage.presignUpload` — `checksumSHA256`을 넣으면 `x-amz-checksum-sha256`이 서명에 포함되고, S3가 실제 바이트와 대조 검증한다:

```java
.putObjectRequest(PutObjectRequest.builder()
        .bucket(props.s3().bucket())
        .key(fileKey)
        .contentType(contentType)
        .contentLength(contentLength)
        .checksumSHA256(checksumSha256)
        .build())
```

`PaperRegistrationResult` — `Map<String, String> uploadHeaders` 필드 추가 (fileKey 다음 위치).

`PaperRegistrationService.register` — 파라미터 `String checksumSha256` 추가, presign 호출과 결과에 반영. **checksum은 저장하지 않는다** (서명 재료일 뿐):

```java
PresignedUpload upload = fileStorage.presignUpload(
        paper.getFileKey(), contentType, size, checksumSha256);

return new PaperRegistrationResult(
        paper.getId(),
        paper.getFileKey(),
        Map.of("Content-Type", contentType, "x-amz-checksum-sha256", checksumSha256),
        upload.url(),
        upload.expiresAt(),
        paper.getStatus(),
        paper.getCreatedAt());
```

`PaperCreated` — `Map<String, String> uploadHeaders` 필드 추가 + `from()` 반영.
`PaperController.create` — `request.checksumSha256()` 전달.

- [ ] **Step 5: 기존 테스트의 등록 요청 본문 갱신**

`PaperRegistrationIntegrationTest`·`PaperFlowE2ETest` 등에서 `/api/papers` POST 본문을 만들던 곳을 전부 `createPaperJson(...)` 또는 checksum 포함 JSON으로 교체한다. 컴파일은 되지만 400이 나는 테스트가 대상이다 — `grep -rn '"contentType"' src/test`로 전수 확인.

- [ ] **Step 6: 전체 확인**

Run: `./gradlew test`
Expected: PASS (S3FileStorage 서명 통합 테스트가 있으면 그 기대값도 checksum 헤더 포함으로 갱신)

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "[YMC-280] feat(be): 등록에 checksumSha256 필수화, presign 서명·uploadHeaders 추가"
```

---

### Task 2: complete의 checksum 확인 — head(ChecksumMode) + UPLOAD_CHECKSUM_MISSING

**Files:**
- Modify: `src/main/java/com/ymc/paper/service/port/UploadedObjectMetadata.java`
- Modify: `src/main/java/com/ymc/paper/infra/storage/S3FileStorage.java` (head)
- Modify: `src/main/java/com/ymc/common/error/ErrorCode.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java`
- Modify: `src/test/java/com/ymc/support/IntegrationTest.java` (`givenUploadedObject`)
- Test: `src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java`

**Interfaces:**
- Produces: `UploadedObjectMetadata(long contentLength, String checksumSha256)` (checksum은 null 가능 — S3 검증값 부재), `ErrorCode.UPLOAD_CHECKSUM_MISSING`(409)
- Consumes: Task 1의 checksum 서명 업로드 (테스트 헬퍼가 checksum 포함 PUT을 하도록 바뀜)

- [ ] **Step 1: 테스트 헬퍼를 checksum 포함 업로드로 변경**

`IntegrationTest.givenUploadedObject`를 교체 — 이후 모든 정상 complete 테스트가 "S3 검증 checksum 있는 객체"를 전제한다:

```java
/** FE가 checksum 서명 presigned URL로 업로드한 상황 (서버 자격증명으로 대신 넣는다). */
protected void givenUploadedObject(Paper paper) {
    s3.putObject(
            PutObjectRequest.builder()
                    .bucket(awsProperties.s3().bucket())
                    .key(paper.getFileKey())
                    .contentType("application/pdf")
                    .checksumSHA256(checksumOf(TEST_PDF_BYTES))
                    .build(),
            RequestBody.fromBytes(TEST_PDF_BYTES));
}

/** checksum 없이 올라간 객체 — UPLOAD_CHECKSUM_MISSING 재현용. */
protected void givenUploadedObjectWithoutChecksum(Paper paper) {
    s3.putObject(
            PutObjectRequest.builder()
                    .bucket(awsProperties.s3().bucket())
                    .key(paper.getFileKey())
                    .contentType("application/pdf")
                    .build(),
            RequestBody.fromBytes(TEST_PDF_BYTES));
}
```

주의: AWS SDK 2.30+는 기본 checksum 계산(`WHEN_SUPPORTED`)으로 checksum 없는 PUT에도 CRC 계열을 붙일 수 있다. `givenUploadedObjectWithoutChecksum`이 정말 SHA-256 없이 저장되는지는 Step 3에서 테스트로 확인한다 — CRC가 붙는 것은 무관하다(우리는 `ChecksumSHA256`만 본다).

- [ ] **Step 2: 실패 테스트 작성**

`PaperUploadCompletionIntegrationTest`에 추가:

```java
@Test
void S3_검증_checksum이_없으면_409_UPLOAD_CHECKSUM_MISSING_상태유지_발행없음() throws Exception {
    Paper paper = givenPendingPaper("no-checksum.pdf");
    givenUploadedObjectWithoutChecksum(paper);

    mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("UPLOAD_CHECKSUM_MISSING"));

    assertThat(reload(paper.getId()).getStatus()).isEqualTo(PaperStatus.UPLOAD_PENDING);
    verify(parseRequestPublisher, never()).publish(any(), any());
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperUploadCompletionIntegrationTest'`
Expected: 신규 테스트 FAIL (`UPLOAD_CHECKSUM_MISSING` enum 없음 → 컴파일 에러부터)

- [ ] **Step 4: 구현**

`UploadedObjectMetadata`:

```java
/** S3 HEAD로 확인한 업로드 객체 메타데이터. checksumSha256은 S3가 검증한 값이며 없을 수 있다. */
public record UploadedObjectMetadata(long contentLength, String checksumSha256) {
}
```

`S3FileStorage.head` — `ChecksumMode.ENABLED`가 없으면 HEAD 응답에 checksum이 실리지 않는다:

```java
var response = s3.headObject(HeadObjectRequest.builder()
        .bucket(props.s3().bucket())
        .key(fileKey)
        .checksumMode(software.amazon.awssdk.services.s3.model.ChecksumMode.ENABLED)
        .build());
return Optional.of(new UploadedObjectMetadata(response.contentLength(), response.checksumSHA256()));
```

`ErrorCode` — `UPLOAD_NOT_FOUND` 다음에:

```java
/** 객체에 S3가 검증한 SHA-256 checksum이 없음 (complete) */
UPLOAD_CHECKSUM_MISSING(HttpStatus.CONFLICT),
```

`PaperUploadCompletionService.complete` — 크기 검사 다음에 (아직 기존 Paper 흐름 유지):

```java
if (uploadedObject.checksumSha256() == null || uploadedObject.checksumSha256().isBlank()) {
    throw new ApiException(ErrorCode.UPLOAD_CHECKSUM_MISSING,
            "업로드 객체에 검증된 checksum이 없습니다. 같은 파일을 다시 업로드한 뒤 재시도해 주세요.");
}
```

- [ ] **Step 5: 확인 후 커밋**

Run: `./gradlew test` → PASS

```bash
git add -A && git commit -m "[YMC-280] feat(be): complete에서 S3 검증 checksum 확인, 누락 시 409"
```

---

### Task 3: Document 도메인 신설 + paper.document_id

**Files:**
- Create: `src/main/java/com/ymc/paper/domain/DocumentStatus.java`
- Create: `src/main/java/com/ymc/paper/domain/Document.java`
- Create: `src/main/java/com/ymc/paper/domain/DocumentRepository.java`
- Create: `src/main/java/com/ymc/paper/service/DocumentTransitions.java`
- Modify: `src/main/java/com/ymc/paper/domain/Paper.java` (documentId 필드)
- Modify: `src/main/java/com/ymc/paper/domain/PaperRepository.java` (linkDocument)
- Create: `docs/db/document.sql`
- Test: `src/test/java/com/ymc/paper/domain/DocumentTest.java` (단위)
- Test: `src/test/java/com/ymc/paper/domain/DocumentPersistenceIntegrationTest.java`

**Interfaces:**
- Produces: `DocumentStatus{UPLOADED,PROCESSING,COMPLETED,FAILED}` + `isTerminal()` / `Document.create(UUID id, String checksumSha256, String fileKey, UUID requestPaperId, Instant now)` / `DocumentRepository.insertIfAbsent(...)→int`, `findByChecksumSha256`, `findByRequestPaperId`, `markProcessing`, `markParsed`, `revertToUploaded` / `DocumentTransitions.markProcessing(UUID)→boolean`, `markParsed(UUID, DocumentStatus, String)→boolean`, `revertToUploaded(UUID)→boolean` / `Paper.getDocumentId(): UUID`(nullable), `PaperRepository.linkDocument(paperId, documentId, now)→int`
- Consumes: 없음 (다른 코드는 아직 이 도메인을 쓰지 않는다 — 이 태스크는 순수 additive)

- [ ] **Step 1: 실패 테스트 작성 (단위)**

`DocumentTest.java`:

```java
package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DocumentTest {

    @Test
    void create는_UPLOADED로_시작한다() {
        Document doc = Document.create(UUID.randomUUID(), "A".repeat(43) + "=",
                "uploads/x/original.pdf", UUID.randomUUID(), Instant.now());
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(doc.getErrorCode()).isNull();
    }

    @Test
    void 필수값_null이면_거절한다() {
        assertThatThrownBy(() -> Document.create(null, "c", "k", UUID.randomUUID(), Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void terminal은_COMPLETED와_FAILED다() {
        assertThat(DocumentStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(DocumentStatus.FAILED.isTerminal()).isTrue();
        assertThat(DocumentStatus.UPLOADED.isTerminal()).isFalse();
        assertThat(DocumentStatus.PROCESSING.isTerminal()).isFalse();
    }
}
```

- [ ] **Step 2: 실패 테스트 작성 (통합 — 유일성·CAS)**

`DocumentPersistenceIntegrationTest.java`:

```java
package com.ymc.paper.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ymc.support.IntegrationTest;

class DocumentPersistenceIntegrationTest extends IntegrationTest {

    @Autowired
    DocumentRepository documentRepository;

    @Autowired
    TransactionTemplate tx;

    private UUID insert(String checksum, UUID requestPaperId) {
        UUID id = UUID.randomUUID();
        Integer inserted = tx.execute(s -> documentRepository.insertIfAbsent(
                id, checksum, "uploads/" + requestPaperId + "/original.pdf", requestPaperId, Instant.now()));
        return inserted == 1 ? id : null;
    }

    @Test
    void 같은_checksum은_한_번만_insert되고_두번째는_0row다() {
        String checksum = randomChecksum();
        assertThat(insert(checksum, UUID.randomUUID())).isNotNull();
        assertThat(insert(checksum, UUID.randomUUID())).isNull();   // ON CONFLICT DO NOTHING
        assertThat(documentRepository.findByChecksumSha256(checksum)).isPresent();
    }

    @Test
    void 선점_CAS는_UPLOADED에서만_1row다() {
        String checksum = randomChecksum();
        UUID id = insert(checksum, UUID.randomUUID());
        assertThat(tx.execute(s -> documentRepository.markProcessing(id, Instant.now()))).isEqualTo(1);
        assertThat(tx.execute(s -> documentRepository.markProcessing(id, Instant.now()))).isEqualTo(0);
    }

    @Test
    void 반납은_PROCESSING을_UPLOADED로_되돌린다() {
        UUID id = insert(randomChecksum(), UUID.randomUUID());
        tx.execute(s -> documentRepository.markProcessing(id, Instant.now()));
        assertThat(tx.execute(s -> documentRepository.revertToUploaded(id, Instant.now()))).isEqualTo(1);
        assertThat(documentRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.UPLOADED);
    }

    @Test
    void 결과_전이는_UPLOADED에서도_PROCESSING에서도_1row고_중복은_0row다() {
        UUID uploadedDoc = insert(randomChecksum(), UUID.randomUUID());
        assertThat(tx.execute(s -> documentRepository.markParsed(
                uploadedDoc, DocumentStatus.COMPLETED, null, Instant.now()))).isEqualTo(1);
        assertThat(tx.execute(s -> documentRepository.markParsed(
                uploadedDoc, DocumentStatus.FAILED, "X", Instant.now()))).isEqualTo(0);
    }

    @Test
    void 연결_CAS는_document_id가_null일_때만_1row고_updated_at을_갱신한다() {
        var paper = givenPendingPaper("link.pdf");
        Instant before = paper.getUpdatedAt();
        UUID docId = insert(randomChecksum(), paper.getId());
        assertThat(tx.execute(s -> paperRepository.linkDocument(
                paper.getId(), docId, Instant.now()))).isEqualTo(1);
        assertThat(tx.execute(s -> paperRepository.linkDocument(
                paper.getId(), docId, Instant.now()))).isEqualTo(0);
        var linked = reload(paper.getId());
        assertThat(linked.getDocumentId()).isEqualTo(docId);
        assertThat(linked.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.domain.DocumentPersistenceIntegrationTest' --tests 'com.ymc.paper.domain.DocumentTest'`
Expected: 컴파일 실패 (Document 미존재)

- [ ] **Step 4: 구현**

`DocumentStatus.java`:

```java
package com.ymc.paper.domain;

/**
 * Document의 파싱 라이프사이클. 검증된 객체가 있어야 Document가 생기므로 UPLOAD_PENDING이 없다.
 * API의 PaperStatus로는 이름 그대로 매핑된다.
 */
public enum DocumentStatus {
    UPLOADED,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
```

`Document.java`:

```java
package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 같은 파일 바이트(SHA-256)의 공유 단위 — 대표 원본·파싱 상태·산출물을 소유한다.
 * 여러 Paper가 참조하며 상태 전이는 전부 {@link DocumentRepository}의 CAS로 한다.
 */
@Getter
@Entity
@Table(
        name = "document",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_document_checksum", columnNames = "checksum_sha256"),
        indexes = @Index(
                name = "ux_document_request_paper", columnList = "request_paper_id", unique = true))
public class Document {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** S3가 실제 바이트와 대조 검증한 표준 Base64 SHA-256. 동일 파일 판정의 유일한 기준. */
    @Column(name = "checksum_sha256", nullable = false, updatable = false, length = 44)
    private String checksumSha256;

    /** 대표 원본 key. 최초 업로드 객체를 복제 없이 그대로 쓴다. */
    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DocumentStatus status;

    /** 파싱 실패 코드 원문. 내부 기록용 — API 응답에는 나가지 않는다. */
    @Column(name = "error_code")
    private String errorCode;

    /**
     * AI 작업 상관키 = 이 Document를 만든 최초 paperId. FK가 아니다 — 대표 Paper가 삭제돼도
     * 발행·결과 역조회는 이 값만 본다.
     */
    @Column(name = "request_paper_id", nullable = false, updatable = false)
    private UUID requestPaperId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 상태가 마지막으로 바뀐 시각. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // JPA
    }

    private Document(UUID id, String checksumSha256, String fileKey, UUID requestPaperId, Instant now) {
        this.id = id;
        this.checksumSha256 = checksumSha256;
        this.fileKey = fileKey;
        this.status = DocumentStatus.UPLOADED;
        this.requestPaperId = requestPaperId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Document create(
            UUID id, String checksumSha256, String fileKey, UUID requestPaperId, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(checksumSha256, "checksumSha256");
        Objects.requireNonNull(fileKey, "fileKey");
        Objects.requireNonNull(requestPaperId, "requestPaperId");
        Objects.requireNonNull(now, "now");
        return new Document(id, checksumSha256, fileKey, requestPaperId, now);
    }
}
```

`DocumentRepository.java`:

```java
package com.ymc.paper.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 상태 전이는 전부 조건부 UPDATE(CAS) — 변경 row 수가 1일 때만 후속 동작. Paper CAS와 같은 관용구다.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByChecksumSha256(String checksumSha256);

    Optional<Document> findByRequestPaperId(UUID requestPaperId);

    /**
     * checksum 유일성을 지키는 생성. unique 위반 예외에 의존하면 PostgreSQL이 트랜잭션을
     * abort시켜 같은 Tx에서 기존-Document 경로로 전환할 수 없으므로 ON CONFLICT를 쓴다.
     *
     * @return 1이면 이 호출이 생성함, 0이면 같은 checksum이 이미 있음
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            insert into document
                (id, checksum_sha256, file_key, status, error_code, request_paper_id, created_at, updated_at)
            values (:id, :checksum, :fileKey, 'UPLOADED', null, :requestPaperId, :now, :now)
            on conflict (checksum_sha256) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("checksum") String checksum,
            @Param("fileKey") String fileKey,
            @Param("requestPaperId") UUID requestPaperId,
            @Param("now") Instant now);

    /** 파싱 시작 권한 선점. 승자 1명만 1을 받고, 승자만 발행한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = com.ymc.paper.domain.DocumentStatus.PROCESSING,
                   d.updatedAt = :now
             where d.id = :id
               and d.status = com.ymc.paper.domain.DocumentStatus.UPLOADED
            """)
    int markProcessing(@Param("id") UUID id, @Param("now") Instant now);

    /** 발행 실패 시 선점 반납 — 다음 complete가 다시 선점(구제)할 수 있게 한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = com.ymc.paper.domain.DocumentStatus.UPLOADED,
                   d.updatedAt = :now
             where d.id = :id
               and d.status = com.ymc.paper.domain.DocumentStatus.PROCESSING
            """)
    int revertToUploaded(@Param("id") UUID id, @Param("now") Instant now);

    /** 결과 수신 전이. UPLOADED 포함 — PROCESSING 커밋 전에 결과가 도착하는 경합을 흡수한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Document d
               set d.status = :terminal,
                   d.errorCode = :errorCode,
                   d.updatedAt = :now
             where d.id = :id
               and d.status in (com.ymc.paper.domain.DocumentStatus.UPLOADED,
                                com.ymc.paper.domain.DocumentStatus.PROCESSING)
            """)
    int markParsed(
            @Param("id") UUID id,
            @Param("terminal") DocumentStatus terminal,
            @Param("errorCode") String errorCode,
            @Param("now") Instant now);
}
```

`DocumentTransitions.java` (트랜잭션 경계 분리 전용 빈 — PaperTransitions와 같은 역할):

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;

import lombok.RequiredArgsConstructor;

/**
 * Document 상태 전이의 트랜잭션 단위. 조율자와 별도 빈인 이유는 전이 커밋과 큐 발행(외부 I/O)의
 * 경계를 실제로 쪼개기 위해서다 — 발행을 트랜잭션 안에 두면 row 락이 I/O에 물리고
 * "발행 성공 후 커밋 실패"의 유령 발행이 생긴다.
 */
@Service
@RequiredArgsConstructor
public class DocumentTransitions {

    private final DocumentRepository documentRepository;

    /** 파싱 시작 권한 선점을 즉시 커밋한다. true면 이 호출만 발행 권한을 갖는다. */
    @Transactional
    public boolean markProcessing(UUID documentId) {
        return documentRepository.markProcessing(documentId, Instant.now()) == 1;
    }

    /** 발행 실패 시 선점 반납. */
    @Transactional
    public boolean revertToUploaded(UUID documentId) {
        return documentRepository.revertToUploaded(documentId, Instant.now()) == 1;
    }

    /** 결과 수신 전이. false면 이미 terminal(중복 수신 등). */
    @Transactional
    public boolean markParsed(UUID documentId, DocumentStatus terminal, String errorCode) {
        return documentRepository.markParsed(documentId, terminal, errorCode, Instant.now()) == 1;
    }
}
```

`Paper.java` — 필드 추가 (errorCode 아래). `updatable = false`를 두지 않는다 — 연결은 `linkDocument` CAS만이 쓴다:

```java
/** 연결된 공유 Document. 업로드 검증 전에는 null이며 연결은 linkDocument CAS로만 한다. */
@Column(name = "document_id")
private UUID documentId;
```

`PaperRepository` — 추가:

```java
/**
 * 검증 완료된 Paper를 Document에 연결. document_id가 null일 때만 1 row다.
 * updated_at을 함께 갱신한다 — 연결 순간이 이 Paper의 표시 상태가 바뀐 시각이고,
 * bulk UPDATE는 JPA auditing을 우회하기 때문이다.
 */
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update Paper p
           set p.documentId = :documentId,
               p.updatedAt = :now
         where p.id = :paperId
           and p.documentId is null
        """)
int linkDocument(@Param("paperId") UUID paperId, @Param("documentId") UUID documentId,
        @Param("now") Instant now);
```

`docs/db/document.sql` (paper.sql과 같은 형식·주의 문구로):

```sql
-- document 테이블 — 같은 파일 바이트(SHA-256)의 공유 단위. 여러 paper가 참조한다.
--
-- prod는 validate라 배포 전에 이 스크립트를 운영 DB에 반영해야 한다.
-- ⚠ Document 엔티티가 바뀌면 이 파일도 함께 고칠 것.

create table document (
    id               uuid                        not null,
    checksum_sha256  varchar(44)                 not null,
    file_key         varchar(255)                not null,
    status           varchar(32)                 not null
        check (status in ('UPLOADED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    error_code       varchar(255),
    request_paper_id uuid                        not null,
    created_at       timestamp(6) with time zone not null,
    updated_at       timestamp(6) with time zone not null,

    primary key (id),

    -- 동일 파일 판정의 근거. 동시 complete의 생성 경쟁은 이 제약 + ON CONFLICT로 1명만 이긴다.
    constraint uk_document_checksum unique (checksum_sha256)
);

-- AI 결과(paper_id = request_paper_id) 역조회 경로.
create unique index ux_document_request_paper on document (request_paper_id);
```

- [ ] **Step 5: 확인 후 커밋**

Run: `./gradlew test` → PASS

```bash
git add -A && git commit -m "[YMC-280] feat(be): Document 도메인·CAS·paper 연결 컬럼 신설"
```

---

### Task 4: 조회 경로를 document-인지로 확장 (파생 규칙)

**Files:**
- Create: `src/main/java/com/ymc/paper/service/PaperDocumentViews.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperStatusService.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperListService.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperDownloadService.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperContentQueryService.java` (상태 게이트만)
- Modify: `src/main/java/com/ymc/paper/service/PaperChatAccessValidator.java`
- Test: `src/test/java/com/ymc/paper/api/PaperStatusPollingIntegrationTest.java` (파생 케이스 추가)

**Interfaces:**
- Produces: `PaperDocumentViews.statusView(Paper)→PaperStatusView`, `listViews(List<Paper>)→List<PaperListView>`, `documentOf(Paper)→Optional<Document>`, `derivedStatus(Paper, Document)→PaperStatus`
- Consumes: Task 3의 `Document`/`DocumentRepository`/`Paper.getDocumentId()`
- 전환기 규칙: document가 없으면 **기존 paper.status를 그대로** 쓴다(legacy fallback). 쓰기 전환(Task 5) 전까지 기존 테스트가 전부 green으로 유지되는 근거다. fallback은 Task 7에서 `UPLOAD_PENDING` 상수로 굳힌다.

- [ ] **Step 1: 실패 테스트 작성**

`PaperStatusPollingIntegrationTest`에 추가:

```java
@Test
void document에_연결된_paper는_document_상태와_최신_updatedAt을_반환한다() throws Exception {
    Paper paper = givenPendingPaper("derived.pdf");
    UUID docId = UUID.randomUUID();
    // COMPLETED document를 직접 구성 — 결과 재사용으로 UPLOAD_PENDING → COMPLETED 점프가 가능해야 한다
    documentRepository.save(Document.create(
            docId, randomChecksum(), paper.getFileKey(), paper.getId(), Instant.now()));
    tx.execute(s -> documentRepository.markParsed(docId, DocumentStatus.COMPLETED, null, Instant.now()));
    tx.execute(s -> paperRepository.linkDocument(paper.getId(), docId, Instant.now()));

    mockMvc.perform(get("/api/papers/{id}/status", paper.getId()).with(userJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
}
```

주의: `documentRepository.markParsed`·`paperRepository.linkDocument`는 `@Modifying` 쿼리라 트랜잭션이 필요하다. `IntegrationTest`에 `@Autowired protected TransactionTemplate tx;`를 추가하고 `tx.execute(s -> ...)`로 감싼다 (Task 3 테스트와 동일 패턴). `DocumentRepository`도 `IntegrationTest`에 `@Autowired protected DocumentRepository documentRepository;`로 올려 하위 테스트가 공유하게 한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperStatusPollingIntegrationTest'`
Expected: 신규 테스트 FAIL — status API가 paper.status(UPLOAD_PENDING)를 반환

- [ ] **Step 3: 구현**

`PaperDocumentViews.java`:

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/**
 * Paper 응답 값의 파생 규칙 — 파싱 상태의 진실 원천은 연결된 Document 하나다.
 * 연결 전(document 없음)은 paper 자신의 상태를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class PaperDocumentViews {

    private final DocumentRepository documentRepository;

    public Optional<Document> documentOf(Paper paper) {
        if (paper.getDocumentId() == null) {
            return Optional.empty();
        }
        return documentRepository.findById(paper.getDocumentId());
    }

    public PaperStatusView statusView(Paper paper) {
        Document document = documentOf(paper).orElse(null);
        return new PaperStatusView(paper.getId(), derivedStatus(paper, document),
                derivedUpdatedAt(paper, document));
    }

    public List<PaperListView> listViews(List<Paper> papers) {
        List<UUID> documentIds = papers.stream()
                .map(Paper::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, Document> documents = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        return papers.stream()
                .map(p -> {
                    Document document = p.getDocumentId() == null
                            ? null : documents.get(p.getDocumentId());
                    return new PaperListView(p.getId(), p.getFilename(),
                            derivedStatus(p, document), p.getCreatedAt(),
                            derivedUpdatedAt(p, document));
                })
                .toList();
    }

    /** Document 상태는 API PaperStatus와 이름 1:1이다. 연결 전에는 paper 상태 그대로. */
    static PaperStatus derivedStatus(Paper paper, Document document) {
        if (document == null) {
            return paper.getStatus();
        }
        return PaperStatus.valueOf(document.getStatus().name());
    }

    /** "이 Paper의 표시 상태가 마지막으로 바뀐 시각" = 연결 시각과 Document 전이 시각 중 최신. */
    static Instant derivedUpdatedAt(Paper paper, Document document) {
        if (document == null || paper.getUpdatedAt().isAfter(document.getUpdatedAt())) {
            return paper.getUpdatedAt();
        }
        return document.getUpdatedAt();
    }
}
```

`PaperStatusService.getStatus` — 마지막 줄만 교체: `return PaperStatusView.from(paper);` → `return views.statusView(paper);` (`private final PaperDocumentViews views;` 주입).

`PaperListService.list` — `return views.listViews(paperRepository.findAllByOwnerId(ownerId));`

`PaperDownloadService.download` — 상태 게이트·파일 key를 document-인지로:

```java
Document document = views.documentOf(paper).orElse(null);
if (document != null) {
    return fileStorage.presignDownload(document.getFileKey(), paper.getFilename());
}
PaperStatus status = paper.getStatus();
if (status == PaperStatus.UPLOAD_PENDING || status == PaperStatus.EXPIRED) {
    throw new ApiException(ErrorCode.UPLOAD_NOT_FOUND, "업로드된 원본 파일이 없습니다: " + paperId);
}
return fileStorage.presignDownload(paper.getFileKey(), paper.getFilename());
```

`PaperContentQueryService.getContent` — COMPLETED 게이트만 교체:

```java
if (PaperDocumentViews.derivedStatus(paper, views.documentOf(paper).orElse(null))
        != PaperStatus.COMPLETED) {
    throw new ApiException(ErrorCode.PAPER_NOT_READY, "논문이 아직 완료 상태가 아닙니다.");
}
```

`PaperChatAccessValidator.validateChatReady` — 같은 방식으로 derived COMPLETED 판정으로 교체.

- [ ] **Step 4: 확인 후 커밋**

Run: `./gradlew test` → 전체 PASS (기존 테스트는 document가 없으므로 fallback으로 동작 불변)

```bash
git add -A && git commit -m "[YMC-280] feat(be): 조회 경로에 Document 파생 상태 규칙 도입"
```

---

### Task 5: 쓰기 전환 — complete 재작성(연결·생성·발행 규칙) + 결과 소비 Document 전환

가장 큰 태스크. 이후 complete는 Paper 상태를 전이시키지 않고 Document만 만든다.

**Files:**
- Create: `src/main/java/com/ymc/paper/service/PaperDocumentLinkService.java`
- Create: `src/main/java/com/ymc/paper/service/DocumentParsingStarter.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java` (전면 재작성)
- Modify: `src/main/java/com/ymc/paper/service/ParseResultService.java`
- Modify: `src/main/java/com/ymc/paper/infra/messaging/message/ParseResultMessage.java` (`terminalStatus()`를 `DocumentStatus`로)
- Modify: `src/test/java/com/ymc/support/IntegrationTest.java` (spy 교체·헬퍼 재작성)
- Test: `src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java` (개정)
- Test: `src/test/java/com/ymc/paper/infra/messaging/ParseResultConsumptionIntegrationTest.java` (개정)

**Interfaces:**
- Produces: `PaperDocumentLinkService.linkOrCreate(UUID paperId, String fileKey, String checksumSha256)→LinkOutcome(Document document, boolean linkedToExisting)` / `DocumentParsingStarter.startIfUploaded(UUID documentId)`
- Consumes: Task 2 `UploadedObjectMetadata.checksumSha256()`, Task 3 도메인 전부, Task 4 `PaperDocumentViews`
- 이 태스크 이후: `PaperTransitions`는 호출부가 없어진다(삭제는 Task 7). 적재(`PaperContentIngestService`)는 아직 paperId(=requestPaperId) 키다 — Task 6에서 전환.

- [ ] **Step 1: 테스트 인프라 전환**

`IntegrationTest.java`:

1. spy 교체 — `@MockitoSpyBean protected PaperTransitions paperTransitions;` 를 삭제하고 추가:

```java
@MockitoSpyBean
protected DocumentTransitions documentTransitions;

@MockitoSpyBean
protected DocumentParsingStarter documentParsingStarter;
```

(spy 조합은 컨텍스트 캐시 키다 — 반드시 베이스에서만 바꾸고 하위 클래스에서 개별 선언하지 않는다.)

2. `resetState()`에 `documentRepository.deleteAll();` 추가 (`paperRepository.deleteAll();` 다음 — paper의 FK가 document를 참조하므로 paper 먼저).

3. 헬퍼 재작성:

```java
/** UPLOADED document에 연결된 Paper — 발행 직전 상태. */
protected Document givenLinkedDocument(Paper paper) {
    Document document = documentRepository.save(Document.create(
            UUID.randomUUID(), randomChecksum(), paper.getFileKey(), paper.getId(), Instant.now()));
    tx.execute(s -> paperRepository.linkDocument(paper.getId(), document.getId(), Instant.now()));
    return document;
}

/** 파싱 진행 중 상태 — 결과 수신 시나리오의 출발점. */
protected Paper givenProcessingPaper(String filename) {
    Paper paper = givenPendingPaper(filename);
    Document document = givenLinkedDocument(paper);
    documentTransitions.markProcessing(document.getId());
    clearInvocations(documentTransitions);
    return reload(paper.getId());
}
```

기존에 `paperTransitions`를 쓰던 모든 테스트가 컴파일 에러로 드러난다 — `documentTransitions`·document 헬퍼 기준으로 하나씩 고친다(아래 Step 2·5의 코드가 기준).

- [ ] **Step 2: 실패 테스트 작성 (complete 개정의 핵심 케이스)**

`PaperUploadCompletionIntegrationTest`의 기존 케이스를 다음 기준으로 개정·추가:

```java
@Test
void 신규_checksum이면_document를_만들고_발행하고_PROCESSING을_반환한다() throws Exception {
    Paper paper = givenPendingPaper("first.pdf");
    givenUploadedObject(paper);

    mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSING"));

    Paper linked = reload(paper.getId());
    assertThat(linked.getDocumentId()).isNotNull();
    Document document = documentRepository.findById(linked.getDocumentId()).orElseThrow();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
    assertThat(document.getRequestPaperId()).isEqualTo(paper.getId());
    assertThat(document.getFileKey()).isEqualTo(paper.getFileKey());
    verify(parseRequestPublisher).publish(paper.getId(), paper.getFileKey());
}

@Test
void 기존_checksum이면_연결만_하고_발행없이_현재_상태를_반환하고_중복_객체를_지운다() throws Exception {
    // 선행자: 같은 바이트를 먼저 완료
    Paper first = givenPendingPaper("origin.pdf");
    givenUploadedObject(first);
    mockMvc.perform(post("/api/papers/{id}/complete", first.getId()).with(userJwt()))
            .andExpect(status().isOk());
    clearInvocations(parseRequestPublisher);

    // 다른 사용자가 같은 바이트를 다른 파일명으로 업로드
    Paper second = paperRepository.save(Paper.register(OTHER_USER_ID, "copy.pdf", Instant.now()));
    givenUploadedObject(second);

    mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSING"));   // 기존 작업 상태 즉시 반환

    assertThat(reload(second.getId()).getDocumentId())
            .isEqualTo(reload(first.getId()).getDocumentId());
    verify(parseRequestPublisher, never()).publish(any(), any());
    verify(fileStorage).delete(second.getFileKey());     // 중복 객체 삭제
    verify(fileStorage, never()).delete(first.getFileKey());   // 대표 원본은 보존
}

@Test
void 발행_실패면_5xx고_document는_UPLOADED로_반납되고_재호출이_구제한다() throws Exception {
    Paper paper = givenPendingPaper("rescue.pdf");
    givenUploadedObject(paper);
    doThrow(new RuntimeException("SQS down")).doCallRealMethod()
            .when(parseRequestPublisher).publish(any(), any());

    mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
            .andExpect(status().is5xxServerError());

    Document document = documentRepository
            .findById(reload(paper.getId()).getDocumentId()).orElseThrow();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADED);   // 반납됨

    // 같은 Paper의 complete 재호출이 구제 발행한다 (HEAD 없이)
    clearInvocations(fileStorage);
    mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSING"));
    verify(fileStorage, never()).head(any());
    verify(parseRequestPublisher, times(2)).publish(paper.getId(), paper.getFileKey());
}

@Test
void 이미_연결된_paper의_재호출은_HEAD없이_현재_상태를_반환한다() throws Exception {
    Paper paper = givenProcessingPaper("idem.pdf");
    clearInvocations(fileStorage, parseRequestPublisher);

    mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSING"));

    verify(fileStorage, never()).head(any());
    verify(parseRequestPublisher, never()).publish(any(), any());
}

@Test
void 중복_객체_삭제가_실패해도_complete는_성공한다() throws Exception {
    Paper first = givenPendingPaper("origin2.pdf");
    givenUploadedObject(first);
    mockMvc.perform(post("/api/papers/{id}/complete", first.getId()).with(userJwt()))
            .andExpect(status().isOk());

    Paper second = paperRepository.save(Paper.register(OTHER_USER_ID, "copy2.pdf", Instant.now()));
    givenUploadedObject(second);
    doThrow(new RuntimeException("delete fail")).when(fileStorage).delete(second.getFileKey());

    mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
            .andExpect(status().isOk());
    assertThat(reload(second.getId()).getDocumentId()).isNotNull();
}
```

기존 케이스 중 유지·개정: 404/403(그대로), `UPLOAD_NOT_FOUND`(그대로), 413+객체 삭제(그대로), checksum 누락 409(Task 2), "발행 실패 시 UPLOADED 정체" 케이스는 위의 "반납+구제" 케이스로 대체.

- [ ] **Step 3: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperUploadCompletionIntegrationTest'`
Expected: 컴파일 에러(신규 서비스 미존재) → 구현 후 재실행

- [ ] **Step 4: 구현**

`PaperDocumentLinkService.java`:

```java
package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/**
 * complete의 "checksum → Document 결정 + Paper 연결"을 한 트랜잭션으로 처리한다.
 * 생성 경쟁의 패자는 ON CONFLICT 0 row로 감지해 기존 Document 연결로 전환한다.
 */
@Service
@RequiredArgsConstructor
public class PaperDocumentLinkService {

    private final DocumentRepository documentRepository;
    private final PaperRepository paperRepository;

    public record LinkOutcome(Document document, boolean linkedToExisting) {
    }

    @Transactional
    public LinkOutcome linkOrCreate(UUID paperId, String fileKey, String checksumSha256) {
        Instant now = Instant.now();
        boolean created = documentRepository.insertIfAbsent(
                UUID.randomUUID(), checksumSha256, fileKey, paperId, now) == 1;
        Document document = documentRepository.findByChecksumSha256(checksumSha256)
                .orElseThrow(() -> new IllegalStateException(
                        "생성 직후 조회에 실패한 document: paperId=" + paperId));
        // 0 row = 같은 Paper의 동시 complete가 먼저 연결 — 결과가 같으므로 그대로 진행
        paperRepository.linkDocument(paperId, document.getId(), now);
        return new LinkOutcome(document, !created);
    }
}
```

`DocumentParsingStarter.java`:

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.service.port.ParseRequestPublisher;

import lombok.RequiredArgsConstructor;

/**
 * 발행 규칙 — 생성·연결·멱등 재호출 모든 경로가 이 메서드 하나를 거친다.
 * UPLOADED면 선점(CAS) 승자만 발행하고, 실패하면 반납해 다음 complete가 구제할 수 있게 한다.
 * 메시지 식별자는 언제나 Document에 보존된 최초 paperId다.
 */
@Service
@RequiredArgsConstructor
public class DocumentParsingStarter {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsingStarter.class);

    private final DocumentRepository documentRepository;
    private final DocumentTransitions transitions;
    private final ParseRequestPublisher parseRequestPublisher;

    public void startIfUploaded(UUID documentId) {
        Document document = documentRepository.findById(documentId).orElseThrow(
                () -> new IllegalStateException("존재하지 않는 document: " + documentId));
        if (document.getStatus() != DocumentStatus.UPLOADED) {
            return;
        }
        if (!transitions.markProcessing(documentId)) {
            return;   // 다른 요청이 선점
        }
        try {
            parseRequestPublisher.publish(document.getRequestPaperId(), document.getFileKey());
        } catch (RuntimeException e) {
            revertBestEffort(documentId);
            log.warn("파싱 요청 발행 실패, UPLOADED 반납: documentId={}, requestPaperId={}",
                    documentId, document.getRequestPaperId(), e);
            throw e;
        }
        log.info("파싱 요청 발행: documentId={}, requestPaperId={}",
                documentId, document.getRequestPaperId());
    }

    private void revertBestEffort(UUID documentId) {
        try {
            transitions.revertToUploaded(documentId);
        } catch (RuntimeException revertFailure) {
            // 반납까지 실패하면 PROCESSING 정체 — 후속 정리 스윕(별도 티켓)의 대상
            log.warn("PROCESSING 반납 실패, 정체 가능: documentId={}", documentId, revertFailure);
        }
    }
}
```

`PaperUploadCompletionService.java` 전면 재작성:

```java
package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.PaperDocumentLinkService.LinkOutcome;
import com.ymc.paper.service.port.FileStorage;
import com.ymc.paper.service.port.UploadedObjectMetadata;

import lombok.RequiredArgsConstructor;

/**
 * 업로드 완료 통보 — S3 검증 checksum으로 Document를 결정하고 발행 규칙을 실행한다.
 *
 * <pre>
 * paper 조회·소유 확인
 * → 이미 연결됨: 발행 규칙(정체 구제 창구) 후 파생 상태 반환 (HEAD 없음)
 * → 미연결: HEAD(checksum 포함) 검증 → [Tx] Document 생성/연결 → 중복 객체 삭제(best-effort)
 *          → 발행 규칙 → 파생 상태 반환
 * </pre>
 *
 * <p><b>이 클래스에 {@code @Transactional}을 걸지 말 것.</b> 연결 커밋·선점 커밋·큐 발행은
 * 서로 다른 경계여야 한다 — 경계는 {@link PaperDocumentLinkService}·{@link DocumentTransitions}가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class PaperUploadCompletionService {

    private static final Logger log = LoggerFactory.getLogger(PaperUploadCompletionService.class);

    private final PaperRepository paperRepository;
    private final PaperDocumentLinkService linkService;
    private final DocumentParsingStarter parsingStarter;
    private final PaperDocumentViews views;
    private final FileStorage fileStorage;
    private final PaperUploadPolicy uploadPolicy;

    public PaperStatusView complete(UUID paperId, UUID ownerId) {
        Paper paper = find(paperId);
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }

        // 멱등 재호출. 발행 규칙을 먼저 거치는 이유: 발행 실패로 UPLOADED에 정체된 Document를
        // 재호출이 구제할 수 있는 유일한 창구이기 때문이다.
        if (paper.getDocumentId() != null) {
            parsingStarter.startIfUploaded(paper.getDocumentId());
            return views.statusView(find(paperId));
        }

        UploadedObjectMetadata uploadedObject = fileStorage.head(paper.getFileKey()).orElseThrow(() ->
                new ApiException(ErrorCode.UPLOAD_NOT_FOUND,
                        "업로드된 파일을 찾을 수 없습니다. 업로드 후 다시 시도해 주세요."));

        if (uploadPolicy.exceedsLimit(uploadedObject.contentLength())) {
            log.warn("상한 초과 객체 삭제: paperId={}, contentLength={}, limit={}",
                    paperId, uploadedObject.contentLength(), uploadPolicy.maxFileBytes());
            fileStorage.delete(paper.getFileKey());
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, uploadPolicy.tooLargeMessage());
        }

        if (uploadedObject.checksumSha256() == null || uploadedObject.checksumSha256().isBlank()) {
            throw new ApiException(ErrorCode.UPLOAD_CHECKSUM_MISSING,
                    "업로드 객체에 검증된 checksum이 없습니다. 같은 파일을 다시 업로드한 뒤 재시도해 주세요.");
        }

        LinkOutcome outcome = linkService.linkOrCreate(
                paperId, paper.getFileKey(), uploadedObject.checksumSha256());
        log.info("document {}: paperId={}, documentId={}",
                outcome.linkedToExisting() ? "연결" : "생성", paperId, outcome.document().getId());

        // 연결 커밋 후에만 삭제. 대표 원본과 같은 key면 절대 지우지 않는다 —
        // 같은 Paper의 동시 complete에서 패자가 대표 원본을 지우는 사고 방지.
        if (outcome.linkedToExisting()
                && !paper.getFileKey().equals(outcome.document().getFileKey())) {
            deleteBestEffort(paperId, paper.getFileKey());
        }

        parsingStarter.startIfUploaded(outcome.document().getId());
        return views.statusView(find(paperId));
    }

    private void deleteBestEffort(UUID paperId, String fileKey) {
        try {
            fileStorage.delete(fileKey);
        } catch (RuntimeException e) {
            // 잔여 객체는 후속 정리 작업이 재삭제한다 — complete를 실패로 되돌리지 않는다
            log.warn("중복 업로드 객체 삭제 실패: paperId={}, fileKey={}", paperId, fileKey, e);
        }
    }

    private Paper find(UUID paperId) {
        return paperRepository.findById(paperId).orElseThrow(() -> {
            log.debug("존재하지 않는 paperId로 complete 호출: {}", paperId);
            return new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId);
        });
    }
}
```

`ParseResultMessage` — `PaperStatus` import를 `DocumentStatus`로 바꾸고 `terminalStatus()`·`contractViolation()`의 타입만 치환한다 (로직 동일):

```java
public DocumentStatus terminalStatus() {
    if (STATUS_COMPLETED.equals(status)) {
        return DocumentStatus.COMPLETED;
    }
    if (STATUS_FAILED.equals(status)) {
        return DocumentStatus.FAILED;
    }
    return null;
}
```

`ParseResultService.java` 재작성 (적재는 아직 paperId 키 — Task 6에서 documentId로):

```java
package com.ymc.paper.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 결과 반영 — 메시지의 paper_id(= Document의 requestPaperId)로 Document를 역조회해
 * 전이·적재한다. 대표 Paper가 삭제돼도 이 역조회는 성립한다.
 */
@Service
@RequiredArgsConstructor
public class ParseResultService {

    private static final Logger log = LoggerFactory.getLogger(ParseResultService.class);

    private final DocumentTransitions transitions;
    private final DocumentRepository documentRepository;
    private final PaperContentIngestService ingestService;

    public void apply(UUID requestPaperId, DocumentStatus terminal, String errorCode,
            String manifestKey) {
        Optional<Document> found = documentRepository.findByRequestPaperId(requestPaperId);
        if (found.isEmpty()) {
            log.warn("파싱 결과 미반영, 대응 document 없음: requestPaperId={}", requestPaperId);
            return;
        }
        UUID documentId = found.get().getId();

        boolean transitioned = transitions.markParsed(documentId, terminal, errorCode);
        if (transitioned) {
            log.info("파싱 결과 반영: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, terminal);
        } else {
            log.warn("파싱 결과 미반영, 이미 terminal: requestPaperId={}, documentId={}, status={}",
                    requestPaperId, documentId, terminal);
        }

        if (terminal != DocumentStatus.COMPLETED || manifestKey == null) {
            return;
        }
        boolean completed = transitioned || documentRepository.findById(documentId)
                .map(d -> d.getStatus() == DocumentStatus.COMPLETED)
                .orElse(false);
        if (completed && !ingestService.isIngested(requestPaperId)) {
            ingestService.ingest(requestPaperId, manifestKey);
            log.info("본문 적재 완료: requestPaperId={}, documentId={}", requestPaperId, documentId);
        }
    }
}
```

(`ParseResultListener`는 시그니처가 그대로 맞아 변경 없음.)

- [ ] **Step 5: 소비 테스트 개정**

`ParseResultConsumptionIntegrationTest` — 기준 변화 두 가지만 반영:
1. 픽스처는 `givenProcessingPaper(...)`가 만든 document 기준. 상태 검증은 `paperRepository`의 status가 아니라 **status API 또는 document 상태**로 바꾼다.
2. "알 수 없는 paperId" 케이스는 "대응 document 없음 → WARN + ack(재전달 없음)"으로 의미 유지.
"UPLOADED 선도착 흡수"는 `givenLinkedDocument`(UPLOADED)에 결과를 발행해 document가 terminal이 되는 것으로 검증.

- [ ] **Step 6: 전체 확인 후 커밋**

Run: `./gradlew test` → PASS (컴파일 에러로 드러나는 나머지 테스트 호출부를 모두 새 헬퍼 기준으로 갱신)

```bash
git add -A && git commit -m "[YMC-280] feat(be): complete를 Document 생성·연결·발행 규칙으로 전환, 결과 소비를 역조회로 전환"
```

---

### Task 6: 파싱 산출물을 Document 키로 전환 (`document_content*`)

**Files:**
- Rename+Modify: `PaperContent`→`src/main/java/com/ymc/paper/domain/DocumentContent.java` (PK `document_id`, 테이블 `document_content`)
- Rename+Modify: `PaperContentBlock`→`DocumentContentBlock.java` (`uk_document_content_block(document_id, block_id)`, `ix_document_content_block_order(document_id, global_order)`)
- Rename+Modify: `PaperContentAsset`→`DocumentContentAsset.java` (`uk_document_content_asset(document_id, asset_key)`)
- Rename+Modify: 대응 Repository 3개 (`deleteByPaperId`→`deleteByDocumentId` 등)
- Rename+Modify: `PaperContentIngestService`→`DocumentContentIngestService.java` (`ingest(UUID documentId, String manifestKey)`, `isIngested(UUID documentId)`)
- Modify: `src/main/java/com/ymc/paper/service/ParseResultService.java` (적재 키를 documentId로)
- Modify: `src/main/java/com/ymc/paper/service/PaperContentQueryService.java` (document 기준 조회)
- Create: `docs/db/document_content.sql`
- Test: `src/test/java/com/ymc/paper/api/PaperContentIntegrationTest.java` (개정 + 공유 케이스)

**Interfaces:**
- Produces: `DocumentContentIngestService.ingest(UUID documentId, String manifestKey)`, `isIngested(UUID documentId)` / 엔티티 팩토리들은 첫 파라미터가 `UUID documentId`로 바뀌는 것 외 동일
- Consumes: Task 5의 `ParseResultService` (적재 호출 두 줄이 `documentId` 기준으로 바뀜: `ingestService.isIngested(documentId)` / `ingestService.ingest(documentId, manifestKey)`)

- [ ] **Step 1: 실패 테스트 작성 — 산출물 공유**

`PaperContentIntegrationTest`에 추가:

```java
@Test
void 같은_document에_연결된_두_paper는_같은_본문을_받는다() throws Exception {
    Paper mine = givenPendingPaper("mine.pdf");
    Document document = givenLinkedDocument(mine);
    Paper theirs = paperRepository.save(Paper.register(OTHER_USER_ID, "theirs.pdf", Instant.now()));
    tx.execute(s -> paperRepository.linkDocument(theirs.getId(), document.getId(), Instant.now()));
    tx.execute(s -> documentRepository.markParsed(
            document.getId(), DocumentStatus.COMPLETED, null, Instant.now()));

    String manifestKey = givenPackageOnS3(document.getRequestPaperId());
    documentContentIngestService.ingest(document.getId(), manifestKey);

    mockMvc.perform(get("/api/papers/{id}/content", mine.getId()).with(userJwt()))
            .andExpect(status().isOk());
    mockMvc.perform(get("/api/papers/{id}/content", theirs.getId()).with(otherUserJwt()))
            .andExpect(status().isOk());
}
```

(`IntegrationTest`에 `@Autowired protected DocumentContentIngestService documentContentIngestService;` 추가. 기존 `paperContent*Repository` 필드·resetState는 개명된 타입으로 치환.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperContentIntegrationTest'` → 컴파일 실패

- [ ] **Step 3: 구현 (기계적 개명 + 키 전환)**

1. 엔티티 3개 개명: 클래스명·테이블명·컬럼(`paper_id`→`document_id`)·제약 이름을 위 Files 목록대로. 필드명 `paperId`→`documentId`, 팩토리 `of(UUID documentId, ...)`. 나머지 필드·주석 유지.
2. Repository 3개 개명: `DocumentContentRepository extends JpaRepository<DocumentContent, UUID>` 등. 쿼리 메서드 `findAllByDocumentIdOrderByGlobalOrderAsc`, `findAllByDocumentId`, `deleteByDocumentId`(JPQL의 필드 참조도 치환).
3. `DocumentContentIngestService` — 본문은 기존과 동일하되 키만 `documentId`.
4. `ParseResultService` — 마지막 블록을:

```java
if (completed && !ingestService.isIngested(documentId)) {
    ingestService.ingest(documentId, manifestKey);
    log.info("본문 적재 완료: requestPaperId={}, documentId={}", requestPaperId, documentId);
}
```

5. `PaperContentQueryService` — 소유 확인 후 document를 얻어 그 키로 조회:

```java
Document document = views.documentOf(paper).orElseThrow(() ->
        new ApiException(ErrorCode.PAPER_NOT_READY, "논문이 아직 완료 상태가 아닙니다."));
if (document.getStatus() != DocumentStatus.COMPLETED) {
    throw new ApiException(ErrorCode.PAPER_NOT_READY,
            "논문이 아직 완료 상태가 아닙니다: " + document.getStatus());
}
DocumentContent content = contentRepository.findById(document.getId()).orElseThrow(
        () -> new ApiException(ErrorCode.PAPER_NOT_READY, "본문이 아직 적재되지 않았습니다."));
// blocks·assets 조회도 document.getId() 기준으로 치환. 응답 조립(PaperContentView)은 동일 —
// 첫 인자 paperId는 응답용이므로 paper.getId()를 유지한다.
```

6. `docs/db/document_content.sql` 신설:

```sql
-- 파싱 산출물 3테이블 — document 단위로 공유된다. 행 존재 = 적재 완료.
-- prod는 validate라 배포 전에 반영해야 한다. ⚠ 엔티티가 바뀌면 이 파일도 함께 고칠 것.

create table document_content (
    document_id    uuid                        not null,
    title          varchar(255),
    schema_version integer                     not null,
    ingested_at    timestamp(6) with time zone not null,
    primary key (document_id)
);

create table document_content_block (
    id            bigserial                   not null,
    document_id   uuid                        not null,
    block_id      varchar(255)                not null,
    global_order  integer                     not null,
    label         varchar(32)                 not null,
    heading_level integer,
    section_path  jsonb                       not null,
    content       jsonb                       not null,
    primary key (id),
    constraint uk_document_content_block unique (document_id, block_id)
);
create index ix_document_content_block_order on document_content_block (document_id, global_order);

create table document_content_asset (
    id          bigserial    not null,
    document_id uuid         not null,
    asset_key   varchar(255) not null,
    s3_key      varchar(255) not null,
    media_type  varchar(255) not null,
    primary key (id),
    constraint uk_document_content_asset unique (document_id, asset_key)
);
```

- [ ] **Step 4: 전체 확인 후 커밋**

Run: `./gradlew test` → PASS (`ParseResultContentIngestIntegrationTest`·`PaperContentIngestIntegrationTest`도 컴파일 에러 기준으로 documentId 픽스처로 개정)

```bash
git add -A && git commit -m "[YMC-280] feat(be): 파싱 산출물을 document_content*로 전환해 Paper 간 공유"
```

---

### Task 7: Paper 정리 — status·errorCode 제거, 레거시 전이 삭제, DDL 확정

**Files:**
- Modify: `src/main/java/com/ymc/paper/domain/Paper.java` (status·errorCode 필드 제거)
- Modify: `src/main/java/com/ymc/paper/domain/PaperRepository.java` (markUploaded·markProcessing·markParsed 삭제)
- Delete: `src/main/java/com/ymc/paper/service/PaperTransitions.java`
- Modify: `src/main/java/com/ymc/paper/service/PaperDocumentViews.java` (fallback을 상수로)
- Modify: `src/main/java/com/ymc/paper/service/PaperDownloadService.java` (레거시 분기 제거)
- Modify: `docs/db/paper.sql`
- Test: `src/test/java/com/ymc/paper/PaperFlowE2ETest.java` (전 구간 개정 확인)
- Test: `src/test/java/com/ymc/paper/domain/PaperTest.java` (status 관련 단위 케이스 정리)

**Interfaces:**
- Produces: 최종 저장 모델 — Paper는 `id, ownerId, filename, fileKey, documentId, createdAt, updatedAt`만 갖는다.
- Consumes: Task 4~6에서 파생 규칙이 이미 모든 읽기 경로를 담당 — 이 태스크는 죽은 코드를 걷어낸다.

- [ ] **Step 1: 제거**

1. `Paper` — `status`·`errorCode` 필드·컬럼·getter 제거. 생성자에서 `this.status = ...` 줄 제거. `PaperStatus` import 제거.
2. `PaperRepository` — CAS 3개 삭제.
3. `PaperTransitions` 삭제.
4. `PaperDocumentViews` — fallback 분기 교체:

```java
static PaperStatus derivedStatus(Paper paper, Document document) {
    if (document == null) {
        return PaperStatus.UPLOAD_PENDING;   // 연결 전 = 업로드 대기
    }
    return PaperStatus.valueOf(document.getStatus().name());
}
```

5. `PaperDownloadService` — 레거시 분기 제거:

```java
Document document = views.documentOf(paper).orElseThrow(() ->
        new ApiException(ErrorCode.UPLOAD_NOT_FOUND, "업로드된 원본 파일이 없습니다: " + paperId));
return fileStorage.presignDownload(document.getFileKey(), paper.getFilename());
```

6. `PaperContentQueryService`·`PaperChatAccessValidator`의 남은 `paper.getStatus()` 참조(있다면) 제거 — 컴파일 에러가 전수 목록이다. `PaperStatusView.from(Paper)`·`PaperListView.from(Paper)`도 더 이상 성립하지 않으므로 삭제(호출부는 Task 4에서 이미 파생 경로로 이동).
7. `PaperStatus` enum은 **유지** — API 계약 타입이다 (`EXPIRED` 포함).

- [ ] **Step 2: DDL 확정**

`docs/db/paper.sql` — `status`·`error_code` 컬럼과 check 제약을 제거하고 추가:

```sql
    document_id uuid,
    -- ...
    constraint fk_paper_document foreign key (document_id) references document (id)
```

인덱스 추가: `create index ix_paper_document on paper (document_id);`
헤더 주석의 상태 라이프사이클 설명을 "상태는 document가 소유하고 조회 시 파생한다"로 교체.

- [ ] **Step 3: 전체 확인**

Run: `./gradlew test`
Expected: PASS. 특히 `PaperFlowE2ETest`가 등록(checksum)→PUT(uploadHeaders)→complete→발행→결과→COMPLETED→content 전 구간을 Document 경유로 통과해야 한다. `PaperTest`의 status 단위 케이스는 Document/DocumentStatus 쪽으로 이동했으므로 삭제하거나 `PaperStatus` 계약 검증만 남긴다.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "[YMC-280] refactor(be): Paper의 상태 컬럼·레거시 전이 제거, DDL 확정"
```

---

### Task 8: 동시성·구제·수명주기 통합 테스트 (YMC-281)

**Files:**
- Create: `src/test/java/com/ymc/paper/api/DocumentDedupIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1~7의 전체 구현과 IntegrationTest 헬퍼. 새 프로덕션 코드는 없어야 정상이며, 여기서 결함이 나오면 이 태스크에서 고치고 같은 커밋에 포함한다.

- [ ] **Step 1: 시나리오 테스트 작성**

```java
package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

/** 스펙 §9의 중복 제거·동시성·수명주기 시나리오. */
class DocumentDedupIntegrationTest extends IntegrationTest {

    private Paper completedVia(UUID ownerId, String filename,
            org.springframework.test.web.servlet.request.RequestPostProcessor jwt) throws Exception {
        Paper paper = paperRepository.save(Paper.register(ownerId, filename, Instant.now()));
        givenUploadedObject(paper);
        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(jwt))
                .andExpect(status().isOk());
        return reload(paper.getId());
    }

    @Test
    void 다른_파일명의_같은_바이트는_같은_document를_공유하고_파싱은_한_번만_발행된다() throws Exception {
        Paper a = completedVia(TEST_USER_ID, "a.pdf", userJwt());
        Paper b = completedVia(TEST_USER_ID, "b.pdf", userJwt());
        assertThat(a.getDocumentId()).isEqualTo(b.getDocumentId());
        verify(parseRequestPublisher, times(1)).publish(any(), any());
    }

    @Test
    void 다른_사용자의_같은_바이트도_같은_document를_공유한다() throws Exception {
        Paper mine = completedVia(TEST_USER_ID, "mine.pdf", userJwt());
        Paper theirs = completedVia(OTHER_USER_ID, "theirs.pdf", otherUserJwt());
        assertThat(mine.getDocumentId()).isEqualTo(theirs.getDocumentId());
        verify(parseRequestPublisher, times(1)).publish(any(), any());
    }

    @Test
    void 같은_checksum의_동시_complete는_document_하나와_발행_한_번만_만든다() throws Exception {
        Paper first = givenPendingPaper("race-1.pdf");
        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "race-2.pdf", Instant.now()));
        givenUploadedObject(first);
        givenUploadedObject(second);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        var f1 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/papers/{id}/complete", first.getId())
                    .with(userJwt())).andReturn().getResponse().getStatus();
        });
        var f2 = pool.submit(() -> {
            start.await();
            return mockMvc.perform(post("/api/papers/{id}/complete", second.getId())
                    .with(otherUserJwt())).andReturn().getResponse().getStatus();
        });
        start.countDown();
        assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        pool.shutdown();

        assertThat(reload(first.getId()).getDocumentId())
                .isEqualTo(reload(second.getId()).getDocumentId());
        verify(parseRequestPublisher, times(1)).publish(any(), any());
        assertThat(documentRepository.count()).isEqualTo(1);
    }

    @Test
    void 기존_document가_COMPLETED면_새_paper는_즉시_COMPLETED를_본다() throws Exception {
        Paper first = completedVia(TEST_USER_ID, "done-1.pdf", userJwt());
        tx.execute(s -> documentRepository.markParsed(
                first.getDocumentId(), DocumentStatus.COMPLETED, null, Instant.now()));

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "done-2.pdf", Instant.now()));
        givenUploadedObject(second);
        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 기존_document가_FAILED면_새_paper도_FAILED를_보고_재파싱하지_않는다() throws Exception {
        Paper first = completedVia(TEST_USER_ID, "fail-1.pdf", userJwt());
        tx.execute(s -> documentRepository.markParsed(
                first.getDocumentId(), DocumentStatus.FAILED, "PARSE_RETRIES_EXHAUSTED",
                Instant.now()));
        clearInvocations(parseRequestPublisher);

        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "fail-2.pdf", Instant.now()));
        givenUploadedObject(second);
        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        verify(parseRequestPublisher, times(0)).publish(any(), any());
    }

    @Test
    void 정체된_document는_다른_사용자의_complete가_구제_발행한다() throws Exception {
        // 발행 실패로 UPLOADED 정체를 만든다
        org.mockito.Mockito.doThrow(new RuntimeException("SQS down")).doCallRealMethod()
                .when(parseRequestPublisher).publish(any(), any());
        Paper first = givenPendingPaper("stuck.pdf");
        givenUploadedObject(first);
        mockMvc.perform(post("/api/papers/{id}/complete", first.getId()).with(userJwt()))
                .andExpect(status().is5xxServerError());

        // 같은 바이트를 올린 다른 사용자의 complete가 구제한다 — 발행 식별자는 최초 paperId
        Paper second = paperRepository.save(
                Paper.register(OTHER_USER_ID, "rescuer.pdf", Instant.now()));
        givenUploadedObject(second);
        mockMvc.perform(post("/api/papers/{id}/complete", second.getId()).with(otherUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(parseRequestPublisher, times(2)).publish(first.getId(), first.getFileKey());
    }

    @Test
    void 대표_paper가_삭제돼도_다른_paper의_상태·다운로드·결과_반영은_유지된다() throws Exception {
        Paper representative = completedVia(TEST_USER_ID, "rep.pdf", userJwt());
        Paper survivor = completedVia(OTHER_USER_ID, "survivor.pdf", otherUserJwt());
        UUID documentId = representative.getDocumentId();
        UUID requestPaperId = documentRepository.findById(documentId)
                .orElseThrow().getRequestPaperId();

        paperRepository.deleteById(representative.getId());   // 대표 삭제 — document는 남는다

        assertThat(documentRepository.findById(documentId)).isPresent();
        mockMvc.perform(get("/api/papers/{id}/status", survivor.getId()).with(otherUserJwt()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/papers/{id}/download", survivor.getId()).with(otherUserJwt()))
                .andExpect(status().isOk());

        // 대표 삭제 후 도착한 결과도 requestPaperId 역조회로 반영된다
        publishParseResult(requestPaperId);   // 기존 헬퍼 — completed 결과 발행
        awaitConsumed();
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.COMPLETED);
    }
}
```

주의: `publishParseResult`·`awaitConsumed` 헬퍼의 실제 시그니처는 `IntegrationTest`에 이미 있는 것을 따른다 (manifest key 인자가 필요하면 `givenPackageOnS3(requestPaperId)` 결과를 넘긴다). 마지막 케이스에서 대표 paper 삭제 전에 결과가 이미 반영돼 COMPLETED라면(`completedVia`가 PROCESSING까지 만들므로 아님) 검증 대상을 상태 유지로 바꾼다.

- [ ] **Step 2: 실행·결함 수정**

Run: `./gradlew test --tests 'com.ymc.paper.api.DocumentDedupIntegrationTest'`
Expected: PASS. 실패하면 프로덕션 결함이다 — 스펙 §4(발행 규칙·삭제 가드) 기준으로 수정하고 이 태스크 커밋에 포함한다.

- [ ] **Step 3: 전체 확인 후 커밋**

Run: `./gradlew test` → PASS

```bash
git add -A && git commit -m "[YMC-281] test(be): 동시 complete·구제 발행·수명주기 시나리오 검증"
```

---

## 커밋·브랜치 규칙

- 브랜치: `origin/main` 기준 `YMC-280-document-dedup` 신규 (Task 8만 브랜치를 이어 쓰되 커밋 키는 YMC-281).
- 태스크당 최소 1커밋. 태스크 내 스텝을 쪼개 커밋해도 된다(모두 green일 것).
- PR 본문: `## 배경`(스펙 링크 한 줄) · `## 변경사항` · `## 검증`(테스트 결과) · `## 의존`(스펙 PR #40, ADR PR project-docs#33).

## 하지 않는 것 (스펙 §11)

- 삭제 API·Document 물리 삭제·정리 스윕(후속 티켓), outbox, Flyway, MDC, 메시지 스키마 변경, 기존 데이터 이관, FAILED 재파싱, `CHAT_USAGE_LIMIT_EXCEEDED` 추가(별도 처리).
