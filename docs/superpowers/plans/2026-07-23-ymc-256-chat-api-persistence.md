# YMC-256 채팅 요청 API·대화 영속화 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE가 `POST /api/papers/{paperId}/chat/messages`로 질문하면 인증·권한·멱등을 검증하고 user/assistant 메시지를 저장한 뒤 fake AI 스트림으로 SSE 응답(`message.started → delta → completed`)을 완주하는 BE 수직 슬라이스.

**Architecture:** `com.ymc.chat` 컨텍스트 신설(`api/service/service/port/domain/infra/ai`). AI 호출은 `AiAgentStreamPort` 뒤에 격리하고 이번 티켓은 fake 구현만 붙인다(YMC-257이 WebClient 구현으로 교체). 트랜잭션 서비스(`ChatCommandService`)가 commit한 뒤 relay(`ChatStreamService`)가 SseEmitter로 스트리밍한다. `GENERATING → COMPLETED/FAILED` 전이는 조건부 UPDATE, 세션당 동시 실행 1개는 세션 행 비관적 잠금으로 보장한다.

**Tech Stack:** Spring Boot 3.5 MVC / Java 21 / JPA(PostgreSQL, ddl-auto) / SseEmitter / Testcontainers + MockMvc

**설계 SSOT:** `docs/superpowers/specs/2026-07-23-chat-sse-streaming-design.md` §3
**계약 SSOT:** `project-docs/contracts/frontend-backend/openapi.yaml` (`/api/papers/{paperId}/chat/messages`)

## Global Constraints

- 계약이 코드보다 앞선다 — 계약에 없는 필드·코드를 짓지 않는다. 새 에러 코드는 openapi.yaml PR 먼저 (`ErrorCode` javadoc 규칙).
- 컨텍스트 간 참조는 ID로만. 컨텍스트 간 `@ManyToOne` 금지, 데이터 조립은 서비스 호출 (be/CLAUDE.md).
- 엔티티는 `@Getter`만. 생성은 정적 팩토리, 전이는 의도가 드러나는 메서드 또는 리포지토리 조건부 UPDATE.
- 모든 연관관계 `fetch = LAZY` 명시. OSIV off — LAZY 접근은 트랜잭션 안에서만.
- 빈 주입은 `@RequiredArgsConstructor` + `final` 필드.
- 스키마는 JPA `ddl-auto`(local `update`/prod `validate`) — 마이그레이션 도구 없음. partial index 등 수동 DDL 금지.
- 커밋 메시지: `[YMC-256] type(scope): subject` 한 줄 (Co-Authored-By 등 트레일러 금지).
- 새 외부 의존성 추가 없음 (webflux는 YMC-257에서).
- delta 토큰별 DB 저장 금지 (ADR-004).

## 사전 조건

- **app PR #13(YMC-215 업로드 인증 연동)이 main에 머지돼 있어야 한다.** 이 계획의 컨트롤러는 #13의 패턴(`@AuthenticationPrincipal Jwt jwt` → `UUID.fromString(jwt.getSubject())`)을 그대로 쓴다. 머지 전이면 중단하고 사용자에게 알린다.
- 확인: `cd app && git fetch && git log origin/main --oneline -5` 에 YMC-215 커밋(또는 PR #13 머지 커밋)이 보여야 한다.

## File Structure (전체 조감)

```text
project-docs/contracts/frontend-backend/openapi.yaml   # Task 1: DUPLICATE_MESSAGE 계약 추가
app/be/src/main/java/com/ymc/
├── common/error/
│   ├── ErrorCode.java                                 # Task 2: chat 코드 추가 (Modify)
│   └── GlobalExceptionHandler.java                    # Task 6: duplicate 핸들러 추가 (Modify)
├── paper/service/
│   └── PaperChatAccessValidator.java                  # Task 5: paper 컨텍스트의 채팅 접근 검증 (Create)
└── chat/
    ├── domain/
    │   ├── ChatSession.java                           # Task 3
    │   ├── ChatSessionRepository.java                 # Task 3
    │   ├── ChatMessage.java                           # Task 3
    │   ├── ChatMessageRepository.java                 # Task 3
    │   ├── ChatMessageRole.java                       # Task 3
    │   └── ChatMessageStatus.java                     # Task 3
    ├── service/
    │   ├── port/
    │   │   ├── AiAgentStreamPort.java                 # Task 4
    │   │   ├── AiStreamListener.java                  # Task 4
    │   │   ├── AiRunRequest.java                      # Task 4
    │   │   └── AiRunHandle.java                       # Task 4
    │   ├── ChatMessageTransitions.java                # Task 3
    │   ├── DuplicateChatMessageException.java         # Task 6
    │   ├── ChatStartResult.java                       # Task 7
    │   ├── ChatCommandService.java                    # Task 7
    │   └── ChatStreamService.java                     # Task 8
    ├── infra/ai/
    │   └── FakeAiAgentStreamAdapter.java              # Task 4
    └── api/
        ├── dto/
        │   ├── ChatMessageStreamRequest.java          # Task 9
        │   ├── ChatSseEventData.java                  # Task 8
        │   └── ChatDuplicateMessageResponse.java      # Task 6
        └── ChatController.java                        # Task 9
app/be/src/test/java/com/ymc/
├── support/IntegrationTest.java                       # Task 3: chat repo 정리 추가 (Modify)
└── chat/
    ├── domain/ChatMessageTransitionsTest.java         # Task 3
    ├── infra/FakeAiAgentStreamAdapterTest.java        # Task 4
    ├── service/ChatCommandServiceTest.java            # Task 7
    └── api/ChatMessageStreamIntegrationTest.java      # Task 9
```

---

### Task 1: 계약 PR — 동일 요청 재전송(DUPLICATE_MESSAGE) 응답 정의

계약의 남은 공백 하나: `clientMessageId`가 같고 content도 같은 재전송(결과를 모르는 재시도)의 응답이 미정이다. 설계 §3 "멱등 재시도 의미론"대로 409 + 기존 실행 식별자·상태를 계약에 먼저 넣는다. **project-docs 레포에서 작업한다.**

**Files:**
- Modify: `project-docs/contracts/frontend-backend/openapi.yaml`

**Interfaces:**
- Produces: `Error.code`에 `DUPLICATE_MESSAGE`, 스키마 `ChatDuplicateMessageError` — Task 2(ErrorCode)와 Task 6(응답 DTO)이 이 이름을 그대로 쓴다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd project-docs && git fetch origin && git switch -c YMC-256-chat-duplicate-contract origin/main
```

- [ ] **Step 2: 409 응답 설명·스키마 참조 수정**

`openapi.yaml`의 chat operation `"409":` 블록(현재 line 473~480 부근)을 다음으로 교체:

```yaml
        "409":
          description: |
            논문이 아직 학습 가능 상태가 아니면 PAPER_NOT_READY. 같은 세션에 이미
            생성 중인 답변이 있으면 CHAT_RUN_IN_PROGRESS. clientMessageId를 다른 요청
            내용에 재사용하면 CLIENT_MESSAGE_ID_CONFLICT. 같은 clientMessageId·같은
            content의 재전송이면 DUPLICATE_MESSAGE — 새 run을 만들지 않고 기존 실행의
            식별자와 상태를 ChatDuplicateMessageError로 반환한다. FE는 status로 분기한다
            (GENERATING: 생성 중 안내, COMPLETED: 완료 안내, FAILED: 새 clientMessageId로 재시도).
          content:
            application/json:
              schema:
                oneOf:
                  - $ref: "#/components/schemas/Error"
                  - $ref: "#/components/schemas/ChatDuplicateMessageError"
```

- [ ] **Step 3: ChatDuplicateMessageError 스키마 추가**

`components.schemas`의 `ChatStreamErrorDetail` 다음에 추가:

```yaml
    ChatDuplicateMessageError:
      type: object
      description: |
        같은 clientMessageId·같은 content의 재전송에 대한 409 응답. 새 run은 만들어지지
        않았으며, 최초 요청이 만든 세션·assistant message의 식별자와 현재 상태를 담는다.
      required: [code, message, sessionId, messageId, status]
      additionalProperties: false
      properties:
        code:
          const: DUPLICATE_MESSAGE
        message:
          type: string
          description: 사람이 읽는 설명
        sessionId:
          type: string
          format: uuid
          description: 최초 요청이 사용(또는 생성)한 세션
        messageId:
          type: string
          format: uuid
          description: 최초 요청이 만든 assistant message (message.started의 messageId와 같은 값)
        status:
          $ref: "#/components/schemas/ChatMessageStatus"
```

- [ ] **Step 4: Error.code enum에 DUPLICATE_MESSAGE 추가**

`Error` 스키마의 code description 목록에 한 줄 추가하고 enum 배열에도 추가:

```yaml
            - DUPLICATE_MESSAGE     : 같은 clientMessageId·같은 content 재전송 (409, 기존 실행 상태 반환)
```

enum 배열(`CHAT_USAGE_LIMIT_EXCEEDED,` 뒤)에 `DUPLICATE_MESSAGE,` 추가.

- [ ] **Step 5: 버전 patch 올림**

`info.version`을 patch 증가시킨다 (호환 필드 추가 — contracts/README.md 규칙). 현재 값을 확인하고 마지막 자리 +1.

- [ ] **Step 6: 커밋·푸시·PR**

```bash
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-256] docs(contracts): 채팅 동일 요청 재전송 409 DUPLICATE_MESSAGE 정의"
git push -u origin YMC-256-chat-duplicate-contract
gh pr create --title "[YMC-256] docs(contracts): 채팅 DUPLICATE_MESSAGE 409 정의" --body "..."
```

PR 본문은 `.github/PULL_REQUEST_TEMPLATE.md`가 있으면 그 형식, 없으면 Summary/Changes만. **사용자에게 PR 링크를 보여주고 머지를 확인받은 뒤 다음 태스크로 진행한다** (contract-first).

---

### Task 2: app 브랜치 생성 + ErrorCode에 chat 코드 추가

**Files:**
- Modify: `app/be/src/main/java/com/ymc/common/error/ErrorCode.java`

**Interfaces:**
- Consumes: 계약의 코드 목록 (Task 1 머지본)
- Produces: `ErrorCode.PAPER_NOT_READY / CHAT_SESSION_NOT_FOUND / CHAT_RUN_IN_PROGRESS / CLIENT_MESSAGE_ID_CONFLICT / DUPLICATE_MESSAGE / FORBIDDEN` — Task 5·6·7이 사용

- [ ] **Step 1: 브랜치 생성**

```bash
cd app && git fetch origin && git switch -c YMC-256-chat-api-persistence origin/main
```

이 계획 문서(`docs/superpowers/plans/2026-07-23-ymc-256-chat-api-persistence.md`)가 아직 커밋 전이면 첫 커밋으로 넣는다:

```bash
git add docs/superpowers/plans/2026-07-23-ymc-256-chat-api-persistence.md
git commit -m "[YMC-256] docs(plans): 채팅 API·영속화 구현 계획"
```

- [ ] **Step 2: ErrorCode에 코드 추가**

`ErrorCode.java`의 `AUTH_REFRESH_INVALID(HttpStatus.UNAUTHORIZED);` 를 `,`로 바꾸고 뒤에 추가 (**주의: `FORBIDDEN`이 PR #13에서 이미 추가됐으면 그 항목은 건너뛴다**):

```java
    /** 인증됐지만 대상 논문 접근 권한 없음 (FT-001) */
    FORBIDDEN(HttpStatus.FORBIDDEN),

    /** 논문이 아직 채팅 가능한 상태가 아님 (FT-007) */
    PAPER_NOT_READY(HttpStatus.CONFLICT),

    /** 세션 없음 또는 현재 사용자·논문에 속하지 않음 (FT-007) */
    CHAT_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND),

    /** 같은 세션에서 assistant 답변 생성 중 (FT-007) */
    CHAT_RUN_IN_PROGRESS(HttpStatus.CONFLICT),

    /** clientMessageId를 다른 요청 내용에 재사용함 (FT-007) */
    CLIENT_MESSAGE_ID_CONFLICT(HttpStatus.CONFLICT),

    /** 같은 clientMessageId·같은 content 재전송 — 기존 실행 상태 반환 (FT-007) */
    DUPLICATE_MESSAGE(HttpStatus.CONFLICT);
```

- [ ] **Step 3: 컴파일 확인**

```bash
cd be && ./gradlew compileJava -q
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add be/src/main/java/com/ymc/common/error/ErrorCode.java
git commit -m "[YMC-256] feat(error): 계약의 chat 에러 코드 추가"
```

---

### Task 3: chat 도메인 — 엔티티·리포지토리·조건부 전이

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatSession.java`
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatSessionRepository.java`
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatMessage.java`
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatMessageRepository.java`
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatMessageRole.java`
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatMessageStatus.java`
- Create: `app/be/src/main/java/com/ymc/chat/service/ChatMessageTransitions.java`
- Modify: `app/be/src/test/java/com/ymc/support/IntegrationTest.java` (chat repo 주입 + resetState 정리)
- Test: `app/be/src/test/java/com/ymc/chat/domain/ChatMessageTransitionsTest.java`

**Interfaces:**
- Produces:
  - `ChatSession.open(UUID ownerId, UUID paperId, Instant now)` → `ChatSession` (getId/getOwnerId/getPaperId)
  - `ChatMessage.userMessage(ChatSession, UUID clientMessageId, String content, Instant now)` / `ChatMessage.assistantGenerating(ChatSession, UUID clientMessageId, Instant now)`
  - `ChatSessionRepository.findWithLockById(UUID)` → `Optional<ChatSession>` (PESSIMISTIC_WRITE)
  - `ChatMessageRepository.findByClientMessageIdAndRole(UUID, ChatMessageRole)` / `existsBySessionIdAndStatus(UUID, ChatMessageStatus)` / `markCompleted(UUID, String, Instant)` → int / `markFailed(UUID, Instant)` → int
  - `ChatMessageTransitions.complete(UUID messageId, String content)` → boolean / `fail(UUID messageId)` → boolean — Task 8이 사용

- [ ] **Step 1: enum 2개 작성**

```java
// chat/domain/ChatMessageRole.java
package com.ymc.chat.domain;

/** 메시지 발화 주체. */
public enum ChatMessageRole { USER, ASSISTANT }
```

```java
// chat/domain/ChatMessageStatus.java
package com.ymc.chat.domain;

/**
 * 계약(openapi.yaml `ChatMessageStatus`)과 1:1. GENERATING에서 COMPLETED 또는 FAILED로만 전이한다.
 * user 메시지는 생성 즉시 COMPLETED다.
 */
public enum ChatMessageStatus { GENERATING, COMPLETED, FAILED }
```

- [ ] **Step 2: ChatSession 엔티티 작성**

```java
package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * 논문 하나에 대한 채팅 세션. id는 BE가 insert 전에 만들며 AI thread_id로 그대로 전달된다
 * (계약 x-upstream-event-mapping). 세션은 생성 시 paperId에 고정된다.
 *
 * <p>owner·paper는 다른 컨텍스트라 ID로만 참조한다 (be/CLAUDE.md 의존성 규칙).
 */
@Getter
@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "paper_id", nullable = false, updatable = false)
    private UUID paperId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatSession() {
        // JPA
    }

    private ChatSession(UUID ownerId, UUID paperId, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.paperId = paperId;
        this.createdAt = now;
    }

    /** 새 세션. 첫 질문에서 sessionId 없이 요청이 오면 만든다. */
    public static ChatSession open(UUID ownerId, UUID paperId, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(paperId, "paperId");
        Objects.requireNonNull(now, "now");
        return new ChatSession(ownerId, paperId, now);
    }
}
```

- [ ] **Step 3: ChatMessage 엔티티 작성**

```java
package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;

/**
 * 세션 안의 메시지 한 건. user 질문은 저장 즉시 COMPLETED, assistant 답변은 GENERATING으로
 * 시작해 완료 시 content가 1회 채워진다 (ADR-004 — delta는 저장하지 않는다).
 *
 * <p>{@code GENERATING → COMPLETED/FAILED}는 relay 정상 완료와 timeout이 경쟁하므로 엔티티
 * 메서드가 아닌 {@link ChatMessageRepository}의 조건부 UPDATE로 전이한다 (paper design D2 준용).
 *
 * <p>clientMessageId는 user·assistant 두 행에 같이 저장한다 — 재전송 멱등 판정(user 행)과
 * DUPLICATE_MESSAGE 응답의 messageId·status 조회(assistant 행)를 한 인덱스로 해결한다.
 * 유니크는 (client_message_id, role)이다.
 */
@Getter
@Entity
@Table(
        name = "chat_message",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_message_client_id_role",
                columnNames = {"client_message_id", "role"}))
public class ChatMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16, updatable = false)
    private ChatMessageRole role;

    /** assistant는 완료 전까지 null. 완료 시 조건부 UPDATE로 1회 채워진다. */
    @Column(name = "content", columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ChatMessageStatus status;

    @Column(name = "client_message_id", nullable = false, updatable = false)
    private UUID clientMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ChatMessage() {
        // JPA
    }

    private ChatMessage(ChatSession session, ChatMessageRole role, String content,
            ChatMessageStatus status, UUID clientMessageId, Instant now) {
        this.id = UUID.randomUUID();
        this.session = session;
        this.role = role;
        this.content = content;
        this.status = status;
        this.clientMessageId = clientMessageId;
        this.createdAt = now;
        this.completedAt = status == ChatMessageStatus.COMPLETED ? now : null;
    }

    /** 사용자 질문. 저장 즉시 COMPLETED다. */
    public static ChatMessage userMessage(
            ChatSession session, UUID clientMessageId, String content, Instant now) {
        Objects.requireNonNull(content, "content");
        return new ChatMessage(session, ChatMessageRole.USER, content,
                ChatMessageStatus.COMPLETED, clientMessageId, now);
    }

    /** 생성 중인 assistant 답변 자리. content는 완료 시 조건부 UPDATE로 채운다. */
    public static ChatMessage assistantGenerating(
            ChatSession session, UUID clientMessageId, Instant now) {
        return new ChatMessage(session, ChatMessageRole.ASSISTANT, null,
                ChatMessageStatus.GENERATING, clientMessageId, now);
    }
}
```

- [ ] **Step 4: 리포지토리 2개 작성**

```java
// chat/domain/ChatSessionRepository.java
package com.ymc.chat.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    /**
     * 세션 행을 PESSIMISTIC_WRITE로 잠근다 (SELECT ... FOR UPDATE).
     *
     * <p>"세션당 동시 실행 1개"는 check-then-insert 조회만으로는 경쟁을 막지 못한다 —
     * 같은 세션의 시작 요청을 이 잠금으로 직렬화한다 (설계 §3). 트랜잭션이 짧고
     * (스트리밍 시작 전 commit) 단일 행 잠금이라 데드락 여지가 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSession s where s.id = :id")
    Optional<ChatSession> findWithLockById(UUID id);
}
```

```java
// chat/domain/ChatMessageRepository.java
package com.ymc.chat.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Optional<ChatMessage> findByClientMessageIdAndRole(UUID clientMessageId, ChatMessageRole role);

    boolean existsBySessionIdAndStatus(UUID sessionId, ChatMessageStatus status);

    /**
     * {@code GENERATING → COMPLETED} 조건부 전이 + 최종 content 1회 저장.
     *
     * @return 1이면 이 호출이 전이의 주인. 0이면 이미 다른 경로(실패 처리 등)가 전이시켰다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update ChatMessage m
               set m.status = com.ymc.chat.domain.ChatMessageStatus.COMPLETED,
                   m.content = :content,
                   m.completedAt = :now
             where m.id = :id
               and m.status = com.ymc.chat.domain.ChatMessageStatus.GENERATING
            """)
    int markCompleted(UUID id, String content, Instant now);

    /** {@code GENERATING → FAILED} 조건부 전이. partial content는 저장하지 않는다. */
    @Modifying(clearAutomatically = true)
    @Query("""
            update ChatMessage m
               set m.status = com.ymc.chat.domain.ChatMessageStatus.FAILED,
                   m.completedAt = :now
             where m.id = :id
               and m.status = com.ymc.chat.domain.ChatMessageStatus.GENERATING
            """)
    int markFailed(UUID id, Instant now);
}
```

- [ ] **Step 5: ChatMessageTransitions 작성**

relay(비트랜잭션 스레드)가 부를 트랜잭션 단위. `PaperTransitions`와 같은 이유로 별도 빈이다.

```java
// chat/service/ChatMessageTransitions.java
package com.ymc.chat.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatMessageRepository;

import lombok.RequiredArgsConstructor;

/**
 * assistant 메시지의 종결 전이만 담는 트랜잭션 단위. relay 스레드가 호출한다.
 * 조건부 UPDATE라 완료·실패가 경쟁해도 한쪽만 1 row를 얻는다 (설계 §3 D6).
 */
@Service
@RequiredArgsConstructor
public class ChatMessageTransitions {

    private final ChatMessageRepository chatMessageRepository;

    /** @return 이 호출이 COMPLETED 전이의 주인이면 true */
    @Transactional
    public boolean complete(UUID messageId, String content) {
        return chatMessageRepository.markCompleted(messageId, content, Instant.now()) == 1;
    }

    /** @return 이 호출이 FAILED 전이의 주인이면 true */
    @Transactional
    public boolean fail(UUID messageId) {
        return chatMessageRepository.markFailed(messageId, Instant.now()) == 1;
    }
}
```

- [ ] **Step 6: IntegrationTest에 chat 정리 추가**

`support/IntegrationTest.java`에 주입 필드 2개를 추가하고 `resetState()` 맨 앞에 삭제를 추가한다 (FK 순서: message → session):

```java
    @Autowired
    protected com.ymc.chat.domain.ChatMessageRepository chatMessageRepository;

    @Autowired
    protected com.ymc.chat.domain.ChatSessionRepository chatSessionRepository;
```

```java
    @BeforeEach
    void resetState() {
        chatMessageRepository.deleteAll();
        chatSessionRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        // ... 기존 코드 유지
    }
```

import는 파일 상단 스타일에 맞춰 `com.ymc.chat.domain.*`를 정식 import로 올린다.

- [ ] **Step 7: 실패하는 테스트 작성**

```java
// test/java/com/ymc/chat/domain/ChatMessageTransitionsTest.java
package com.ymc.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.support.IntegrationTest;

class ChatMessageTransitionsTest extends IntegrationTest {

    @Autowired
    ChatMessageTransitions transitions;

    private ChatMessage givenGeneratingAssistant() {
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, UUID.randomUUID(), Instant.now()));
        return chatMessageRepository.save(
                ChatMessage.assistantGenerating(session, UUID.randomUUID(), Instant.now()));
    }

    @Test
    @DisplayName("GENERATING → COMPLETED 전이는 한 번만 성공하고 content를 저장한다")
    void completeOnlyOnce() {
        ChatMessage assistant = givenGeneratingAssistant();

        assertThat(transitions.complete(assistant.getId(), "최종 답변")).isTrue();
        assertThat(transitions.complete(assistant.getId(), "다른 답변")).isFalse();
        assertThat(transitions.fail(assistant.getId())).isFalse();

        ChatMessage saved = chatMessageRepository.findById(assistant.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(saved.getContent()).isEqualTo("최종 답변");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("GENERATING → FAILED 전이 후에는 COMPLETED가 불가능하고 content는 남지 않는다")
    void failBlocksComplete() {
        ChatMessage assistant = givenGeneratingAssistant();

        assertThat(transitions.fail(assistant.getId())).isTrue();
        assertThat(transitions.complete(assistant.getId(), "늦은 답변")).isFalse();

        ChatMessage saved = chatMessageRepository.findById(assistant.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(saved.getContent()).isNull();
    }

    @Test
    @DisplayName("같은 clientMessageId·같은 role은 유니크 제약에 걸린다")
    void clientMessageIdUniquePerRole() {
        ChatSession session = chatSessionRepository.save(
                ChatSession.open(TEST_USER_ID, UUID.randomUUID(), Instant.now()));
        UUID clientMessageId = UUID.randomUUID();
        chatMessageRepository.saveAndFlush(
                ChatMessage.userMessage(session, clientMessageId, "질문", Instant.now()));

        assertThatThrownBy(() -> chatMessageRepository.saveAndFlush(
                ChatMessage.userMessage(session, clientMessageId, "질문", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 8: 테스트 실패 확인 (구현 파일이 없는 상태라면 컴파일 실패도 실패로 간주)**

엔티티·리포지토리를 Step 1~6에서 이미 만들었으므로 여기서는 통과가 목표다. 만약 TDD 순서를 엄격히 밟고 싶다면 Step 7을 Step 1보다 먼저 작성해 컴파일 실패를 확인한 뒤 구현한다.

```bash
cd be && ./gradlew test --tests "com.ymc.chat.domain.ChatMessageTransitionsTest" 
```

Expected: 3 tests PASS (Testcontainers 기동 포함 수 분 소요)

- [ ] **Step 9: 커밋**

```bash
git add be/src/main/java/com/ymc/chat be/src/test/java/com/ymc/chat be/src/test/java/com/ymc/support/IntegrationTest.java
git commit -m "[YMC-256] feat(chat): 세션·메시지 엔티티와 조건부 전이"
```

---

### Task 4: AiAgentStreamPort + fake 어댑터

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/service/port/AiAgentStreamPort.java`
- Create: `app/be/src/main/java/com/ymc/chat/service/port/AiStreamListener.java`
- Create: `app/be/src/main/java/com/ymc/chat/service/port/AiRunRequest.java`
- Create: `app/be/src/main/java/com/ymc/chat/service/port/AiRunHandle.java`
- Create: `app/be/src/main/java/com/ymc/chat/infra/ai/FakeAiAgentStreamAdapter.java`
- Test: `app/be/src/test/java/com/ymc/chat/infra/FakeAiAgentStreamAdapterTest.java`

**Interfaces:**
- Produces (Task 8·9와 YMC-257이 이 시그니처에 의존):
  - `AiAgentStreamPort.stream(AiRunRequest, AiStreamListener)` → `AiRunHandle`
  - `AiStreamListener`: `onRunStarted()`, `onDelta(String)`, `onMessageCompleted(String)`, `onRunCompleted()`, `onRunFailed(String)`, `onTransportError(Exception)`
  - `AiRunRequest(String threadId, String message)` record
  - `AiRunHandle.cancel()`

- [ ] **Step 1: 포트 타입 4개 작성**

```java
// chat/service/port/AiRunRequest.java
package com.ymc.chat.service.port;

/** BE↔AI 계약(simple-agent-run-stream.yml)의 request body. thread_id = sessionId 문자열. */
public record AiRunRequest(String threadId, String message) {
}
```

```java
// chat/service/port/AiRunHandle.java
package com.ymc.chat.service.port;

/** 진행 중인 AI run의 취소 손잡이. YMC-257의 timeout 워치독이 사용한다. */
public interface AiRunHandle {

    /** upstream 연결을 끊어 AI의 생성 취소를 유도한다. 중복 호출은 무해해야 한다. */
    void cancel();
}
```

```java
// chat/service/port/AiStreamListener.java
package com.ymc.chat.service.port;

/**
 * AI 스트림 이벤트 수신 콜백. 구현체(어댑터)는 한 run의 콜백을 순서대로, 한 번에 하나씩
 * 호출해야 한다 — 리스너 쪽에서 동기화를 추가하지 않는다.
 *
 * <p>terminal은 {@code onRunCompleted / onRunFailed / onTransportError} 중 정확히 하나다.
 */
public interface AiStreamListener {

    void onRunStarted();

    /** assistant 응답의 부분 문자열. 완전한 단어·문장·Markdown block이라고 가정하지 않는다. */
    void onDelta(String delta);

    /** AI가 만든 최종 답변 전문. 이 시점은 아직 성공 확정이 아니다 (run.completed 대기). */
    void onMessageCompleted(String message);

    void onRunCompleted();

    /** AI가 run.failed를 보냄. raw error는 FE에 노출하지 않는다. */
    void onRunFailed(String error);

    /** terminal event 없이 연결이 끊기거나 스트림 소비 중 예외가 남. */
    void onTransportError(Exception cause);
}
```

```java
// chat/service/port/AiAgentStreamPort.java
package com.ymc.chat.service.port;

/**
 * AI 에이전트 스트리밍 호출 포트. 외부 시스템(AI 서버) 격리 — 인터페이스는 service/,
 * 구현은 infra/ (be/CLAUDE.md). YMC-256은 fake, YMC-257이 WebClient 구현으로 교체한다.
 *
 * <p>reactive 타입을 노출하지 않는다 — service 계층은 동기 콜백 모델을 유지한다 (설계 §3 D4).
 */
public interface AiAgentStreamPort {

    /** 스트림을 시작하고 즉시 반환한다. 이벤트는 어댑터의 스레드에서 listener로 전달된다. */
    AiRunHandle stream(AiRunRequest request, AiStreamListener listener);
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// test/java/com/ymc/chat/infra/FakeAiAgentStreamAdapterTest.java
package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ymc.chat.infra.ai.FakeAiAgentStreamAdapter;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

/** 순수 단위 테스트 — 스프링 컨텍스트 불필요. */
class FakeAiAgentStreamAdapterTest {

    final List<String> events = new CopyOnWriteArrayList<>();

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { events.add("started"); }
        public void onDelta(String delta) { events.add("delta:" + delta); }
        public void onMessageCompleted(String message) { events.add("completed:" + message); }
        public void onRunCompleted() { events.add("run-completed"); }
        public void onRunFailed(String error) { events.add("run-failed:" + error); }
        public void onTransportError(Exception cause) { events.add("transport-error"); }
    };

    @Test
    @DisplayName("성공 시퀀스를 순서대로 콜백하고, delta 누적과 최종 답변이 일치한다")
    void successSequence() {
        new FakeAiAgentStreamAdapter().stream(new AiRunRequest("t-1", "질문"), recorder);

        await().atMost(Duration.ofSeconds(5)).until(() -> events.contains("run-completed"));

        assertThat(events).containsExactly(
                "started",
                "delta:가짜 ",
                "delta:응답",
                "delta:입니다.",
                "completed:가짜 응답입니다.",
                "run-completed");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.infra.FakeAiAgentStreamAdapterTest"
```

Expected: 컴파일 실패 — `FakeAiAgentStreamAdapter` 없음

- [ ] **Step 4: fake 어댑터 구현**

```java
// chat/infra/ai/FakeAiAgentStreamAdapter.java
package com.ymc.chat.infra.ai;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

/**
 * 고정 delta를 흘려보내는 fake 구현. YMC-256에서 SSE 성공 경로를 미리 검증하기 위한 것으로,
 * YMC-257의 WebClient 어댑터가 본 구현이 되면 테스트 스코프로 이동한다 (설계 §3).
 *
 * <p>run당 virtual thread 하나에서 콜백을 순서대로 호출한다 — 실제 어댑터와 같은
 * "controller 스레드 밖에서 이벤트가 온다"는 성질을 유지한다.
 */
@Component
public class FakeAiAgentStreamAdapter implements AiAgentStreamPort {

    static final List<String> DELTAS = List.of("가짜 ", "응답", "입니다.");

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public AiRunHandle stream(AiRunRequest request, AiStreamListener listener) {
        executor.execute(() -> {
            listener.onRunStarted();
            StringBuilder full = new StringBuilder();
            for (String delta : DELTAS) {
                full.append(delta);
                listener.onDelta(delta);
            }
            listener.onMessageCompleted(full.toString());
            listener.onRunCompleted();
        });
        return () -> {
            // fake는 즉시 완료되므로 취소할 것이 없다
        };
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.infra.FakeAiAgentStreamAdapterTest"
```

Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add be/src/main/java/com/ymc/chat/service/port be/src/main/java/com/ymc/chat/infra be/src/test/java/com/ymc/chat/infra
git commit -m "[YMC-256] feat(chat): AI 스트림 포트와 fake 어댑터"
```

---

### Task 5: PaperChatAccessValidator — paper 컨텍스트의 채팅 접근 검증

chat 컨텍스트는 paper 엔티티·리포지토리를 직접 만지지 않는다(컨텍스트 간 ID 참조 규칙). paper 컨텍스트가 검증 서비스를 제공하고 chat이 호출한다.

**Files:**
- Create: `app/be/src/main/java/com/ymc/paper/service/PaperChatAccessValidator.java`

**Interfaces:**
- Consumes: `PaperRepository.findById`, `Paper.getOwnerId()/getStatus()`, `ErrorCode.{PAPER_NOT_FOUND, FORBIDDEN, PAPER_NOT_READY}` (Task 2)
- Produces: `validateChatReady(UUID paperId, UUID ownerId)` — Task 7이 호출. 통과 못 하면 `ApiException`.

- [ ] **Step 1: 구현**

```java
// paper/service/PaperChatAccessValidator.java
package com.ymc.paper.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * 채팅(FT-007)이 요구하는 논문 접근 검증. chat 컨텍스트는 paper를 ID로만 알므로
 * (be/CLAUDE.md 의존성 규칙) 검증은 paper 컨텍스트가 서비스로 제공한다.
 *
 * <p>학습 페이지 진입은 파싱 완료(COMPLETED) 논문에서만 가능하다 (계약 PaperListItem 주석).
 */
@Service
@RequiredArgsConstructor
public class PaperChatAccessValidator {

    private final PaperRepository paperRepository;

    /**
     * @throws ApiException PAPER_NOT_FOUND(404) — 논문 없음
     * @throws ApiException FORBIDDEN(403) — 소유자가 아님
     * @throws ApiException PAPER_NOT_READY(409) — 파싱 완료 상태가 아님
     */
    @Transactional(readOnly = true)
    public void validateChatReady(UUID paperId, UUID ownerId) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));

        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        if (paper.getStatus() != PaperStatus.COMPLETED) {
            throw new ApiException(ErrorCode.PAPER_NOT_READY,
                    "논문이 아직 학습 가능한 상태가 아닙니다: " + paper.getStatus());
        }
    }
}
```

- [ ] **Step 2: 컴파일 확인 후 커밋**

이 빈의 세 분기는 Task 9의 통합 테스트(403/404/409 케이스)가 HTTP 레벨에서 검증한다 — 별도 단위 테스트를 만들지 않는다 (동일 검증의 중복).

```bash
cd be && ./gradlew compileJava -q
git add be/src/main/java/com/ymc/paper/service/PaperChatAccessValidator.java
git commit -m "[YMC-256] feat(paper): 채팅 접근 검증 서비스"
```

---

### Task 6: DUPLICATE_MESSAGE 예외·응답

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/service/DuplicateChatMessageException.java`
- Create: `app/be/src/main/java/com/ymc/chat/api/dto/ChatDuplicateMessageResponse.java`
- Modify: `app/be/src/main/java/com/ymc/common/error/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `ChatMessageStatus` (Task 3), `ErrorCode.DUPLICATE_MESSAGE` (Task 2)
- Produces: `DuplicateChatMessageException(UUID sessionId, UUID messageId, ChatMessageStatus status)` — Task 7이 던지고, 핸들러가 계약의 `ChatDuplicateMessageError` 형태(409)로 렌더한다.

- [ ] **Step 1: 예외 작성**

```java
// chat/service/DuplicateChatMessageException.java
package com.ymc.chat.service;

import java.util.UUID;

import com.ymc.chat.domain.ChatMessageStatus;

import lombok.Getter;

/**
 * 같은 clientMessageId·같은 content의 재전송. 새 run을 만들지 않았고, 계약의
 * ChatDuplicateMessageError(409)로 기존 실행의 식별자·상태를 돌려준다.
 */
@Getter
public class DuplicateChatMessageException extends RuntimeException {

    private final UUID sessionId;
    private final UUID messageId;
    private final ChatMessageStatus status;

    public DuplicateChatMessageException(UUID sessionId, UUID messageId, ChatMessageStatus status) {
        super("이미 처리된(또는 처리 중인) 요청입니다.");
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.status = status;
    }
}
```

- [ ] **Step 2: 응답 DTO 작성**

```java
// chat/api/dto/ChatDuplicateMessageResponse.java
package com.ymc.chat.api.dto;

import java.util.UUID;

import com.ymc.chat.domain.ChatMessageStatus;

/** 계약의 `ChatDuplicateMessageError` 스키마 (409, code = DUPLICATE_MESSAGE). */
public record ChatDuplicateMessageResponse(
        String code, String message, UUID sessionId, UUID messageId, ChatMessageStatus status) {

    public static ChatDuplicateMessageResponse of(
            String message, UUID sessionId, UUID messageId, ChatMessageStatus status) {
        return new ChatDuplicateMessageResponse("DUPLICATE_MESSAGE", message, sessionId, messageId, status);
    }
}
```

- [ ] **Step 3: GlobalExceptionHandler에 핸들러 추가**

`GlobalExceptionHandler.java`에 메서드 추가 (import: `com.ymc.chat.api.dto.ChatDuplicateMessageResponse`, `com.ymc.chat.service.DuplicateChatMessageException`, `org.springframework.http.HttpStatus`):

```java
    /** 같은 clientMessageId·같은 content 재전송 — 기존 실행 식별자·상태를 담아 409 (계약 ChatDuplicateMessageError). */
    @ExceptionHandler(DuplicateChatMessageException.class)
    public ResponseEntity<ChatDuplicateMessageResponse> handleDuplicateChatMessage(
            DuplicateChatMessageException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ChatDuplicateMessageResponse.of(
                        e.getMessage(), e.getSessionId(), e.getMessageId(), e.getStatus()));
    }
```

- [ ] **Step 4: 컴파일 확인 후 커밋**

렌더링 검증은 Task 9 통합 테스트(중복 재전송 케이스)가 담당한다.

```bash
cd be && ./gradlew compileJava -q
git add be/src/main/java/com/ymc/chat/service/DuplicateChatMessageException.java be/src/main/java/com/ymc/chat/api/dto/ChatDuplicateMessageResponse.java be/src/main/java/com/ymc/common/error/GlobalExceptionHandler.java
git commit -m "[YMC-256] feat(chat): DUPLICATE_MESSAGE 409 응답"
```

---

### Task 7: ChatCommandService — 시작 트랜잭션

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/service/ChatStartResult.java`
- Create: `app/be/src/main/java/com/ymc/chat/service/ChatCommandService.java`
- Test: `app/be/src/test/java/com/ymc/chat/service/ChatCommandServiceTest.java`

**Interfaces:**
- Consumes: Task 3 리포지토리·팩토리, Task 5 `validateChatReady`, Task 6 예외, `ErrorCode.{CHAT_SESSION_NOT_FOUND, CHAT_RUN_IN_PROGRESS, CLIENT_MESSAGE_ID_CONFLICT}`
- Produces: `start(UUID ownerId, UUID paperId, UUID sessionIdOrNull, UUID clientMessageId, String content)` → `ChatStartResult(UUID paperId, UUID sessionId, UUID assistantMessageId, UUID clientMessageId)` — Task 9 컨트롤러가 호출. **이 메서드가 리턴한 시점 = user·assistant row가 commit된 시점**이다 (계약: commit 뒤 message.started).

- [ ] **Step 1: 결과 record 작성**

```java
// chat/service/ChatStartResult.java
package com.ymc.chat.service;

import java.util.UUID;

/** 시작 트랜잭션 commit 후 relay·SSE event 구성에 필요한 식별자 묶음. */
public record ChatStartResult(
        UUID paperId, UUID sessionId, UUID assistantMessageId, UUID clientMessageId) {
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
// test/java/com/ymc/chat/service/ChatCommandServiceTest.java
package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class ChatCommandServiceTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    /** 파싱 완료(COMPLETED) 논문 — 채팅 가능 상태. */
    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("chat-target.pdf");
        paperTransitions.markParsed(paper.getId(), com.ymc.paper.domain.PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("sessionId 없이 시작하면 세션을 만들고 user COMPLETED + assistant GENERATING을 저장한다")
    void startCreatesSessionAndRows() {
        Paper paper = givenCompletedPaper();

        ChatStartResult result = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "핵심 기여가 뭐야?");

        ChatSession session = chatSessionRepository.findById(result.sessionId()).orElseThrow();
        assertThat(session.getPaperId()).isEqualTo(paper.getId());
        assertThat(session.getOwnerId()).isEqualTo(TEST_USER_ID);

        ChatMessage assistant = chatMessageRepository.findById(result.assistantMessageId()).orElseThrow();
        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.GENERATING);
        assertThat(assistant.getRole()).isEqualTo(ChatMessageRole.ASSISTANT);
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("기존 sessionId로 시작하면 같은 세션에 메시지가 쌓인다")
    void startReusesSession() {
        Paper paper = givenCompletedPaper();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "첫 질문");
        // 첫 assistant를 종결시켜 CHAT_RUN_IN_PROGRESS를 피한다
        // (@Modifying 쿼리는 트랜잭션이 필요하므로 리포지토리 직접 호출이 아니라 transitions 빈을 쓴다)
        chatMessageTransitions.complete(first.assistantMessageId(), "답");

        ChatStartResult second = chatCommandService.start(
                TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "후속 질문");

        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(chatMessageRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("남의 세션이나 다른 논문의 세션이면 CHAT_SESSION_NOT_FOUND")
    void rejectsForeignSession() {
        Paper paper = givenCompletedPaper();
        ChatSession otherOwners = chatSessionRepository.save(
                ChatSession.open(UUID.randomUUID(), paper.getId(), Instant.now()));

        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), otherOwners.getId(), UUID.randomUUID(), "질문"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CHAT_SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("세션에 GENERATING assistant가 있으면 CHAT_RUN_IN_PROGRESS")
    void rejectsConcurrentRun() {
        Paper paper = givenCompletedPaper();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "첫 질문");

        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), first.sessionId(), UUID.randomUUID(), "성급한 질문"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CHAT_RUN_IN_PROGRESS));
    }

    @Test
    @DisplayName("같은 clientMessageId·같은 content 재전송은 기존 식별자·상태를 담은 예외 — 새 행을 만들지 않는다")
    void duplicateSameContentIsIdempotent() {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문");

        DuplicateChatMessageException dup = catchThrowableOfType(
                DuplicateChatMessageException.class,
                () -> chatCommandService.start(
                        TEST_USER_ID, paper.getId(), first.sessionId(), clientMessageId, "같은 질문"));

        assertThat(dup.getSessionId()).isEqualTo(first.sessionId());
        assertThat(dup.getMessageId()).isEqualTo(first.assistantMessageId());
        assertThat(dup.getStatus()).isEqualTo(ChatMessageStatus.GENERATING);
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 clientMessageId를 다른 content에 재사용하면 CLIENT_MESSAGE_ID_CONFLICT")
    void duplicateDifferentContentConflicts() {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        chatCommandService.start(TEST_USER_ID, paper.getId(), null, clientMessageId, "원래 질문");

        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "다른 질문"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT));
        assertThat(chatMessageRepository.count()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.service.ChatCommandServiceTest"
```

Expected: 컴파일 실패 — `ChatCommandService` 없음

- [ ] **Step 4: 구현**

```java
// chat/service/ChatCommandService.java
package com.ymc.chat.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.service.PaperChatAccessValidator;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 시작 트랜잭션 — 검증과 저장까지만. 스트리밍은 이 메서드가 commit된 뒤
 * {@link ChatStreamService}가 시작한다 (계약: commit 뒤 message.started).
 *
 * <p>세션당 동시 실행 1개는 기존 세션 행의 PESSIMISTIC_WRITE 잠금으로 보장한다.
 * 새 세션은 방금 만든 UUID라 경쟁 상대가 존재할 수 없다 (설계 §3).
 */
@Service
@RequiredArgsConstructor
public class ChatCommandService {

    private final PaperChatAccessValidator paperChatAccessValidator;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN / PAPER_NOT_READY — 논문 검증 실패
     * @throws ApiException CHAT_SESSION_NOT_FOUND — 세션 없음·소유/논문 불일치
     * @throws ApiException CHAT_RUN_IN_PROGRESS — 세션에 GENERATING assistant 존재
     * @throws ApiException CLIENT_MESSAGE_ID_CONFLICT — 같은 id, 다른 content
     * @throws DuplicateChatMessageException — 같은 id, 같은 content (멱등 재전송)
     */
    @Transactional
    public ChatStartResult start(
            UUID ownerId, UUID paperId, UUID sessionIdOrNull, UUID clientMessageId, String content) {

        paperChatAccessValidator.validateChatReady(paperId, ownerId);
        rejectDuplicate(clientMessageId, content);

        ChatSession session = resolveSession(ownerId, paperId, sessionIdOrNull);

        if (chatMessageRepository.existsBySessionIdAndStatus(
                session.getId(), ChatMessageStatus.GENERATING)) {
            throw new ApiException(ErrorCode.CHAT_RUN_IN_PROGRESS, "이미 답변을 생성하고 있습니다.");
        }

        Instant now = Instant.now();
        ChatMessage assistant;
        try {
            chatMessageRepository.save(
                    ChatMessage.userMessage(session, clientMessageId, content, now));
            assistant = chatMessageRepository.saveAndFlush(
                    ChatMessage.assistantGenerating(session, clientMessageId, now));
        } catch (DataIntegrityViolationException e) {
            // 사전 조회를 나란히 통과한 동시 재전송 — 유니크 제약이 최후 방어선 (paper design D4 준용)
            rejectDuplicate(clientMessageId, content);
            throw e; // rejectDuplicate가 못 잡는 위반이면 예상 밖 — 그대로 5xx
        }

        return new ChatStartResult(paperId, session.getId(), assistant.getId(), clientMessageId);
    }

    /** 재전송 판정. 같은 content면 멱등(기존 상태 반환), 다르면 CONFLICT. */
    private void rejectDuplicate(UUID clientMessageId, String content) {
        Optional<ChatMessage> existingUser =
                chatMessageRepository.findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.USER);
        if (existingUser.isEmpty()) {
            return;
        }
        if (!existingUser.get().getContent().equals(content)) {
            throw new ApiException(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT,
                    "clientMessageId가 다른 요청에 이미 사용되었습니다.");
        }
        ChatMessage assistant = chatMessageRepository
                .findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.ASSISTANT)
                .orElseThrow(() -> new IllegalStateException(
                        "user 행만 있고 assistant 행이 없습니다: " + clientMessageId));
        throw new DuplicateChatMessageException(
                assistant.getSession().getId(), assistant.getId(), assistant.getStatus());
    }

    private ChatSession resolveSession(UUID ownerId, UUID paperId, UUID sessionIdOrNull) {
        if (sessionIdOrNull == null) {
            return chatSessionRepository.save(ChatSession.open(ownerId, paperId, Instant.now()));
        }
        ChatSession session = chatSessionRepository.findWithLockById(sessionIdOrNull)
                .orElseThrow(this::sessionNotFound);
        if (!session.getOwnerId().equals(ownerId) || !session.getPaperId().equals(paperId)) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션도 404 (계약)
        }
        return session;
    }

    private ApiException sessionNotFound() {
        return new ApiException(ErrorCode.CHAT_SESSION_NOT_FOUND,
                "세션이 없거나 이 논문의 세션이 아닙니다.");
    }
}
```

주의: `rejectDuplicate`의 LAZY 접근(`assistant.getSession().getId()`)은 `@Transactional` 안이라 안전하다 (OSIV off 규칙).

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.service.ChatCommandServiceTest"
```

Expected: 6 tests PASS

- [ ] **Step 6: 커밋**

```bash
git add be/src/main/java/com/ymc/chat/service be/src/test/java/com/ymc/chat/service
git commit -m "[YMC-256] feat(chat): 채팅 시작 트랜잭션 — 검증·멱등·세션 잠금·저장"
```

---

### Task 8: SSE event payload + ChatStreamService (relay)

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/api/dto/ChatSseEventData.java`
- Create: `app/be/src/main/java/com/ymc/chat/service/ChatStreamService.java`

**Interfaces:**
- Consumes: Task 3 `ChatMessageTransitions`, Task 4 포트, Task 7 `ChatStartResult`
- Produces: `ChatStreamService.begin(SseEmitter emitter, ChatStartResult started, String userContent)` — Task 9 컨트롤러가 호출. `ChatSseEventData.*` payload record들 — 계약 `ChatSseEvent`의 data 스키마와 1:1.

- [ ] **Step 1: SSE payload record 작성**

계약의 `Chat*EventData` 스키마와 필드가 1:1이어야 한다. `heartbeat`는 YMC-257 범위라 여기 없다.

```java
// chat/api/dto/ChatSseEventData.java
package com.ymc.chat.api.dto;

import java.util.UUID;

/**
 * 계약(openapi.yaml `ChatSseEvent`)의 event별 data payload. record 이름이 아니라
 * {@code type} 필드와 SSE event line이 계약의 event 이름이다 (data.type == event 이름 규칙).
 */
public final class ChatSseEventData {

    private ChatSseEventData() {
    }

    /** event: message.started — commit 후 식별자 확정 통지. */
    public record Started(
            String type, UUID paperId, UUID sessionId, UUID messageId,
            UUID clientMessageId, String status) {

        public static Started of(UUID paperId, UUID sessionId, UUID messageId, UUID clientMessageId) {
            return new Started("message.started", paperId, sessionId, messageId,
                    clientMessageId, "GENERATING");
        }
    }

    /** event: message.delta — 부분 문자열 조각. */
    public record Delta(String type, UUID paperId, UUID sessionId, UUID messageId, String delta) {

        public static Delta of(UUID paperId, UUID sessionId, UUID messageId, String delta) {
            return new Delta("message.delta", paperId, sessionId, messageId, delta);
        }
    }

    /** event: message.completed — COMPLETED commit 후의 성공 terminal. */
    public record Completed(
            String type, UUID paperId, UUID sessionId, UUID messageId,
            String content, String contentFormat, String status) {

        public static Completed of(UUID paperId, UUID sessionId, UUID messageId, String content) {
            return new Completed("message.completed", paperId, sessionId, messageId,
                    content, "markdown", "COMPLETED");
        }
    }

    /** event: error — FAILED commit 후의 실패 terminal. */
    public record StreamError(
            String type, UUID paperId, UUID sessionId, UUID messageId,
            String status, Detail error) {

        public record Detail(String code, String message, boolean retryable) {
        }

        public static StreamError of(
                UUID paperId, UUID sessionId, UUID messageId,
                String code, String message, boolean retryable) {
            return new StreamError("error", paperId, sessionId, messageId, "FAILED",
                    new Detail(code, message, retryable));
        }
    }
}
```

- [ ] **Step 2: ChatStreamService 작성**

```java
// chat/service/ChatStreamService.java
package com.ymc.chat.service;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatSseEventData;
import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

import lombok.RequiredArgsConstructor;

/**
 * 시작 트랜잭션 commit 후의 스트리밍 조율 — message.started 전송, AI 스트림 구독,
 * delta 중계, 종결 시 상태 확정과 terminal event 전송 (ADR-004).
 *
 * <p>YMC-256 범위: fake 포트 기준의 성공·실패 경로. timeout·heartbeat·누적 상한은
 * YMC-257에서 이 클래스에 추가된다 (설계 §4).
 *
 * <p>FE 연결이 끊겨도 upstream 소비와 최종 저장은 계속한다 (계약 frontendDisconnect) —
 * emitter 전송만 스킵한다.
 */
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final AiAgentStreamPort aiAgentStreamPort;
    private final ChatMessageTransitions transitions;

    /** message.started를 보내고 AI 스트림을 시작한다. 호출 시점은 시작 트랜잭션 commit 후다. */
    public void begin(SseEmitter emitter, ChatStartResult started, String userContent) {
        Run run = new Run(emitter, started);
        run.sendStarted();
        aiAgentStreamPort.stream(
                new AiRunRequest(started.sessionId().toString(), userContent), run);
    }

    /** 한 스트림의 상태. 어댑터가 콜백을 직렬 호출하므로 필드 동기화는 FE 단절 플래그만 필요하다. */
    private class Run implements AiStreamListener {

        private final SseEmitter emitter;
        private final ChatStartResult ids;
        private final AtomicBoolean feConnected = new AtomicBoolean(true);
        private final StringBuilder accumulated = new StringBuilder();
        private String finalContent;

        private Run(SseEmitter emitter, ChatStartResult ids) {
            this.emitter = emitter;
            this.ids = ids;
            emitter.onCompletion(() -> feConnected.set(false));
            emitter.onError(t -> feConnected.set(false));
            emitter.onTimeout(() -> feConnected.set(false));
        }

        private void sendStarted() {
            send("message.started", ChatSseEventData.Started.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), ids.clientMessageId()));
        }

        @Override
        public void onRunStarted() {
            // BE 내부 확인용 — FE에는 보내지 않는다 (계약 x-upstream-event-mapping)
        }

        @Override
        public void onDelta(String delta) {
            accumulated.append(delta);
            send("message.delta", ChatSseEventData.Delta.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), delta));
        }

        @Override
        public void onMessageCompleted(String message) {
            this.finalContent = message; // 아직 성공 아님 — run.completed 대기
        }

        @Override
        public void onRunCompleted() {
            if (finalContent == null) {
                // message.completed 없이 run.completed — 계약의 AI_PROTOCOL_ERROR
                failWith("AI_PROTOCOL_ERROR", "답변 생성 결과가 올바르지 않습니다.", false);
                return;
            }
            if (!accumulated.toString().equals(finalContent)) {
                log.warn("누적 delta와 최종 답변이 다릅니다. messageId={} 누적={}자 최종={}자",
                        ids.assistantMessageId(), accumulated.length(), finalContent.length());
            }
            boolean committed;
            try {
                committed = transitions.complete(ids.assistantMessageId(), finalContent);
            } catch (RuntimeException e) {
                log.error("최종 답변 저장 실패. messageId={}", ids.assistantMessageId(), e);
                failWith("MESSAGE_PERSISTENCE_FAILED", "답변을 저장하지 못했습니다.", true);
                return;
            }
            if (!committed) {
                // 이미 다른 경로가 FAILED로 확정 — 성공 event를 보내지 않는다
                complete();
                return;
            }
            send("message.completed", ChatSseEventData.Completed.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), finalContent));
            complete();
        }

        @Override
        public void onRunFailed(String error) {
            log.warn("AI run 실패. messageId={} error={}", ids.assistantMessageId(), error);
            failWith("AI_RUN_FAILED", "답변을 생성하지 못했습니다.", true);
        }

        @Override
        public void onTransportError(Exception cause) {
            log.warn("AI 스트림 단절. messageId={}", ids.assistantMessageId(), cause);
            failWith("AI_STREAM_DISCONNECTED", "답변 생성 연결이 끊어졌습니다.", true);
        }

        /** FAILED 확정 후 terminal error 전송. 저장 실패 시 terminal 없이 닫는다 (계약). */
        private void failWith(String code, String message, boolean retryable) {
            try {
                transitions.fail(ids.assistantMessageId());
            } catch (RuntimeException e) {
                log.error("FAILED 전이조차 실패 — terminal 없이 종료. messageId={}",
                        ids.assistantMessageId(), e);
                emitter.completeWithError(e);
                return;
            }
            send("error", ChatSseEventData.StreamError.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), code, message, retryable));
            complete();
        }

        private void send(String eventName, Object payload) {
            if (!feConnected.get()) {
                return; // FE만 끊긴 것 — upstream 소비·저장은 계속 (계약 frontendDisconnect)
            }
            try {
                emitter.send(SseEmitter.event().name(eventName)
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                feConnected.set(false);
                log.debug("FE 전송 중단 — 연결 종료로 판단. messageId={}", ids.assistantMessageId());
            }
        }

        private void complete() {
            if (feConnected.get()) {
                emitter.complete();
            }
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인 후 커밋**

동작 검증은 Task 9의 통합 테스트가 HTTP 레벨에서 수행한다 (성공 스트림·실패 스트림·FE 단절).

```bash
cd be && ./gradlew compileJava -q
git add be/src/main/java/com/ymc/chat/api/dto/ChatSseEventData.java be/src/main/java/com/ymc/chat/service/ChatStreamService.java
git commit -m "[YMC-256] feat(chat): SSE payload와 스트림 relay"
```

---

### Task 9: ChatController + 통합 테스트

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/api/dto/ChatMessageStreamRequest.java`
- Create: `app/be/src/main/java/com/ymc/chat/api/ChatController.java`
- Modify: `app/be/src/test/java/com/ymc/support/IntegrationTest.java` (AiAgentStreamPort 스파이 추가)
- Test: `app/be/src/test/java/com/ymc/chat/api/ChatMessageStreamIntegrationTest.java`

**Interfaces:**
- Consumes: Task 7 `ChatCommandService.start`, Task 8 `ChatStreamService.begin`
- Produces: `POST /api/papers/{paperId}/chat/messages` → `text/event-stream` (계약 그대로)

- [ ] **Step 1: 요청 DTO 작성**

```java
// chat/api/dto/ChatMessageStreamRequest.java
package com.ymc.chat.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 계약의 `ChatMessageStreamRequest`. sessionId는 첫 질문에서 null이다. */
public record ChatMessageStreamRequest(
        UUID sessionId,
        @NotNull UUID clientMessageId,
        @NotBlank String content) {
}
```

- [ ] **Step 2: 컨트롤러 작성**

```java
// chat/api/ChatController.java
package com.ymc.chat.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ymc.chat.api.dto.ChatMessageStreamRequest;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.chat.service.ChatStreamService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 계약(openapi.yaml)의 /api/papers/{paperId}/chat/messages. HTTP ↔ DTO 변환만 한다. */
@RestController
@RequestMapping("/api/papers/{paperId}/chat")
@RequiredArgsConstructor
public class ChatController {

    /**
     * MVC async timeout. YMC-257의 application deadline(10분)보다 커야 워치독이 먼저
     * 발화한다 (설계 §4 타이머 표). 값 조정은 257에서 설정 프로퍼티로 옮기며 함께 한다.
     */
    static final long EMITTER_TIMEOUT_MS = 11 * 60 * 1000L;

    private final ChatCommandService chatCommandService;
    private final ChatStreamService chatStreamService;

    /** 질문을 저장(commit)한 뒤 SSE 스트림을 시작한다. 스트림 전 오류는 JSON으로 반환된다. */
    @PostMapping(path = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> createMessageStream(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paperId,
            @Valid @RequestBody ChatMessageStreamRequest request) {

        UUID ownerId = UUID.fromString(jwt.getSubject());
        ChatStartResult started = chatCommandService.start(
                ownerId, paperId, request.sessionId(), request.clientMessageId(), request.content());

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        chatStreamService.begin(emitter, started, request.content());

        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform") // 중간 계층 버퍼링·캐싱 방지 (계약)
                .body(emitter);
    }
}
```

- [ ] **Step 3: IntegrationTest에 포트 스파이 추가**

베이스의 스파이 조합에 추가한다 (조합을 베이스 한 곳에 모으는 기존 규칙 — 컨텍스트 캐시 분열 방지):

```java
    @MockitoSpyBean
    protected com.ymc.chat.service.port.AiAgentStreamPort aiAgentStreamPort;
```

import 정리 후, 스파이는 기본적으로 fake에 위임하므로 기존 테스트 동작은 변하지 않는다.

- [ ] **Step 4: 실패하는 통합 테스트 작성**

```java
// test/java/com/ymc/chat/api/ChatMessageStreamIntegrationTest.java
package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

class ChatMessageStreamIntegrationTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("chat-e2e.pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    private String body(UUID sessionId, UUID clientMessageId, String content) throws Exception {
        // Map.of는 null 값을 허용하지 않으므로 ObjectNode로 조립한다 — sessionId null이면 키를 뺀다
        var node = objectMapper.createObjectNode()
                .put("clientMessageId", clientMessageId.toString())
                .put("content", content);
        if (sessionId != null) {
            node.put("sessionId", sessionId.toString());
        }
        return objectMapper.writeValueAsString(node);
    }

    private MvcResult startStream(Paper paper, String bodyJson) throws Exception {
        return mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    @Test
    @DisplayName("성공 스트림 — started → delta×3 → completed 순서로 오고 DB는 COMPLETED다")
    void successStream() throws Exception {
        Paper paper = givenCompletedPaper();
        MvcResult result = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "핵심 기여가 뭐야?")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                chatMessageRepository.findAll().stream()
                        .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT)
                        .findFirst().orElseThrow().getStatus())
                .isEqualTo(ChatMessageStatus.COMPLETED));

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);

        int startedAt = stream.indexOf("event:message.started");
        int firstDeltaAt = stream.indexOf("event:message.delta");
        int completedAt = stream.indexOf("event:message.completed");
        assertThat(startedAt).isNotNegative();
        assertThat(firstDeltaAt).isGreaterThan(startedAt);
        assertThat(completedAt).isGreaterThan(firstDeltaAt);
        assertThat(stream).contains("\"content\":\"가짜 응답입니다.\"");

        ChatMessage assistant = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT).findFirst().orElseThrow();
        assertThat(assistant.getContent()).isEqualTo("가짜 응답입니다.");
    }

    @Test
    @DisplayName("AI 콜백 시점에 user·assistant row는 이미 commit돼 있다 (commit 뒤 message.started 규칙)")
    void rowsCommittedBeforeStreamStarts() throws Exception {
        Paper paper = givenCompletedPaper();
        // 포트 호출 시점(=message.started 이후)에 별도 스레드가 DB를 읽어도 행이 보여야 한다
        doAnswer(invocation -> {
            assertThat(chatMessageRepository.count()).isEqualTo(2);
            return invocation.callRealMethod();
        }).when(aiAgentStreamPort).stream(any(), any(AiStreamListener.class));

        MvcResult result = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "질문")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(chatMessageRepository.findAll().stream()
                        .anyMatch(m -> m.getStatus() == ChatMessageStatus.COMPLETED
                                && m.getRole() == ChatMessageRole.ASSISTANT)).isTrue());
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AI run 실패 — FAILED 저장 후 terminal error(AI_RUN_FAILED)를 보낸다")
    void aiRunFailure() throws Exception {
        Paper paper = givenCompletedPaper();
        doAnswer(invocation -> {
            AiStreamListener listener = invocation.getArgument(1);
            Thread.startVirtualThread(() -> {
                listener.onRunStarted();
                listener.onDelta("일부");
                listener.onRunFailed("upstream raw error");
            });
            return (com.ymc.chat.service.port.AiRunHandle) () -> { };
        }).when(aiAgentStreamPort).stream(any(), any(AiStreamListener.class));

        MvcResult result = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "질문")));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                chatMessageRepository.findAll().stream()
                        .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT)
                        .findFirst().orElseThrow().getStatus())
                .isEqualTo(ChatMessageStatus.FAILED));

        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("event:error");
        assertThat(stream).contains("\"code\":\"AI_RUN_FAILED\"");
        assertThat(stream).doesNotContain("upstream raw error"); // raw error 미노출 (계약)

        // partial delta는 저장되지 않는다
        ChatMessage assistant = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT).findFirst().orElseThrow();
        assertThat(assistant.getContent()).isNull();
    }

    @Test
    @DisplayName("인증 없으면 401")
    void unauthorized() throws Exception {
        Paper paper = givenCompletedPaper();
        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("없는 논문이면 404 PAPER_NOT_FOUND")
    void paperNotFound() throws Exception {
        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", UUID.randomUUID())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_FOUND"));
    }

    @Test
    @DisplayName("남의 논문이면 403 FORBIDDEN")
    void foreignPaper() throws Exception {
        Paper others = paperRepository.save(com.ymc.paper.domain.Paper.register(
                UUID.randomUUID(), "others.pdf", Instant.now()));
        paperTransitions.markUploaded(others.getId());
        paperTransitions.markProcessing(others.getId());
        paperTransitions.markParsed(others.getId(), PaperStatus.COMPLETED, null);

        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", others.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("파싱 미완료 논문이면 409 PAPER_NOT_READY")
    void paperNotReady() throws Exception {
        Paper processing = givenProcessingPaper("not-ready.pdf");
        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", processing.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, UUID.randomUUID(), "질문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAPER_NOT_READY"));
    }

    @Test
    @DisplayName("남의 세션이면 404 CHAT_SESSION_NOT_FOUND")
    void foreignSession() throws Exception {
        Paper paper = givenCompletedPaper();
        var foreign = chatSessionRepository.save(com.ymc.chat.domain.ChatSession.open(
                UUID.randomUUID(), paper.getId(), Instant.now()));

        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(foreign.getId(), UUID.randomUUID(), "질문")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHAT_SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("같은 clientMessageId·같은 content 재전송이면 409 DUPLICATE_MESSAGE + 기존 식별자")
    void duplicateResend() throws Exception {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        ChatStartResult first = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문");

        mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, clientMessageId, "같은 질문")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MESSAGE"))
                .andExpect(jsonPath("$.sessionId").value(first.sessionId().toString()))
                .andExpect(jsonPath("$.messageId").value(first.assistantMessageId().toString()))
                .andExpect(jsonPath("$.status").value("GENERATING"));

        assertThat(chatMessageRepository.count()).isEqualTo(2); // 새 행 없음
    }

    @Test
    @DisplayName("세션 연속성 — 완료된 스트림의 sessionId로 후속 질문이 같은 세션에 쌓인다")
    void sessionContinuity() throws Exception {
        Paper paper = givenCompletedPaper();
        UUID firstClientMessageId = UUID.randomUUID();
        MvcResult first = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "clientMessageId", firstClientMessageId.toString(),
                "content", "첫 질문")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                chatMessageRepository.findByClientMessageIdAndRole(
                        firstClientMessageId, ChatMessageRole.ASSISTANT)
                        .orElseThrow().getStatus())
                .isEqualTo(ChatMessageStatus.COMPLETED));
        mockMvc.perform(asyncDispatch(first));
        UUID sessionId = chatSessionRepository.findAll().get(0).getId();

        MvcResult second = startStream(paper, objectMapper.writeValueAsString(Map.of(
                "sessionId", sessionId.toString(),
                "clientMessageId", UUID.randomUUID().toString(),
                "content", "후속 질문")));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(
                () -> assertThat(chatMessageRepository.count()).isEqualTo(4));
        mockMvc.perform(asyncDispatch(second));

        assertThat(chatSessionRepository.count()).isEqualTo(1);
    }
}
```

- [ ] **Step 5: 테스트 실패 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.api.ChatMessageStreamIntegrationTest"
```

Expected: 컴파일 실패 — `ChatController` 없음 (Step 1·2를 이미 했다면 전체 PASS가 목표)

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.api.ChatMessageStreamIntegrationTest"
```

Expected: 10 tests PASS

- [ ] **Step 7: 커밋**

```bash
git add be/src/main/java/com/ymc/chat/api be/src/test/java/com/ymc/chat/api be/src/test/java/com/ymc/support/IntegrationTest.java
git commit -m "[YMC-256] feat(chat): 채팅 SSE 엔드포인트와 통합 테스트"
```

---

### Task 10: 전체 검증 + PR

- [ ] **Step 1: 전체 테스트**

```bash
cd be && ./gradlew test
```

Expected: BUILD SUCCESSFUL — 기존 paper·user 테스트 포함 전부 PASS. 실패가 있으면 고치기 전에 원인을 보고한다.

- [ ] **Step 2: 수동 확인 (선택, 로컬 환경이 떠 있을 때)**

```bash
cd ../../infra/local && docker compose up localstack postgres -d
cd ../../app/be && ./gradlew bootRun
```

로그인 후 COMPLETED 논문에 대해 curl로 스트림 확인 (fake delta가 흘러야 함):

```bash
curl -N -X POST "http://localhost:8080/api/papers/{paperId}/chat/messages" \
  -H "Authorization: Bearer {accessToken}" -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"clientMessageId":"'"$(uuidgen | tr A-Z a-z)"'","content":"핵심 기여가 뭐야?"}'
```

- [ ] **Step 3: 푸시 + PR 생성**

```bash
git push -u origin YMC-256-chat-api-persistence
gh pr create --title "[YMC-256] feat(chat): 채팅 요청 API·대화 영속화 + fake 스트림" --body "..."
```

PR 본문은 `.github/PULL_REQUEST_TEMPLATE.md`의 Summary(관련 이슈 YMC-256, 설계 문서 링크)/Changes/Verification/Review Focus/Notes 형식. Review Focus에 비관적 잠금(설계 §3)과 멱등 분기(rejectDuplicate)를 명시한다. **Generated with 푸터·Co-Authored-By 금지.**

- [ ] **Step 4: Jira 전환**

YMC-256을 진행 중으로 (커밋 키로 자동 전환 안 됐으면 수동). PR 링크를 티켓에 남긴다.

---

## 범위 밖 (여기서 하지 않는 것)

- WebClient 어댑터·idle/deadline timeout·heartbeat·누적 상한 → **YMC-257** (ChatStreamService에 추가된다)
- FE 파서·UI → **YMC-258**
- 세션 히스토리 조회·삭제 API → **YMC-260**
- 사용량 제한(429 CHAT_USAGE_LIMIT_EXCEEDED) → follow-up (설계 §7)
