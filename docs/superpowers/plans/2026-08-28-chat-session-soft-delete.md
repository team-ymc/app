# 채팅 세션 논리 삭제 전환 (YMC-355) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 세션 삭제를 물리 삭제에서 `deleted_at` 마킹으로 바꾸고, 삭제된 세션을 모든 접근 경로에서 404로 숨긴다.

**Architecture:** `chat_session.deleted_at` 컬럼 하나 추가. 삭제는 마킹, 메시지 row는 보존 — 진행 중 스트림의 종결 전이(`markCompleted`/`markFailed`)와 YMC-344 사용량 정산이 삭제 후에도 계속되게 한다. 조회·시작·재삭제는 삭제된 세션을 `CHAT_SESSION_NOT_FOUND`로 숨긴다(계약: "삭제됐거나 … 404").

**Tech Stack:** Spring Boot + JPA (기존 그대로), 통합 테스트는 `IntegrationTest` 베이스.

**Spec:** 별도 spec 없음 — bounded 변경. 근거: `docs/superpowers/specs/2026-08-28-plan-usage-limit-design.md` §5 선행 의존, FT-011 Story 2 AC("세션 데이터는 논리 삭제 상태로 보관"), openapi 404 설명.

## Global Constraints

- 커밋: `[YMC-355] type(scope): subject`. Claude attribution 금지.
- 엔티티는 `@Getter`만, 상태 변경은 의도 드러나는 메서드 (`be/CLAUDE.md`).
- 코드 주석에 티켓 키·문서 인용 금지 — 제약 내용만 짧게.
- 계약(openapi) 변경 없음 — 404 의미는 이미 "세션 없음·삭제됨·소유/논문 불일치".
- 테스트 실행: `cd be && ./gradlew test --tests '<클래스>'`.

---

### Task 1: 논리 삭제 코어 — 마킹 전환 + 메시지 보존

**Files:**
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatSession.java`
- Modify: `be/src/main/java/com/ymc/chat/service/ChatCommandService.java:151-161` (deleteSession)
- Modify: `be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java:148-180` (기존 테스트 2건 교체)
- Modify: `be/docs/db/chat.sql` (chat_session에 컬럼 추가)

**Interfaces:**
- Produces: `ChatSession.markDeleted(Instant now)`, `ChatSession.isDeleted(): boolean`, `ChatSession.getDeletedAt(): Instant` — Task 2·3이 사용.

- [ ] **Step 1: 기존 삭제 테스트 2건을 논리 삭제 기대로 교체**

`ChatSessionHistoryIntegrationTest`의 `deleteSessionCascadesMessages`(148행)와 `deleteSessionWhileGenerating`(166행)을 아래로 교체:

```java
@Test
@DisplayName("세션 삭제 — 204 후 deleted_at이 찍히고 세션·메시지 row는 남는다")
void deleteSessionMarksDeletedAndKeepsMessages() throws Exception {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult target = givenCompletedExchange(TEST_USER_ID, paper, null, "지울 세션");
    ChatStartResult keep = givenCompletedExchange(TEST_USER_ID, paper, null, "남길 세션");

    mockMvc.perform(delete("/api/papers/{paperId}/chat/sessions/{sessionId}",
                    paper.getId(), target.sessionId())
                    .with(userJwt()))
            .andExpect(status().isNoContent());

    assertThat(chatSessionRepository.findById(target.sessionId()))
            .hasValueSatisfying(s -> assertThat(s.getDeletedAt()).isNotNull());
    assertThat(chatSessionRepository.findById(keep.sessionId()))
            .hasValueSatisfying(s -> assertThat(s.getDeletedAt()).isNull());
    // 메시지는 삭제되지 않는다 — target·keep 각 2건
    assertThat(chatMessageRepository.count()).isEqualTo(4);
}

@Test
@DisplayName("GENERATING assistant가 있어도 삭제된다 — row는 GENERATING인 채 남는다")
void deleteSessionWhileGenerating() throws Exception {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult started = chatCommandService.start(
            TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "질문");
    // markCompleted 하지 않음 — assistant는 GENERATING인 채다

    mockMvc.perform(delete("/api/papers/{paperId}/chat/sessions/{sessionId}",
                    paper.getId(), started.sessionId())
                    .with(userJwt()))
            .andExpect(status().isNoContent());

    assertThat(chatSessionRepository.findById(started.sessionId()))
            .hasValueSatisfying(s -> assertThat(s.getDeletedAt()).isNotNull());
    assertThat(chatMessageRepository.count()).isEqualTo(2);
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'`
Expected: 위 2건 FAIL — 물리 삭제라 `findById`가 empty (`getDeletedAt` 컴파일 에러가 먼저면 Step 3의 엔티티부터).

- [ ] **Step 3: ChatSession에 deleted_at 추가**

`lastMessageAt` 필드 선언 아래에 추가:

```java
/** 논리 삭제 시각. 메시지 row는 남긴다 — 진행 중 답변의 종결 전이와 사용량 정산이 계속되어야 한다. */
@Column(name = "deleted_at")
private Instant deletedAt;
```

`recordActivity` 아래에 메서드 추가:

```java
public void markDeleted(Instant now) {
    this.deletedAt = now;
}

public boolean isDeleted() {
    return deletedAt != null;
}
```

- [ ] **Step 4: deleteSession을 마킹으로 전환**

`ChatCommandService.deleteSession`의 본문 마지막 두 줄을 교체하고, 메서드 Javadoc의 물리 삭제·FK 설명을 아래 내용으로 갱신:

```java
/**
 * 세션을 논리 삭제한다. 메시지 row는 남긴다 — GENERATING 중이어도 삭제할 수 있고,
 * 진행 중이던 relay의 종결 전이(markCompleted/markFailed)는 그대로 이어진다.
 *
 * <p>{@code findWithLockById}로 start와 직렬화한다 — 삭제 커밋 후 start는
 * 삭제된 세션을 보고 404를 반환한다.
 *
 * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN — 논문 검증 실패
 * @throws ApiException CHAT_SESSION_NOT_FOUND — 세션 없음·소유/논문 불일치·이미 삭제됨
 */
@Transactional
public void deleteSession(UUID ownerId, UUID paperId, UUID sessionId) {
    paperChatAccessValidator.validateOwned(paperId, ownerId);
    ChatSession session = chatSessionRepository.findWithLockById(sessionId)
            .orElseThrow(this::sessionNotFound);
    if (!session.belongsTo(ownerId, paperId) || session.isDeleted()) {
        throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션·삭제된 세션도 404 (계약)
    }
    session.markDeleted(Instant.now());
}
```

`chatMessageRepository.deleteBySessionId(sessionId);`와 `chatSessionRepository.delete(session);`은 삭제한다. `ChatMessageRepository.deleteBySessionId`를 다른 곳에서 안 쓰면 메서드도 지운다 (`grep -rn deleteBySessionId be/src`로 확인).

- [ ] **Step 5: 통과 확인**

Run: `cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'`
Expected: PASS (전체 클래스)

- [ ] **Step 6: prod DDL 반영**

`be/docs/db/chat.sql`의 `chat_session` 테이블 정의에 컬럼 추가 (기존 컬럼 서식에 맞춰):

```sql
deleted_at   timestamptz
```

- [ ] **Step 7: 커밋**

```bash
git add be/src/main/java/com/ymc/chat/domain/ChatSession.java \
  be/src/main/java/com/ymc/chat/service/ChatCommandService.java \
  be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java \
  be/docs/db/chat.sql
git commit -m "[YMC-355] feat(chat): 세션 삭제를 논리 삭제로 전환"
```

---

### Task 2: 삭제된 세션 접근 차단 — 목록·히스토리·시작

**Files:**
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatSessionRepository.java:16` (파생 쿼리 이름)
- Modify: `be/src/main/java/com/ymc/chat/service/ChatQueryService.java:37-51` (listSessions·listMessages)
- Modify: `be/src/main/java/com/ymc/chat/service/ChatCommandService.java:133-140` (resolveSession)
- Test: `be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java` (신규 4건)

**Interfaces:**
- Consumes: Task 1의 `isDeleted()`, `markDeleted(Instant)`.
- Produces: `ChatSessionRepository.findAllByOwnerIdAndPaperIdAndDeletedAtIsNullOrderByLastMessageAtDesc(UUID, UUID)` — listSessions 전용.

- [ ] **Step 1: 실패하는 테스트 4건 추가**

`ChatSessionHistoryIntegrationTest`에 추가. `assertThatThrownBy`는 `import static org.assertj.core.api.Assertions.assertThatThrownBy;`, `ApiException`·`ErrorCode`는 `com.ymc.common.error`에서 import:

```java
@Test
@DisplayName("삭제된 세션은 목록에서 빠진다")
void listSessionsExcludesDeleted() throws Exception {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult removed = givenCompletedExchange(TEST_USER_ID, paper, null, "지운 세션");
    ChatStartResult kept = givenCompletedExchange(TEST_USER_ID, paper, null, "남은 세션");
    chatCommandService.deleteSession(TEST_USER_ID, paper.getId(), removed.sessionId());

    mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", paper.getId())
                    .with(userJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].sessionId").value(kept.sessionId().toString()));
}

@Test
@DisplayName("삭제된 세션의 히스토리는 404 CHAT_SESSION_NOT_FOUND")
void listMessagesOfDeletedSessionNotFound() throws Exception {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult removed = givenCompletedExchange(TEST_USER_ID, paper, null, "지운 세션");
    chatCommandService.deleteSession(TEST_USER_ID, paper.getId(), removed.sessionId());

    mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                    paper.getId(), removed.sessionId())
                    .with(userJwt()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
}

@Test
@DisplayName("삭제된 세션으로 start하면 404 CHAT_SESSION_NOT_FOUND")
void startOnDeletedSessionNotFound() {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult removed = givenCompletedExchange(TEST_USER_ID, paper, null, "지운 세션");
    chatCommandService.deleteSession(TEST_USER_ID, paper.getId(), removed.sessionId());

    assertThatThrownBy(() -> chatCommandService.start(
            TEST_USER_ID, paper.getId(), removed.sessionId(), UUID.randomUUID(), "새 질문"))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code())
                    .isEqualTo(ErrorCode.CHAT_SESSION_NOT_FOUND));
}

@Test
@DisplayName("이미 삭제된 세션의 재삭제는 404 CHAT_SESSION_NOT_FOUND")
void deleteTwiceNotFound() throws Exception {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult removed = givenCompletedExchange(TEST_USER_ID, paper, null, "지운 세션");
    chatCommandService.deleteSession(TEST_USER_ID, paper.getId(), removed.sessionId());

    mockMvc.perform(delete("/api/papers/{paperId}/chat/sessions/{sessionId}",
                    paper.getId(), removed.sessionId())
                    .with(userJwt()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
}
```

`ApiException`의 접근자는 `code()`다 (`common/error/ApiException.java:15`).

- [ ] **Step 2: 실패 확인**

Run: `cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'`
Expected: 재삭제 404는 Task 1에서 이미 PASS, 나머지 3건 FAIL (목록에 노출·200 반환·start 성공).

- [ ] **Step 3: 조회·시작 차단 구현**

`ChatSessionRepository` 파생 쿼리 이름 교체:

```java
/** 목록 조회 (계약 listChatSessions). 삭제된 세션은 제외. 정렬 키는 비정규화된 lastMessageAt. */
List<ChatSession> findAllByOwnerIdAndPaperIdAndDeletedAtIsNullOrderByLastMessageAtDesc(
        UUID ownerId, UUID paperId);
```

`ChatQueryService.listSessions`의 호출을 새 이름으로 바꾸고, `listMessages`의 belongsTo 검사를 확장:

```java
if (!session.belongsTo(ownerId, paperId) || session.isDeleted()) {
```

`ChatCommandService.resolveSession`의 belongsTo 검사도 동일하게 확장:

```java
if (!session.belongsTo(ownerId, paperId) || session.isDeleted()) {
    throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션·삭제된 세션도 404 (계약)
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'`
Expected: PASS (전체 클래스)

- [ ] **Step 5: 커밋**

```bash
git add be/src/main/java/com/ymc/chat/domain/ChatSessionRepository.java \
  be/src/main/java/com/ymc/chat/service/ChatQueryService.java \
  be/src/main/java/com/ymc/chat/service/ChatCommandService.java \
  be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java
git commit -m "[YMC-355] feat(chat): 삭제된 세션을 조회·시작에서 404로 숨김"
```

---

### Task 3: 회귀 고정 — 삭제 후에도 전이·멱등이 이어진다

**Files:**
- Test: `be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java` (신규 2건)

**Interfaces:**
- Consumes: Task 1·2 전체. 프로덕션 코드 변경 없음 — 현재 동작을 테스트로 고정한다.

- [ ] **Step 1: 고정 테스트 2건 추가**

`DuplicateChatMessageException`은 `com.ymc.chat.service`에서 import:

```java
@Test
@DisplayName("삭제된 세션의 진행 중 답변도 종결 전이가 계속된다")
void transitionsSurviveSessionDeletion() {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    ChatStartResult started = chatCommandService.start(
            TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "질문");
    chatCommandService.deleteSession(TEST_USER_ID, paper.getId(), started.sessionId());

    boolean owner = chatMessageTransitions.complete(started.assistantMessageId(), "늦은 답변");

    assertThat(owner).isTrue();
}

@Test
@DisplayName("삭제된 세션의 clientMessageId 재전송은 기존 멱등 응답 그대로다")
void duplicateResendIntoDeletedSessionStaysIdempotent() {
    Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
    UUID clientMessageId = UUID.randomUUID();
    ChatStartResult started = chatCommandService.start(
            TEST_USER_ID, paper.getId(), null, clientMessageId, "질문");
    chatMessageTransitions.complete(started.assistantMessageId(), "답변");
    chatCommandService.deleteSession(TEST_USER_ID, paper.getId(), started.sessionId());

    assertThatThrownBy(() -> chatCommandService.start(
            TEST_USER_ID, paper.getId(), started.sessionId(), clientMessageId, "질문"))
            .isInstanceOf(DuplicateChatMessageException.class);
}
```

첫 테스트가 이 변경의 존재 이유다 — 물리 삭제였다면 `markCompleted`가 0행이라 false. 둘째는 rejectDuplicate가 resolveSession보다 먼저 실행되어 삭제 여부와 무관하게 멱등 판정한다는 현재 동작의 고정이다.

- [ ] **Step 2: 통과 확인 + 전체 테스트**

Run: `cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'`
Expected: PASS. 이어서 전체: `cd be && ./gradlew test`
Expected: PASS — 다른 테스트가 물리 삭제에 의존했다면 여기서 드러난다. 실패 시 해당 테스트를 논리 삭제 기대로 고쳐 이 커밋에 포함.

- [ ] **Step 3: 커밋**

```bash
git add be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java
git commit -m "[YMC-355] test(chat): 삭제 후 전이·멱등 동작 고정"
```

---

### Task 4: PR

- [ ] **Step 1: 푸시 후 PR 생성** (본문 양식: 배경·변경사항·검증)

```bash
git push -u origin YMC-355-chat-soft-delete
gh pr create --title "[YMC-355] 채팅 세션 논리 삭제 전환" --body "## 배경

계약과 FT-011은 세션 논리 삭제 보관을 전제하지만 현재는 물리 삭제다. 물리 삭제는 생성 중 GENERATING row를 지워 YMC-344 사용량 예약이 회수 불가능하게 샌다 — 344의 선행 의존.

## 변경사항

- chat_session에 deleted_at 추가, 삭제는 마킹으로 전환. 메시지 row 보존.
- 삭제된 세션은 목록 제외, 히스토리·start·재삭제 404 (계약 그대로).
- **판단** — 삭제된 세션의 clientMessageId 재전송은 기존 멱등 응답(DUPLICATE_MESSAGE) 유지. rejectDuplicate가 세션 판정보다 먼저라 동작 변화가 없고, 계약 위반도 아니다.
- be/docs/db/chat.sql에 deleted_at 반영 — prod 첫 배포 전 필요.

## 검증

- ChatSessionHistoryIntegrationTest 갱신 2건 + 신규 6건, 전체 ./gradlew test 통과.
- 미검증: 없음 (FE 변경 없음 — 삭제된 세션은 지금도 목록에서 사라진 것으로 보인다)."
```
