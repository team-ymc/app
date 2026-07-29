# BE SQS 파싱 배선 (YMC-276) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 워커(YMC-229)가 메시지의 `fileKey`를 그대로 사용하는 전제에 맞춰, BE의 fileKey 형식을 바꾸고 상태 전이를 경합 안전한 CAS로 전환한다.

**Architecture:** 스펙은 `docs/superpowers/specs/2026-07-27-be-sqs-parse-wiring-design.md` (C1~C9). 메시지 필드·DB 컬럼 변경 없음. 전이 2개를 조건부 UPDATE(CAS)로 강화하고(`markParsed`에 UPLOADED 포함, `UPLOADED→PROCESSING` CAS 신설), 엔티티의 load-modify-save 전이를 제거한다. 기존 row는 마이그레이션 없이 저장된 `fileKey`를 그대로 발행한다.

**Tech Stack:** Spring Boot + Spring Data JPA (`@Modifying` JPQL bulk update), JUnit5 + AssertJ, Testcontainers(PostgreSQL) + LocalStack(SQS) 통합 테스트 (`support/IntegrationTest` 베이스).

## Global Constraints

- 커밋 메시지는 `[YMC-276] type(scope): subject` 형식. Claude attribution(Co-Authored-By 등) 금지.
- 계약 SSOT는 `project-docs/contracts/backend-ai/messaging.yml` — 코드 주석이 이 경로를 가리키게 한다. 코드에서 계약을 임의 정의하지 않는다.
- 신규 fileKey 형식은 `uploads/{paperId}/original.pdf` 정확히. 구형 키(`uploads/{ownerId}/{paperId}.pdf`)는 마이그레이션하지 않는다 — 저장된 값을 그대로 발행.
- `parse-results.result` 본문 해석·object 타입 강제는 범위 밖 (spec §5, FT-004 유예).
- 통합 테스트는 Docker 필요 (Testcontainers + LocalStack). 실행은 `app/be`에서 `./gradlew test`.
- 도메인 엔티티에는 `@Getter`만 (be/CLAUDE.md). 리포지토리 쿼리는 `@Query`(JPQL) 단계 사용.

## File Structure

| 파일 | 역할/변경 |
|---|---|
| `be/src/main/java/com/ymc/paper/domain/Paper.java` | C1 `FILE_KEY_FORMAT` 변경, C5 엔티티 `markProcessing` 제거, 클래스 javadoc 갱신 |
| `be/src/main/java/com/ymc/paper/domain/PaperRepository.java` | C2 `markParsed`에 UPLOADED 포함, C3 `markProcessing` CAS 신설 |
| `be/src/main/java/com/ymc/paper/service/PaperTransitions.java` | C4 `markProcessing`을 CAS + 3분기 재조회로 교체 |
| `be/src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java` | C6 주석 갱신 (동작 변경 없음) |
| `be/src/main/java/com/ymc/paper/service/ParseResultService.java` | C9 javadoc·주석 갱신 (동작 변경 없음) |
| `be/src/main/java/com/ymc/paper/infra/messaging/message/ParseRequestMessage.java` | C7 dangling 참조 교체 |
| `be/src/main/java/com/ymc/paper/infra/messaging/message/ParseResultMessage.java` | C7 dangling 참조 교체 |
| `be/src/main/java/com/ymc/paper/service/port/ParseRequestPublisher.java` | C8 dangling 참조 교체 |
| `be/src/test/java/com/ymc/paper/domain/PaperTest.java` | fileKey 형식 테스트 교체, `MarkProcessing` nested 삭제 |
| `be/src/test/java/com/ymc/paper/api/PaperRegistrationIntegrationTest.java` | 구형 fileKey assert 2곳 교체 |
| `be/src/test/java/com/ymc/paper/infra/messaging/ParseResultConsumptionIntegrationTest.java` | UPLOADED 결과 수신 테스트 신규 |
| `be/src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java` | 빠른 결과 선도착 경합 테스트 신규 |

모든 명령은 `app/be` 디렉토리에서 실행한다. git 명령은 `app` 루트에서 실행한다 (모노레포 루트가 repo 루트).

---

### Task 1: C1 — fileKey 형식을 `uploads/{paperId}/original.pdf`로 변경

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/Paper.java:36-37,77`
- Test: `be/src/test/java/com/ymc/paper/domain/PaperTest.java:44-50`
- Test: `be/src/test/java/com/ymc/paper/api/PaperRegistrationIntegrationTest.java:66,136-137`

**Interfaces:**
- Consumes: 없음 (독립 변경)
- Produces: `Paper.register(...)`가 만드는 `fileKey`가 `uploads/{paperId}/original.pdf`. 시그니처 변화 없음 — 이후 Task는 이 형식을 전제하지 않는다 (fileKey는 불투명 값).

- [ ] **Step 1: 단위 테스트를 신형식으로 교체 (failing test)**

`PaperTest.java`의 기존 테스트(45-50행)를 교체:

```java
// 교체 전 (삭제)
@Test
void register는_uploads_ownerId_paperId_형식의_fileKey를_만든다() {
    UUID ownerId = UUID.randomUUID();
    Paper paper = Paper.register(ownerId, "a.pdf", Instant.now());
    assertThat(paper.getFileKey())
            .isEqualTo("uploads/%s/%s.pdf".formatted(ownerId, paper.getId()));
}

// 교체 후
@Test
void register는_uploads_paperId_original_형식의_fileKey를_만든다() {
    Paper paper = Paper.register(UUID.randomUUID(), "a.pdf", Instant.now());
    assertThat(paper.getFileKey())
            .isEqualTo("uploads/%s/original.pdf".formatted(paper.getId()));
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.domain.PaperTest' 2>&1 | tail -20`
Expected: FAIL — `register는_uploads_paperId_original_형식의_fileKey를_만든다` (expected `uploads/{id}/original.pdf` but was `uploads/{ownerId}/{id}.pdf`)

- [ ] **Step 3: `Paper.java` 구현 변경**

36-37행의 상수와 javadoc:

```java
// 교체 전
/** 계약(openapi.yaml `PaperCreated.fileKey`)의 형식 — uploads/{ownerId}/{paperId}.pdf (ADR-002). */
private static final String FILE_KEY_FORMAT = "uploads/%s/%s.pdf";

// 교체 후
/**
 * 원본 PDF의 S3 key 형식 — uploads/{paperId}/original.pdf.
 * 형식은 계약이 아니라 BE 저장소 내부 구현이다 — AI는 메시지의 fileKey를 그대로 GetObject에
 * 쓴다 (contracts/backend-ai/messaging.yml `ParseRequest.fileKey`). 구형 키 row는 저장된 값을
 * 그대로 발행하므로 마이그레이션하지 않는다 (spec §1 역할 구분).
 */
private static final String FILE_KEY_FORMAT = "uploads/%s/original.pdf";
```

77행 생성자 할당:

```java
// 교체 전
this.fileKey = FILE_KEY_FORMAT.formatted(ownerId, id);

// 교체 후
this.fileKey = FILE_KEY_FORMAT.formatted(id);
```

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `./gradlew test --tests 'com.ymc.paper.domain.PaperTest' 2>&1 | tail -5`
Expected: PASS (BUILD SUCCESSFUL)

- [ ] **Step 5: 통합 테스트의 구형식 assert 2곳 교체**

`PaperRegistrationIntegrationTest.java` 66행:

```java
// 교체 전
assertThat(body.get("fileKey").asText()).isEqualTo("uploads/" + TEST_USER_ID + "/" + paperId + ".pdf");

// 교체 후
assertThat(body.get("fileKey").asText()).isEqualTo("uploads/" + paperId + "/original.pdf");
```

136-137행 (`소유자는_인증_주체다` 테스트 안):

```java
// 교체 전
assertThat(saved.getFileKey())
        .isEqualTo("uploads/%s/%s.pdf".formatted(TEST_USER_ID, saved.getId()));

// 교체 후
assertThat(saved.getFileKey())
        .isEqualTo("uploads/%s/original.pdf".formatted(saved.getId()));
```

- [ ] **Step 6: 통합 테스트 통과 확인 (Docker 필요)**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperRegistrationIntegrationTest' 2>&1 | tail -5`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
cd .. && git add be/src/main/java/com/ymc/paper/domain/Paper.java \
  be/src/test/java/com/ymc/paper/domain/PaperTest.java \
  be/src/test/java/com/ymc/paper/api/PaperRegistrationIntegrationTest.java \
  && git commit -m "[YMC-276] feat(be): fileKey 형식을 uploads/{paperId}/original.pdf로 변경" && cd be
```

---

### Task 2: C2 — 결과 수신 CAS에 UPLOADED 포함

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java:44-64`
- Modify: `be/src/main/java/com/ymc/paper/service/PaperTransitions.java:54-58` (javadoc만)
- Modify: `be/src/main/java/com/ymc/paper/service/ParseResultService.java:13-17,40-43` (javadoc·주석만)
- Test: `be/src/test/java/com/ymc/paper/infra/messaging/ParseResultConsumptionIntegrationTest.java`

**Interfaces:**
- Consumes: `PaperTransitions.markUploaded(UUID): boolean` (기존), `IntegrationTest.givenPendingPaper(String): Paper`, `publishParseResult(String)`, `reload(UUID): Paper` (기존 헬퍼)
- Produces: `PaperRepository.markParsed(UUID, PaperStatus, String, Instant): int` — 시그니처 동일, WHERE 조건만 `status IN (UPLOADED, PROCESSING)`으로 확대. Task 3의 경합 테스트가 이 의미론을 전제한다.

- [ ] **Step 1: UPLOADED 상태 결과 수신 통합 테스트 추가 (failing test)**

`ParseResultConsumptionIntegrationTest.java`에 테스트 추가 (`appliesCompletedResult` 아래):

```java
@Test
@DisplayName("UPLOADED 상태의 결과도 terminal로 전이된다 (PROCESSING 커밋 전 선도착 흡수)")
void appliesResultArrivingBeforeProcessing() {
    Paper paper = givenPendingPaper("early-result.pdf");
    paperTransitions.markUploaded(paper.getId());

    publishParseResult("""
            {"paperId": "%s", "status": "COMPLETED"}
            """.formatted(paper.getId()));

    awaitStatus(paper.getId(), PaperStatus.COMPLETED);
    assertThat(reload(paper.getId()).getErrorCode()).isNull();
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.infra.messaging.ParseResultConsumptionIntegrationTest.appliesResultArrivingBeforeProcessing' 2>&1 | tail -20`
Expected: FAIL — awaitStatus 타임아웃 (상태가 UPLOADED에 머묾: 현재 CAS는 PROCESSING만 허용)

- [ ] **Step 3: `PaperRepository.markParsed` WHERE 조건 확대**

44-64행 교체:

```java
// 교체 전 javadoc + 쿼리 중 해당 부분
/**
 * 결과 수신 시의 {@code PROCESSING → COMPLETED | FAILED}. 중복 수신·이미 terminal이면 0을 받는다.
 *
 * @param terminal  {@code COMPLETED} 또는 {@code FAILED}
 * @param errorCode 실패 코드. {@code COMPLETED}면 null
 * @return 변경된 row 수 (0이면 이미 전이됐거나 PROCESSING이 아님 — 경고 로그 후 소비)
 */
...
           and p.status = com.ymc.paper.domain.PaperStatus.PROCESSING

// 교체 후
/**
 * 결과 수신 시의 {@code UPLOADED | PROCESSING → COMPLETED | FAILED}. 중복 수신·이미 terminal이면 0을 받는다.
 *
 * <p>{@code UPLOADED}를 포함하는 이유: request 발행 후 PROCESSING 커밋 전에 결과가 도착하거나
 * BE가 죽는 경합을 흡수한다 (spec §3, ADR-002 Follow-ups).
 *
 * @param terminal  {@code COMPLETED} 또는 {@code FAILED}
 * @param errorCode 실패 코드. {@code COMPLETED}면 null
 * @return 변경된 row 수 (0이면 이미 terminal이거나 진행 전 상태 — 경고 로그 후 소비)
 */
...
           and p.status in (com.ymc.paper.domain.PaperStatus.UPLOADED,
                            com.ymc.paper.domain.PaperStatus.PROCESSING)
```

- [ ] **Step 4: 낡아지는 javadoc·주석 갱신 (C9 일부)**

`PaperTransitions.java` 54-58행 javadoc:

```java
// 교체 전
/**
 * 결과 수신 시의 {@code PROCESSING → COMPLETED | FAILED}.
 *
 * @return 전이했으면 true. false면 이미 terminal이거나 PROCESSING이 아니다 (중복 수신 등)
 */

// 교체 후
/**
 * 결과 수신 시의 {@code UPLOADED | PROCESSING → COMPLETED | FAILED}.
 *
 * @return 전이했으면 true. false면 이미 terminal이거나 UPLOAD_PENDING이다 (중복 수신 등)
 */
```

`ParseResultService.java` 클래스 javadoc 첫 줄(14행)과 apply의 미반영 분기 주석(40-42행):

```java
// 교체 전 (14행)
 * 파싱 결과 반영 — {@code PROCESSING → COMPLETED | FAILED} (design D2·D7).
// 교체 후
 * 파싱 결과 반영 — {@code UPLOADED | PROCESSING → COMPLETED | FAILED} (spec §3).

// 교체 전 (40-42행)
        // 알 수 없는 paperId이거나 이미 전이된(=PROCESSING이 아닌) 레코드. 중복 수신이거나,
        // complete의 PROCESSING 커밋이 실패해 UPLOADED에 머문 경우다 (design D6의 의도된 갭).
        // 어느 쪽이든 재전달해도 달라지지 않으므로 관측만 하고 정상 소비한다.
// 교체 후
        // 알 수 없는 paperId이거나 이미 terminal인 레코드(중복 수신 등). 재전달해도
        // 달라지지 않으므로 관측만 하고 정상 소비한다.
```

같은 파일 43행 로그 메시지도 조건에 맞게 교체:

```java
// 교체 전
log.warn("파싱 결과 미반영, PROCESSING 아님: paperId={}, status={}", paperId, terminal);
// 교체 후
log.warn("파싱 결과 미반영, 이미 terminal이거나 진행 전: paperId={}, status={}", paperId, terminal);
```

- [ ] **Step 5: 통과 확인 (기존 소비 테스트 포함 전체)**

Run: `./gradlew test --tests 'com.ymc.paper.infra.messaging.ParseResultConsumptionIntegrationTest' 2>&1 | tail -5`
Expected: PASS — 신규 테스트 포함 전부. 주의: `failedWithoutErrorCodeIsConsumed`·`nonRecoverableMessagesAreConsumed`는 PROCESSING 유지를 검증하는데, 이들은 계약 위반이라 `apply`까지 못 가므로 영향 없음.

- [ ] **Step 6: Commit**

```bash
cd .. && git add be/src/main/java/com/ymc/paper/domain/PaperRepository.java \
  be/src/main/java/com/ymc/paper/service/PaperTransitions.java \
  be/src/main/java/com/ymc/paper/service/ParseResultService.java \
  be/src/test/java/com/ymc/paper/infra/messaging/ParseResultConsumptionIntegrationTest.java \
  && git commit -m "[YMC-276] feat(be): 결과 수신 CAS에 UPLOADED 포함해 선도착 결과 흡수" && cd be
```

---

### Task 3: C3~C6 — `UPLOADED→PROCESSING` CAS 전환과 경합 3분기

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/domain/PaperRepository.java` (markProcessing CAS 추가)
- Modify: `be/src/main/java/com/ymc/paper/service/PaperTransitions.java:40-52`
- Modify: `be/src/main/java/com/ymc/paper/domain/Paper.java:17-26,98-110` (엔티티 markProcessing 제거)
- Modify: `be/src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java:83-90` (주석)
- Test: `be/src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java` (경합 테스트 신규)
- Test: `be/src/test/java/com/ymc/paper/domain/PaperTest.java` (MarkProcessing nested 삭제)

**Interfaces:**
- Consumes: Task 2의 `markParsed` UPLOADED 의미론 (경합 테스트가 UPLOADED에서 terminal 전이를 주입), `PaperStatus.isTerminal(): boolean` (기존), `PaperStatusView.from(Paper): PaperStatusView` (기존)
- Produces: `PaperRepository.markProcessing(UUID id, Instant now): int` (신규 CAS), `PaperTransitions.markProcessing(UUID): PaperStatusView` — 시그니처 유지, 의미 변경: 0 row + terminal 재조회 시 terminal view 반환(예외 아님), 0 row + 그 외는 `IllegalStateException`.

- [ ] **Step 1: 빠른 결과 선도착 경합 통합 테스트 추가 (failing test)**

`PaperUploadCompletionIntegrationTest.java`에 추가. import에 `static org.mockito.Mockito.doAnswer;` 필요:

```java
@Test
@DisplayName("빠른 결과 선도착: complete는 5xx 없이 terminal을 반환하고 PROCESSING이 덮지 않는다")
void fastResultBeforeProcessingReturnsTerminal() throws Exception {
    Paper paper = givenPendingPaper(FILENAME);
    givenUploadedObject(paper);

    // 발행 직후·PROCESSING 커밋 전에 빠른 FAILED 결과가 도착한 상황을 재현한다.
    // markParsed는 UPLOADED에서 terminal로 전이한다 (Task 2 / spec §3).
    doAnswer(invocation -> {
        invocation.callRealMethod();
        paperTransitions.markParsed(paper.getId(), PaperStatus.FAILED, "PDF_UNREADABLE");
        return null;
    }).when(parseRequestPublisher).publish(any(), anyString());

    mockMvc.perform(post("/api/papers/{paperId}/complete", paper.getId()).with(userJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"));

    Paper after = reload(paper.getId());
    assertThat(after.getStatus()).isEqualTo(PaperStatus.FAILED);       // PROCESSING이 덮지 않았다
    assertThat(after.getErrorCode()).isEqualTo("PDF_UNREADABLE");
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperUploadCompletionIntegrationTest.fastResultBeforeProcessingReturnsTerminal' 2>&1 | tail -20`
Expected: FAIL — 현재 `markProcessing()`은 load-modify-save라 FAILED 상태에서 `IllegalStateException`이 나가 5xx (MockMvc가 예외를 되던짐)

- [ ] **Step 3: `PaperRepository`에 CAS 추가**

`markUploaded` 아래에 추가:

```java
/**
 * 파싱 요청 발행 후의 {@code UPLOADED → PROCESSING}. 빠른 결과가 이미 terminal로
 * 전이시켰으면 0을 받는다 — 호출자가 재조회해 분기한다 (spec §3).
 */
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
        update Paper p
           set p.status = com.ymc.paper.domain.PaperStatus.PROCESSING,
               p.updatedAt = :now
         where p.id = :id
           and p.status = com.ymc.paper.domain.PaperStatus.UPLOADED
        """)
int markProcessing(@Param("id") UUID id, @Param("now") Instant now);
```

- [ ] **Step 4: `PaperTransitions.markProcessing`을 CAS + 3분기로 교체**

40-52행 교체:

```java
/**
 * 파싱 요청 발행에 성공한 뒤의 {@code UPLOADED → PROCESSING}. 별도 트랜잭션으로 커밋한다.
 *
 * <p>CAS가 0 row면 재조회해 분기한다 — terminal이면 빠른 결과가 먼저 온 정상 경합이라
 * 그 상태를 그대로 돌려주고(5xx 아님), 그 외는 예상하지 못한 상태라 예외를 던진다 (spec §3).
 *
 * <p>이 커밋이 실패하면 레코드는 {@code UPLOADED}에 남고 파싱은 진행된다 — ADR-001 §5가 문서화한
 * MVP 갭이라 복구하지 않는다.
 */
@Transactional
public PaperStatusView markProcessing(UUID paperId) {
    if (paperRepository.markProcessing(paperId, Instant.now()) == 1) {
        return PaperStatusView.from(reload(paperId));
    }
    Paper paper = reload(paperId);
    if (paper.getStatus().isTerminal()) {
        return PaperStatusView.from(paper);   // 빠른 결과 선도착 — 정상 경합
    }
    throw new IllegalStateException(
            "UPLOADED가 아닌 상태에서 PROCESSING 전이를 시도했습니다: paperId=%s, status=%s"
                    .formatted(paperId, paper.getStatus()));
}

private Paper reload(UUID paperId) {
    return paperRepository.findById(paperId).orElseThrow(
            () -> new IllegalStateException("전이 직전에 사라진 논문입니다: " + paperId));
}
```

- [ ] **Step 5: 엔티티 `Paper.markProcessing` 제거 + 클래스 javadoc 갱신**

`Paper.java` 98-110행의 `markProcessing(Instant)` 메서드 전체 삭제. 23-25행 클래스 javadoc 교체:

```java
// 교체 전
 * <p>{@code UPLOAD_PENDING → UPLOADED}와 {@code PROCESSING → terminal}은 여기 메서드가 아니라
 * {@link PaperRepository}의 조건부 UPDATE로 한다 — 동시 요청·중복 수신이 실재하는 전이라
 * load-modify-save로는 lost update를 막을 수 없다 (design D2). 경쟁이 없는 전이만 여기 둔다.

// 교체 후
 * <p>상태 전이는 전부 {@link PaperRepository}의 조건부 UPDATE(CAS)로 한다 — 동시 complete·
 * 결과 선도착·중복 수신이 실재하는 전이라 load-modify-save로는 lost update를 막을 수 없다
 * (spec §3). 엔티티에는 생성 불변식만 남는다.
```

- [ ] **Step 6: `PaperTest`의 `MarkProcessing` nested 클래스 삭제**

`PaperTest.java`에서 삭제할 것:
- `MarkProcessing` nested 클래스 전체 (69-98행)
- 이제 안 쓰는 헬퍼 `uploaded()`·`paperWith(PaperStatus)` (131-144행)
- 이제 안 쓰는 import: `assertThatCode`, `assertThatIllegalStateException`, `java.time.temporal.ChronoUnit`

`Register`·`Status` nested는 그대로 둔다.

- [ ] **Step 7: `PaperUploadCompletionService` 주석 갱신 (C6, 동작 변경 없음)**

83-89행의 주석 2곳 교체:

```java
// 교체 전
        // (3) PROCESSING 커밋 — 실패해도 파싱은 이미 진행 중이다. 마찬가지로 방치한다.
        try {
            return transitions.markProcessing(paperId);
        } catch (RuntimeException e) {
            // 발행은 이미 나갔다. 결과가 와도 PROCESSING CAS가 0 row라 반영되지 않는다.

// 교체 후
        // (3) PROCESSING 커밋 — CAS라 빠른 결과가 이미 terminal로 전이시켰으면 그 상태를
        //     그대로 돌려준다 (정상 경합, 5xx 아님). 여기서의 예외는 DB 장애 등 실제 오류뿐이다.
        try {
            return transitions.markProcessing(paperId);
        } catch (RuntimeException e) {
            // 발행은 이미 나갔다. 결과가 오면 UPLOADED에서 바로 terminal로 전이된다 (spec §3 C2).
```

- [ ] **Step 8: 신규 테스트 통과 + 기존 completion·단위 테스트 회귀 확인**

Run: `./gradlew test --tests 'com.ymc.paper.api.PaperUploadCompletionIntegrationTest' --tests 'com.ymc.paper.domain.PaperTest' 2>&1 | tail -5`
Expected: PASS — `processingCommitFailureLeavesRecordUploaded`(spy stub이 예외 주입)도 시그니처가 그대로라 계속 통과해야 한다

- [ ] **Step 9: Commit**

```bash
cd .. && git add be/src/main/java/com/ymc/paper/domain/Paper.java \
  be/src/main/java/com/ymc/paper/domain/PaperRepository.java \
  be/src/main/java/com/ymc/paper/service/PaperTransitions.java \
  be/src/main/java/com/ymc/paper/service/PaperUploadCompletionService.java \
  be/src/test/java/com/ymc/paper/domain/PaperTest.java \
  be/src/test/java/com/ymc/paper/api/PaperUploadCompletionIntegrationTest.java \
  && git commit -m "[YMC-276] feat(be): UPLOADED→PROCESSING 전이를 CAS로 전환해 결과 선도착 경합 방어" && cd be
```

---

### Task 4: C7·C8 — dangling 계약 참조 정리 + 전체 회귀

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/infra/messaging/message/ParseRequestMessage.java:5-9`
- Modify: `be/src/main/java/com/ymc/paper/infra/messaging/message/ParseResultMessage.java:10-11`
- Modify: `be/src/main/java/com/ymc/paper/service/port/ParseRequestPublisher.java:5-8`

**Interfaces:**
- Consumes: 없음 (주석만)
- Produces: 없음 — 동작 변경 없는 docs 커밋

- [ ] **Step 1: `ParseRequestMessage` javadoc 교체**

```java
// 교체 전 (6행)
 * contracts/schema/parse-request.schema.json 대응.
// 교체 후
 * project-docs/contracts/backend-ai/messaging.yml `ParseRequest` 대응.
```

- [ ] **Step 2: `ParseResultMessage` javadoc 교체**

```java
// 교체 전 (11행)
 * contracts/schema/parse-result.schema.json 대응. BE가 읽는 것은 envelope뿐이다 (design D7).
// 교체 후
 * project-docs/contracts/backend-ai/messaging.yml `ParseResult` 대응. BE가 읽는 것은 envelope뿐이다 (design D7).
```

- [ ] **Step 3: `ParseRequestPublisher` javadoc 교체**

파일을 열어 `asyncapi.yaml publishParseRequest`를 참조하는 javadoc 줄(7행 부근)을 교체:

```java
// 교체 전
 * (asyncapi.yaml `publishParseRequest`).
// 교체 후
 * (project-docs/contracts/backend-ai/messaging.yml `parse-requests` 채널).
```

- [ ] **Step 4: 잔여 dangling 참조 없는지 스캔**

Run: `grep -rn "schema.json\|asyncapi" src/main/java --include="*.java"`
Expected: 출력 없음 (`ErrorCode.java`의 `contracts/openapi.yaml`은 spec이 범위 밖으로 명시한 부수 발견 — 나오면 무시)

- [ ] **Step 5: 전체 테스트 스위트 회귀**

Run: `./gradlew test 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL — 전 모듈 green

- [ ] **Step 6: Commit**

```bash
cd .. && git add be/src/main/java/com/ymc/paper/infra/messaging/message/ParseRequestMessage.java \
  be/src/main/java/com/ymc/paper/infra/messaging/message/ParseResultMessage.java \
  be/src/main/java/com/ymc/paper/service/port/ParseRequestPublisher.java \
  && git commit -m "[YMC-276] docs(be): 계약 참조를 messaging.yml로 갱신 (dangling 정리)" && cd be
```
