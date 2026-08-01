# 채팅 세션 히스토리 조회·삭제 (YMC-260) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증 사용자가 논문별 채팅 세션 목록을 조회하고, 세션의 메시지 히스토리를 seq 순으로 다시 열어보고, 세션(+소속 메시지)을 삭제할 수 있는 계약·BE를 구현한다.

**Architecture:** contract-first — project-docs의 `frontend-backend/openapi.yaml`에 operation 3개를 먼저 추가하고 BE가 따라간다. BE는 기존 `com.ymc.chat` 컨텍스트 안에서 `ChatMessage.seq`·`ChatSession.title/lastMessageAt`을 도입하고, 조회는 신규 `ChatQueryService`, 삭제는 `ChatCommandService.deleteSession`으로 나눈다. FE는 이번 범위가 아니다 (YMC-289 머지 후 별도).

**Tech Stack:** Spring Boot(MVC)·Spring Data JPA·PostgreSQL(Testcontainers)·MockMvc 통합 테스트

**Spec:** [2026-08-01-chat-session-history-design.md](../specs/2026-08-01-chat-session-history-design.md)

## Global Constraints

- 계약이 코드보다 앞선다. 스키마 변경은 `project-docs/contracts/`부터 (Task 1이 반드시 선행).
- 커밋 형식 `[YMC-260] type(scope): subject`. **Co-Authored-By·Generated with 등 attribution 금지.**
- app 작업 브랜치: `YMC-260-chat-session-history` (main에서 분기, 이미 존재). project-docs는 Task 1에서 main으로부터 분기.
- be/CLAUDE.md 규칙: 엔티티는 `@Getter`만, 상태 변경은 의도 드러나는 메서드로. 연관관계 `fetch = LAZY` 명시. 리포지토리는 파생 쿼리 → `@Query` 사다리. 빈 주입은 `@RequiredArgsConstructor`(작업 필요하면 명시적 생성자).
- Spring Data JPA·MockMvc의 낯선 API를 쓰기 전에 context7으로 해당 버전 문서를 확인한다.
- `be/src/main/resources/application.yml`에 무관한 로컬 수정(dirty)이 있다 — **절대 스테이징하지 않는다.**
- 테스트 실행: `cd be && ./gradlew test --tests '<클래스명>'` (Docker 필요 — Testcontainers PG·LocalStack).

---

### Task 1: 계약 — openapi.yaml에 세션 operation 3개 추가 (project-docs repo)

**Files:**
- Modify: `/Users/geunhh/Desktop/team-ymc/project-docs/contracts/frontend-backend/openapi.yaml`

**Interfaces:**
- Produces: `listChatSessions`·`listChatSessionMessages`·`deleteChatSession` operation과 `ChatSessionSummary`·`ChatMessageItem` 스키마 — Task 3·4의 DTO가 이 스키마와 1:1이어야 한다.

- [ ] **Step 1: project-docs에 작업 브랜치 생성**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs
git status --short   # clean이어야 한다. dirty면 사용자에게 확인
git switch -c YMC-260-chat-session-history main
```

- [ ] **Step 2: version 올림 (0.2.3 → 0.2.4)**

`info.version: 0.2.3`을 `0.2.4`로. 호환되는 operation 추가이므로 patch (README 규칙).

- [ ] **Step 3: x-contract-limitations의 해소된 제한 제거**

`/api/papers/{paperId}/chat/messages`의 `x-contract-limitations`에서 아래 한 줄을 삭제한다
(이번에 실제 operation이 생기므로):

```yaml
        - session message 조회 API는 별도 OpenAPI operation으로 정의해야 한다.
```

- [ ] **Step 4: paths에 세션 operation 추가**

`/api/papers/{paperId}/chat/messages` path 블록 끝(‵components:‵ 직전)에 추가:

```yaml
  /api/papers/{paperId}/chat/sessions:
    get:
      operationId: listChatSessions
      summary: 논문의 채팅 세션 목록 조회
      description: |
        인증된 사용자가 소유한 논문의 채팅 세션을 lastMessageAt 내림차순으로 반환한다.
        페이지네이션은 없다 (MVP — paper당 세션 수가 작다). 세션은 첫 질문과 같은
        트랜잭션으로 생성되므로 메시지 없는 세션은 정상 경로에서 존재하지 않는다.
        논문 파싱 상태와 무관하게 조회할 수 있다 — 재처리 중에도 과거 대화는 보인다.
      tags: [chat]
      parameters:
        - name: paperId
          in: path
          required: true
          schema:
            type: string
            format: uuid
          description: 조회 대상 논문의 BE 식별자. BE가 소유권을 검증한다.
      responses:
        "200":
          description: 세션 목록 (lastMessageAt 내림차순, 빈 배열 가능)
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: "#/components/schemas/ChatSessionSummary"
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "403":
          description: "논문 접근 권한 없음. code: FORBIDDEN"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "404":
          description: "paperId 없음. code: PAPER_NOT_FOUND"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }

  /api/papers/{paperId}/chat/sessions/{sessionId}/messages:
    get:
      operationId: listChatSessionMessages
      summary: 세션의 메시지 히스토리 조회
      description: |
        세션의 message를 seq 오름차순으로 반환한다. assistant message의 저장 상태
        (GENERATING·COMPLETED·FAILED)를 그대로 보존한다 — 재방문 시 FE가 상태를 복원한다.
        페이지네이션은 없다 (MVP).
      tags: [chat]
      parameters:
        - name: paperId
          in: path
          required: true
          schema:
            type: string
            format: uuid
          description: 세션이 속한 논문의 BE 식별자. BE가 소유권을 검증한다.
        - name: sessionId
          in: path
          required: true
          schema:
            type: string
            format: uuid
          description: 조회할 채팅 세션 id.
      responses:
        "200":
          description: 메시지 목록 (seq 오름차순)
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: "#/components/schemas/ChatMessageItem"
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "403":
          description: "논문 접근 권한 없음. code: FORBIDDEN"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "404":
          description: |
            paperId가 없으면 PAPER_NOT_FOUND. sessionId가 없거나 현재 사용자·paperId에
            속하지 않으면 CHAT_SESSION_NOT_FOUND.
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }

  /api/papers/{paperId}/chat/sessions/{sessionId}:
    delete:
      operationId: deleteChatSession
      summary: 채팅 세션 삭제 (소속 메시지 포함)
      description: |
        세션과 소속 message를 함께 삭제한다. GENERATING인 assistant가 있어도 삭제한다 —
        진행 중이던 스트림의 저장 시도는 무해하게 무시된다 (조건부 UPDATE 0행).
        삭제는 되돌릴 수 없다.
      tags: [chat]
      parameters:
        - name: paperId
          in: path
          required: true
          schema:
            type: string
            format: uuid
          description: 세션이 속한 논문의 BE 식별자. BE가 소유권을 검증한다.
        - name: sessionId
          in: path
          required: true
          schema:
            type: string
            format: uuid
          description: 삭제할 채팅 세션 id.
      responses:
        "204":
          description: 세션·소속 메시지 삭제 완료
        "401":
          description: "인증 필요. code: UNAUTHORIZED"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "403":
          description: "논문 접근 권한 없음. code: FORBIDDEN"
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
        "404":
          description: |
            paperId가 없으면 PAPER_NOT_FOUND. sessionId가 없거나 현재 사용자·paperId에
            속하지 않으면 CHAT_SESSION_NOT_FOUND.
          content:
            application/json:
              schema: { $ref: "#/components/schemas/Error" }
```

- [ ] **Step 5: components.schemas에 스키마 2개 추가**

`ChatMessageStatus:` 스키마 정의 앞에 추가 (chat 스키마들이 모여 있는 곳):

```yaml
    ChatSessionSummary:
      type: object
      required: [sessionId, title, lastMessageAt, createdAt]
      properties:
        sessionId:
          type: string
          format: uuid
        title:
          type: string
          maxLength: 120
          description: 첫 user 질문의 앞 120자 (코드포인트 기준). 생성 시 1회 저장되며 불변.
        lastMessageAt:
          type: string
          format: date-time
          description: 마지막 message 저장 시각. 목록 정렬 키.
        createdAt:
          type: string
          format: date-time

    ChatMessageItem:
      type: object
      required: [messageId, role, content, status, seq, createdAt]
      properties:
        messageId:
          type: string
          format: uuid
        role:
          type: string
          enum: [USER, ASSISTANT]
        content:
          type: [string, "null"]
          description: GENERATING·FAILED assistant는 null이다 (partial content는 저장하지 않는다).
        status:
          $ref: "#/components/schemas/ChatMessageStatus"
        seq:
          type: integer
          description: 세션 내 단조 증가 순번. 히스토리 정렬 키. user·assistant 쌍은 연속 값을 갖는다.
        createdAt:
          type: string
          format: date-time
```

- [ ] **Step 6: YAML 파싱 검증**

```bash
python3 -c "import yaml; d=yaml.safe_load(open('contracts/frontend-backend/openapi.yaml')); print(d['info']['version'], len(d['paths']))"
```

Expected: `0.2.4 11` (paths 8 → 11)

- [ ] **Step 7: 커밋**

```bash
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-260] docs(contracts): 채팅 세션 목록·히스토리·삭제 operation 추가"
```

---

### Task 2: BE — ChatMessage.seq · ChatSession.title/lastMessageAt

**Files:**
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatSession.java`
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatMessage.java`
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatMessageRepository.java`
- Modify: `be/src/main/java/com/ymc/chat/service/ChatCommandService.java` (start 경로)
- Test: `be/src/test/java/com/ymc/chat/service/ChatCommandServiceTest.java` (기존 클래스에 추가)

**Interfaces:**
- Consumes: 없음 (Task 1의 계약 필드명과 일치해야 함)
- Produces:
  - `ChatSession.open(UUID ownerId, UUID paperId, String firstQuestion, Instant now)` — title 자동 절단 저장
  - `ChatSession.recordActivity(Instant now)` / `getTitle()` / `getLastMessageAt()`
  - `ChatMessage.userMessage(ChatSession, UUID clientMessageId, String content, int seq, Instant now)`
  - `ChatMessage.assistantGenerating(ChatSession, UUID clientMessageId, int seq, Instant now)` / `getSeq()`
  - `ChatMessageRepository.findMaxSeqBySessionId(UUID): Optional<Integer>`

- [ ] **Step 1: 실패하는 테스트 추가**

`ChatCommandServiceTest`에 추가 (기존 헬퍼 `givenCompletedPaper()`·`chatMessageTransitions` 재사용):

```java
@Test
@DisplayName("연속 두 질문이면 seq가 1,2,3,4로 단조 증가한다")
void seqIncreasesMonotonically() {
    Paper paper = givenCompletedPaper();

    ChatStartResult first = chatCommandService.start(
            TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "첫 질문");
    chatMessageTransitions.complete(first.assistantMessageId(), "답1");

    chatCommandService.start(
            TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "둘째 질문");

    var seqs = chatMessageRepository.findAll().stream()
            .sorted(java.util.Comparator.comparingInt(ChatMessage::getSeq))
            .map(m -> m.getSeq() + ":" + m.getRole())
            .toList();
    assertThat(seqs).containsExactly(
            "1:USER", "2:ASSISTANT", "3:USER", "4:ASSISTANT");
}

@Test
@DisplayName("새 세션은 첫 질문 앞 120자(코드포인트)를 title로 저장하고 lastMessageAt을 기록한다")
void newSessionStoresTitleAndActivity() {
    Paper paper = givenCompletedPaper();
    String longQuestion = "가".repeat(150);

    ChatStartResult result = chatCommandService.start(
            TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), longQuestion);

    ChatSession session = chatSessionRepository.findById(result.sessionId()).orElseThrow();
    assertThat(session.getTitle()).isEqualTo("가".repeat(120));
    assertThat(session.getLastMessageAt()).isNotNull();
}

@Test
@DisplayName("기존 세션에 질문을 이어가면 lastMessageAt이 갱신된다")
void followUpUpdatesLastMessageAt() {
    Paper paper = givenCompletedPaper();
    ChatStartResult first = chatCommandService.start(
            TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "첫 질문");
    Instant firstActivity = chatSessionRepository.findById(first.sessionId())
            .orElseThrow().getLastMessageAt();
    chatMessageTransitions.complete(first.assistantMessageId(), "답");

    chatCommandService.start(
            TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "둘째 질문");

    assertThat(chatSessionRepository.findById(first.sessionId()).orElseThrow()
            .getLastMessageAt()).isAfter(firstActivity);
}
```

필요 import: `com.ymc.chat.service.ChatMessageTransitions`가 이미 `@Autowired` 필드로 있다. `Instant`·`Comparator`는 기존 import 확인 후 추가.

- [ ] **Step 2: 컴파일 실패 확인**

```bash
cd be && ./gradlew compileTestJava
```

Expected: FAIL — `getSeq()`, `getTitle()`, `getLastMessageAt()` 심볼 없음

- [ ] **Step 3: ChatSession에 title·lastMessageAt 추가**

기존 필드 아래에 추가하고 `open`을 확장한다. 클래스 javadoc에 "title·lastMessageAt은 목록
조회용 비정규화(YMC-260 설계 §2)" 한 줄을 덧붙인다:

```java
    /** 목록 표시용 — 첫 user 질문의 앞 120자 (코드포인트 기준). 생성 시 1회 저장, 불변. */
    @Column(name = "title", nullable = false, length = 120, updatable = false)
    private String title;

    /** 마지막 메시지 저장 시각 — 목록 정렬 키. start 트랜잭션(세션 행 잠금 상태)에서 갱신된다. */
    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;
```

```java
    private static final int TITLE_MAX_CODE_POINTS = 120;

    private ChatSession(UUID ownerId, UUID paperId, String firstQuestion, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.paperId = paperId;
        this.title = truncateTitle(firstQuestion);
        this.createdAt = now;
        this.lastMessageAt = now;
    }

    /** 새 세션. 첫 질문에서 sessionId 없이 요청이 오면 만든다. */
    public static ChatSession open(UUID ownerId, UUID paperId, String firstQuestion, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(firstQuestion, "firstQuestion");
        Objects.requireNonNull(now, "now");
        return new ChatSession(ownerId, paperId, firstQuestion, now);
    }

    /** 메시지 쌍 저장 시 호출 — 목록 정렬 키 갱신. */
    public void recordActivity(Instant now) {
        this.lastMessageAt = now;
    }

    /** surrogate pair를 반 자르지 않도록 코드포인트 기준으로 절단한다. */
    private static String truncateTitle(String firstQuestion) {
        if (firstQuestion.codePointCount(0, firstQuestion.length()) <= TITLE_MAX_CODE_POINTS) {
            return firstQuestion;
        }
        return firstQuestion.substring(
                0, firstQuestion.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS));
    }
```

- [ ] **Step 4: ChatMessage에 seq 추가 + 인덱스 교체**

`@Table`의 인덱스를 정렬 키와 일치하도록 교체:

```java
        indexes = @Index(
                name = "ix_chat_message_session_seq",
                columnList = "session_id, seq"))
```

필드·생성자·팩토리:

```java
    /** 세션 내 단조 증가 순번 — 히스토리 정렬 키. 세션 행 잠금 하에서 부여된다 (YMC-260 설계 §2). */
    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;
```

```java
    private ChatMessage(ChatSession session, ChatMessageRole role, String content,
            ChatMessageStatus status, UUID clientMessageId, int seq, Instant now) {
        this.id = UUID.randomUUID();
        this.session = session;
        this.role = role;
        this.content = content;
        this.status = status;
        this.clientMessageId = clientMessageId;
        this.seq = seq;
        this.createdAt = now;
        this.completedAt = status == ChatMessageStatus.COMPLETED ? now : null;
    }

    /** 사용자 질문. 저장 즉시 COMPLETED다. */
    public static ChatMessage userMessage(
            ChatSession session, UUID clientMessageId, String content, int seq, Instant now) {
        Objects.requireNonNull(content, "content");
        return new ChatMessage(session, ChatMessageRole.USER, content,
                ChatMessageStatus.COMPLETED, clientMessageId, seq, now);
    }

    /** 생성 중인 assistant 답변 자리. content는 완료 시 조건부 UPDATE로 채운다. */
    public static ChatMessage assistantGenerating(
            ChatSession session, UUID clientMessageId, int seq, Instant now) {
        return new ChatMessage(session, ChatMessageRole.ASSISTANT, null,
                ChatMessageStatus.GENERATING, clientMessageId, seq, now);
    }
```

주의: private 생성자 시그니처는 `(session, role, content, status, clientMessageId, seq, now)`
하나다. 호출부 둘 다 이 순서대로 (clientMessageId가 seq보다 앞).

- [ ] **Step 5: ChatMessageRepository에 max(seq) 조회 추가**

```java
    /** 세션의 현재 최대 seq. start 트랜잭션이 세션 행을 잠근 상태에서만 호출한다 — 경쟁 없음. */
    @Query("select max(m.seq) from ChatMessage m where m.session.id = :sessionId")
    Optional<Integer> findMaxSeqBySessionId(UUID sessionId);
```

- [ ] **Step 6: ChatCommandService.start에 seq·title·activity 배선**

`resolveSession`이 title 재료(content)를 받도록 바꾸고, 저장 직전에 seq를 부여한다.
`start()`의 저장 블록을 다음으로 교체:

```java
        ChatSession session = resolveSession(ownerId, paperId, sessionIdOrNull, content);

        if (chatMessageRepository.existsBySessionIdAndStatus(
                session.getId(), ChatMessageStatus.GENERATING)) {
            throw new ApiException(ErrorCode.CHAT_RUN_IN_PROGRESS, "이미 답변을 생성하고 있습니다.");
        }

        Instant now = Instant.now();
        int userSeq = chatMessageRepository.findMaxSeqBySessionId(session.getId()).orElse(0) + 1;
        session.recordActivity(now);
        ChatMessage assistant;
        try {
            chatMessageRepository.save(
                    ChatMessage.userMessage(session, clientMessageId, content, userSeq, now));
            assistant = chatMessageRepository.saveAndFlush(
                    ChatMessage.assistantGenerating(session, clientMessageId, userSeq + 1, now));
        } catch (DataIntegrityViolationException e) {
```

(catch 블록 이하는 그대로.) `resolveSession`은:

```java
    private ChatSession resolveSession(
            UUID ownerId, UUID paperId, UUID sessionIdOrNull, String content) {
        if (sessionIdOrNull == null) {
            return chatSessionRepository.save(
                    ChatSession.open(ownerId, paperId, content, Instant.now()));
        }
        // ... (이하 기존 그대로)
```

- [ ] **Step 7: 바뀐 시그니처의 다른 호출부 확인**

```bash
grep -rn "ChatSession.open(\|userMessage(\|assistantGenerating(" be/src --include='*.java' | grep -v "chat/domain/"
```

`ChatCommandService` 외 호출부(테스트 포함)가 나오면 새 시그니처로 맞춘다. 새 인자는
의미에 맞게 (seq는 테스트 픽스처면 1·2, firstQuestion은 해당 테스트의 질문 문자열).

- [ ] **Step 8: 로컬 DB 리셋 후 테스트 실행**

`seq`·`title`이 not null이라 기존 dev 데이터가 있으면 `ddl-auto: update`가 실패할 수 있다.
Testcontainers는 매번 새 DB라 무관하지만, **로컬 개발 DB는 리셋이 필요하다** — 사용자에게
`infra/local`의 compose volume 리셋을 안내만 하고 (임의로 지우지 않는다) 테스트를 돌린다:

```bash
cd be && ./gradlew test --tests 'ChatCommandServiceTest' --tests 'ChatMessageTransitionsTest'
```

Expected: 전부 PASS (신규 3개 포함)

- [ ] **Step 9: 채팅 전체 회귀**

```bash
cd be && ./gradlew test --tests 'com.ymc.chat.*'
```

Expected: PASS

- [ ] **Step 10: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/chat be/src/test/java/com/ymc/chat
git commit -m "[YMC-260] feat(be): 채팅 세션 seq·title·last_message_at 도입"
```

---

### Task 3: BE — 세션 목록·메시지 히스토리 조회 API

**Files:**
- Modify: `be/src/main/java/com/ymc/paper/service/PaperChatAccessValidator.java`
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatSessionRepository.java`
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatMessageRepository.java`
- Create: `be/src/main/java/com/ymc/chat/service/ChatQueryService.java`
- Create: `be/src/main/java/com/ymc/chat/api/dto/ChatSessionSummaryResponse.java`
- Create: `be/src/main/java/com/ymc/chat/api/dto/ChatMessageItemResponse.java`
- Modify: `be/src/main/java/com/ymc/chat/api/ChatController.java`
- Test: `be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java` (신규)

**Interfaces:**
- Consumes: Task 2의 `getSeq()`·`getTitle()`·`getLastMessageAt()`, `ChatMessageTransitions.markCompleted(UUID, String, Instant)`
- Produces:
  - `PaperChatAccessValidator.validateOwned(UUID paperId, UUID ownerId)` — Task 4도 사용
  - `ChatQueryService.listSessions(UUID ownerId, UUID paperId): List<ChatSession>`
  - `ChatQueryService.listMessages(UUID ownerId, UUID paperId, UUID sessionId): List<ChatMessage>`
  - `GET /api/papers/{paperId}/chat/sessions`, `GET /api/papers/{paperId}/chat/sessions/{sessionId}/messages`

- [ ] **Step 1: 실패하는 통합 테스트 작성**

`be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java` 생성:

```java
// test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java
package com.ymc.chat.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** 세션 목록·메시지 히스토리·삭제 (YMC-260). 계약 operation listChatSessions 외 2개. */
class ChatSessionHistoryIntegrationTest extends IntegrationTest {

    static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    RequestPostProcessor otherUserJwt() {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(j -> j.subject(OTHER_USER_ID.toString()));
    }

    Paper givenCompletedPaper(UUID ownerId, String filename) {
        Paper paper = paperRepository.save(Paper.register(ownerId, filename, Instant.now()));
        paperTransitions.markUploaded(paper.getId());
        paperTransitions.markProcessing(paper.getId());
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    /** 질문 1건을 보내고 답변을 완료시켜 세션을 만든다. */
    ChatStartResult givenCompletedExchange(UUID ownerId, Paper paper, UUID sessionIdOrNull,
            String question) {
        ChatStartResult started = chatCommandService.start(
                ownerId, paper.getId(), sessionIdOrNull, UUID.randomUUID(), question);
        chatMessageTransitions.complete(started.assistantMessageId(), "답변");
        return started;
    }

    @Test
    @DisplayName("세션 목록 — 내 세션만, lastMessageAt 내림차순, title 포함")
    void listSessionsOrderedByActivity() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
        ChatStartResult older = givenCompletedExchange(TEST_USER_ID, paper, null, "첫 세션 질문");
        ChatStartResult newer = givenCompletedExchange(TEST_USER_ID, paper, null, "둘째 세션 질문");
        // older 세션에 후속 질문 — older가 최신 활동이 된다
        givenCompletedExchange(TEST_USER_ID, paper, older.sessionId(), "후속 질문");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", paper.getId())
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionId").value(older.sessionId().toString()))
                .andExpect(jsonPath("$[0].title").value("첫 세션 질문"))
                .andExpect(jsonPath("$[1].sessionId").value(newer.sessionId().toString()))
                .andExpect(jsonPath("$[0].lastMessageAt").exists())
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    @DisplayName("메시지 히스토리 — seq 오름차순, status·content 보존 (GENERATING은 content null)")
    void listMessagesPreservesOrderAndStatus() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
        ChatStartResult first = givenCompletedExchange(TEST_USER_ID, paper, null, "질문1");
        // 후속 질문은 완료시키지 않는다 — GENERATING 상태 보존 검증
        chatCommandService.start(
                TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "질문2");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                        paper.getId(), first.sessionId())
                        .with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].role").value("USER"))
                .andExpect(jsonPath("$[0].content").value("질문1"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[1].seq").value(2))
                .andExpect(jsonPath("$[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[1].content").value("답변"))
                .andExpect(jsonPath("$[3].seq").value(4))
                .andExpect(jsonPath("$[3].status").value("GENERATING"))
                .andExpect(jsonPath("$[3].content").value(nullValue()));
    }

    @Test
    @DisplayName("타인 논문의 목록 조회는 403 FORBIDDEN")
    void listSessionsOfOthersPaperForbidden() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "mine.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", paper.getId())
                        .with(otherUserJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("없는 논문의 목록 조회는 404 PAPER_NOT_FOUND")
    void listSessionsOfMissingPaperNotFound() throws Exception {
        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions", UUID.randomUUID())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("내 논문이라도 다른 논문의 세션 히스토리는 404 CHAT_SESSION_NOT_FOUND")
    void listMessagesOfSessionFromAnotherPaperNotFound() throws Exception {
        Paper paperA = givenCompletedPaper(TEST_USER_ID, "a.pdf");
        Paper paperB = givenCompletedPaper(TEST_USER_ID, "b.pdf");
        ChatStartResult sessionOnB = givenCompletedExchange(TEST_USER_ID, paperB, null, "질문");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                        paperA.getId(), sessionOnB.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("타인 세션의 히스토리는 404 CHAT_SESSION_NOT_FOUND (존재를 숨긴다)")
    void listMessagesOfOthersSessionNotFound() throws Exception {
        Paper othersPaper = givenCompletedPaper(OTHER_USER_ID, "others.pdf");
        ChatStartResult othersSession =
                givenCompletedExchange(OTHER_USER_ID, othersPaper, null, "남의 질문");
        Paper myPaper = givenCompletedPaper(TEST_USER_ID, "mine.pdf");

        mockMvc.perform(get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                        myPaper.getId(), othersSession.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }
}
```

주의: `IntegrationTest.resetState()`가 매 테스트 전 전체 테이블을 비우므로 픽스처 간섭은 없다.
`givenCompletedPaper(ownerId, ...)`는 베이스의 `givenPendingPaper`(TEST_USER 고정)와 달리
owner를 받는 로컬 헬퍼다.

- [ ] **Step 2: 컴파일·실패 확인**

```bash
cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'
```

Expected: FAIL — 404 (endpoint 없음) 또는 컴파일 오류 없이 모든 테스트 실패

- [ ] **Step 3: PaperChatAccessValidator에 validateOwned 추가**

기존 `validateChatReady`와 중복되는 조회·소유 검증을 private으로 추출:

```java
    /**
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     * @throws ApiException PAPER_NOT_READY(409) — 파싱 완료 상태가 아님
     */
    @Transactional(readOnly = true)
    public void validateChatReady(UUID paperId, UUID ownerId) {
        Paper paper = getOwned(paperId, ownerId);
        if (paper.getStatus() != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 학습 가능한 상태가 아닙니다: " + paper.getStatus());
        }
    }

    /**
     * 소유만 검증한다 — 세션 히스토리 조회·삭제는 논문 파싱 상태와 무관하다 (YMC-260 설계 §3).
     *
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     */
    @Transactional(readOnly = true)
    public void validateOwned(UUID paperId, UUID ownerId) {
        getOwned(paperId, ownerId);
    }

    private Paper getOwned(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        return paper;
    }
```

- [ ] **Step 4: 리포지토리 파생 쿼리 추가**

`ChatSessionRepository`:

```java
    /** 목록 조회 (계약 listChatSessions). 정렬 키는 비정규화된 lastMessageAt. */
    List<ChatSession> findAllByOwnerIdAndPaperIdOrderByLastMessageAtDesc(UUID ownerId, UUID paperId);
```

`ChatMessageRepository`:

```java
    /** 히스토리 조회 (계약 listChatSessionMessages). 정렬 키는 seq — ix_chat_message_session_seq. */
    List<ChatMessage> findAllBySessionIdOrderBySeqAsc(UUID sessionId);
```

- [ ] **Step 5: ChatQueryService 생성**

```java
// chat/service/ChatQueryService.java
package com.ymc.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.service.PaperChatAccessValidator;

import lombok.RequiredArgsConstructor;

/**
 * 세션 히스토리 읽기 경로 (YMC-260). 쓰기 경로(ChatCommandService)와 분리 — 잠금 없이
 * 읽기 전용 트랜잭션으로 처리한다.
 *
 * <p>세션 검증 규칙은 ChatCommandService.resolveSession과 동일: 없거나 소유·논문이
 * 다르면 존재를 숨기고 CHAT_SESSION_NOT_FOUND(404).
 */
@Service
@RequiredArgsConstructor
public class ChatQueryService {

    private final PaperChatAccessValidator paperChatAccessValidator;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    /** 논문 파싱 상태와 무관하게 조회한다 — validateChatReady가 아니라 validateOwned. */
    @Transactional(readOnly = true)
    public List<ChatSession> listSessions(UUID ownerId, UUID paperId) {
        paperChatAccessValidator.validateOwned(paperId, ownerId);
        return chatSessionRepository
                .findAllByOwnerIdAndPaperIdOrderByLastMessageAtDesc(ownerId, paperId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(UUID ownerId, UUID paperId, UUID sessionId) {
        paperChatAccessValidator.validateOwned(paperId, ownerId);
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(ChatQueryService::sessionNotFound);
        if (!session.getOwnerId().equals(ownerId) || !session.getPaperId().equals(paperId)) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션도 404 (계약)
        }
        return chatMessageRepository.findAllBySessionIdOrderBySeqAsc(sessionId);
    }

    private static ApiException sessionNotFound() {
        return new ApiException(ErrorCode.CHAT_SESSION_NOT_FOUND,
                "세션이 없거나 이 논문의 세션이 아닙니다.");
    }
}
```

- [ ] **Step 6: 응답 DTO 2개 생성 (계약 스키마 1:1)**

```java
// chat/api/dto/ChatSessionSummaryResponse.java
package com.ymc.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.ymc.chat.domain.ChatSession;

/** 계약 ChatSessionSummary. */
public record ChatSessionSummaryResponse(
        UUID sessionId, String title, Instant lastMessageAt, Instant createdAt) {

    public static ChatSessionSummaryResponse from(ChatSession session) {
        return new ChatSessionSummaryResponse(
                session.getId(), session.getTitle(),
                session.getLastMessageAt(), session.getCreatedAt());
    }
}
```

```java
// chat/api/dto/ChatMessageItemResponse.java
package com.ymc.chat.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;

/** 계약 ChatMessageItem. content는 GENERATING·FAILED assistant에서 null이다. */
public record ChatMessageItemResponse(
        UUID messageId, ChatMessageRole role, String content,
        ChatMessageStatus status, int seq, Instant createdAt) {

    public static ChatMessageItemResponse from(ChatMessage message) {
        return new ChatMessageItemResponse(
                message.getId(), message.getRole(), message.getContent(),
                message.getStatus(), message.getSeq(), message.getCreatedAt());
    }
}
```

- [ ] **Step 7: ChatController에 GET 2개 추가**

`ChatQueryService` 필드 주입 추가 후:

```java
    /** 계약 listChatSessions — 세션 목록, lastMessageAt 내림차순. */
    @GetMapping("/sessions")
    public List<ChatSessionSummaryResponse> listSessions(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID paperId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return chatQueryService.listSessions(ownerId, paperId).stream()
                .map(ChatSessionSummaryResponse::from)
                .toList();
    }

    /** 계약 listChatSessionMessages — 메시지 히스토리, seq 오름차순. */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageItemResponse> listMessages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paperId,
            @PathVariable UUID sessionId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return chatQueryService.listMessages(ownerId, paperId, sessionId).stream()
                .map(ChatMessageItemResponse::from)
                .toList();
    }
```

import 추가: `java.util.List`, `org.springframework.web.bind.annotation.GetMapping`,
`com.ymc.chat.api.dto.ChatSessionSummaryResponse`, `com.ymc.chat.api.dto.ChatMessageItemResponse`,
`com.ymc.chat.service.ChatQueryService`. 클래스 javadoc의 경로 나열도 갱신한다.

- [ ] **Step 8: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'
```

Expected: 삭제 테스트가 아직 없으므로 이 클래스의 6개 전부 PASS

- [ ] **Step 9: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/chat be/src/main/java/com/ymc/paper/service/PaperChatAccessValidator.java be/src/test/java/com/ymc/chat
git commit -m "[YMC-260] feat(be): 채팅 세션 목록·메시지 히스토리 조회 API"
```

---

### Task 4: BE — 세션 삭제 API (소속 메시지 동반 삭제)

**Files:**
- Modify: `be/src/main/java/com/ymc/chat/domain/ChatMessageRepository.java`
- Modify: `be/src/main/java/com/ymc/chat/service/ChatCommandService.java`
- Modify: `be/src/main/java/com/ymc/chat/api/ChatController.java`
- Test: `be/src/test/java/com/ymc/chat/api/ChatSessionHistoryIntegrationTest.java` (추가)

**Interfaces:**
- Consumes: Task 3의 `validateOwned`, 테스트 헬퍼 `givenCompletedPaper`/`givenCompletedExchange`/`otherUserJwt`
- Produces: `ChatCommandService.deleteSession(UUID ownerId, UUID paperId, UUID sessionId)`, `DELETE /api/papers/{paperId}/chat/sessions/{sessionId}` → 204

- [ ] **Step 1: 실패하는 테스트 추가**

`ChatSessionHistoryIntegrationTest`에 추가 (import에 `delete` static — 기존 `get` 옆에
`org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete` 추가,
`org.assertj.core.api.Assertions.assertThat`도):

```java
    @Test
    @DisplayName("세션 삭제 — 204 후 세션·소속 메시지가 모두 사라지고 다른 세션은 남는다")
    void deleteSessionCascadesMessages() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
        ChatStartResult target = givenCompletedExchange(TEST_USER_ID, paper, null, "지울 세션");
        ChatStartResult keep = givenCompletedExchange(TEST_USER_ID, paper, null, "남길 세션");

        mockMvc.perform(delete("/api/papers/{paperId}/chat/sessions/{sessionId}",
                        paper.getId(), target.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNoContent());

        assertThat(chatSessionRepository.findById(target.sessionId())).isEmpty();
        assertThat(chatMessageRepository.findAll())
                .allMatch(m -> m.getSession().getId().equals(keep.sessionId()));
        assertThat(chatSessionRepository.findById(keep.sessionId())).isPresent();
    }

    @Test
    @DisplayName("GENERATING assistant가 있어도 삭제된다")
    void deleteSessionWhileGenerating() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "history.pdf");
        ChatStartResult started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "질문");
        // markCompleted 하지 않음 — assistant는 GENERATING인 채다

        mockMvc.perform(delete("/api/papers/{paperId}/chat/sessions/{sessionId}",
                        paper.getId(), started.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNoContent());

        assertThat(chatSessionRepository.count()).isZero();
        assertThat(chatMessageRepository.count()).isZero();
    }

    @Test
    @DisplayName("타인 세션 삭제는 404 CHAT_SESSION_NOT_FOUND — 데이터는 남는다")
    void deleteOthersSessionNotFound() throws Exception {
        Paper othersPaper = givenCompletedPaper(OTHER_USER_ID, "others.pdf");
        ChatStartResult othersSession =
                givenCompletedExchange(OTHER_USER_ID, othersPaper, null, "남의 질문");
        Paper myPaper = givenCompletedPaper(TEST_USER_ID, "mine.pdf");

        mockMvc.perform(delete("/api/papers/{paperId}/chat/sessions/{sessionId}",
                        myPaper.getId(), othersSession.sessionId())
                        .with(userJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));

        assertThat(chatSessionRepository.findById(othersSession.sessionId())).isPresent();
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }
```

주의: `deleteSessionCascadesMessages`의 `m.getSession()`은 LAZY 프록시 접근이다 — 테스트
스레드는 트랜잭션이 없으므로 `getSession().getId()`까지만 안전하다(FK 값은 프록시 초기화
없이 읽힌다). 그 이상 접근하는 assert를 추가하지 말 것.

- [ ] **Step 2: 실패 확인**

```bash
cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'
```

Expected: 신규 3개 FAIL (405 Method Not Allowed 등), 기존 6개 PASS

- [ ] **Step 3: ChatMessageRepository에 bulk delete 추가**

```java
    /** 세션 삭제 시 소속 메시지 일괄 삭제 (YMC-260). 영속성 컨텍스트를 거치지 않는 bulk다. */
    @Modifying(clearAutomatically = true)
    @Query("delete from ChatMessage m where m.session.id = :sessionId")
    int deleteBySessionId(UUID sessionId);
```

- [ ] **Step 4: ChatCommandService.deleteSession 추가**

`sessionNotFound()` 헬퍼를 재사용한다:

```java
    /**
     * 세션과 소속 메시지를 삭제한다 (설계 §1·§3). GENERATING 중이어도 삭제한다 — 진행 중이던
     * relay의 조건부 UPDATE(markCompleted/markFailed)는 0행으로 끝나며 무해하다.
     *
     * <p>{@code findWithLockById}로 start와 직렬화한다 — 잠금 없이 bulk delete와 start의
     * 메시지 insert가 교차하면 삭제된 세션을 참조하는 insert가 FK 위반으로 5xx가 된다.
     *
     * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN — 논문 검증 실패
     * @throws ApiException CHAT_SESSION_NOT_FOUND — 세션 없음·소유/논문 불일치
     */
    @Transactional
    public void deleteSession(UUID ownerId, UUID paperId, UUID sessionId) {
        paperChatAccessValidator.validateOwned(paperId, ownerId);
        ChatSession session = chatSessionRepository.findWithLockById(sessionId)
                .orElseThrow(this::sessionNotFound);
        if (!session.getOwnerId().equals(ownerId) || !session.getPaperId().equals(paperId)) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션도 404 (계약)
        }
        chatMessageRepository.deleteBySessionId(sessionId);
        chatSessionRepository.delete(session);
    }
```

- [ ] **Step 5: ChatController에 DELETE 추가**

```java
    /** 계약 deleteChatSession — 세션·소속 메시지 삭제. */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paperId,
            @PathVariable UUID sessionId) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        chatCommandService.deleteSession(ownerId, paperId, sessionId);
        return ResponseEntity.noContent().build();
    }
```

import 추가: `org.springframework.web.bind.annotation.DeleteMapping`.

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests 'ChatSessionHistoryIntegrationTest'
```

Expected: 9개 전부 PASS

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app
git add be/src/main/java/com/ymc/chat be/src/test/java/com/ymc/chat
git commit -m "[YMC-260] feat(be): 채팅 세션 삭제 API — 소속 메시지 동반 삭제"
```

---

### Task 5: 전체 검증·마무리

**Files:**
- 없음 (검증만)

- [ ] **Step 1: BE 전체 테스트**

```bash
cd be && ./gradlew test
```

Expected: 전체 PASS. 실패하면 원인을 고치고 (테스트를 약화시키지 말 것) 해당 Task의
커밋에 fixup하거나 별도 fix 커밋.

- [ ] **Step 2: AC 대조**

Jira YMC-260 AC 중 이번 범위(BE) 항목을 하나씩 테스트와 대조한다:

| AC | 검증 |
|---|---|
| 목록이 최근 활동 순·내 세션만 | `listSessionsOrderedByActivity` |
| 히스토리가 seq 순서·상태 보존 | `listMessagesPreservesOrderAndStatus` |
| 삭제가 세션+메시지 동반 삭제 | `deleteSessionCascadesMessages` |
| 타인 세션 조회·삭제 거부 | `listMessagesOfOthersSessionNotFound`·`deleteOthersSessionNotFound`·`listSessionsOfOthersPaperForbidden` |
| 조회·권한·cascade 테스트 존재 | 위 전부 |

FE 항목 2개(재방문 로드, 삭제 후 화면 갱신)는 이번 범위 아님 — Jira에 코멘트로 남긴다.

- [ ] **Step 3: 사용자 보고**

핵심 diff 요약을 사용자에게 보여주고 (메모리 규칙: 머지/PR 전 변경사항 먼저 보여주기),
project-docs·app 두 repo의 PR 생성 여부를 확인받는다. 로컬 dev DB 리셋 필요성
(`infra/local` compose volume — seq·title not null 추가 때문)도 함께 안내한다.
