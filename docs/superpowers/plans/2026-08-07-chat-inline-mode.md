# 채팅 인라인 모드 전환 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FE에서 호출하는 채팅을 simple-agent에서 inline-pdf-agent로 전환한다 — 블록 앵커 selection 전송·저장·이력 표시 포함.

**Architecture:** 계약(project-docs) → BE(포트·어댑터·저장) → FE(앵커 변환·배관·칩) 순서. AI 서버는 변경 없음. 스펙: `docs/superpowers/specs/2026-08-07-chat-inline-mode-design.md`.

**Tech Stack:** OpenAPI 3.2 / Spring Boot(WebClient SSE, JPA+Hibernate 6, Testcontainers PG) / React+TS(vitest, jsdom)

## Global Constraints

- **티켓 번호**: 실행 시작 전 Jira에서 이 작업의 YMC 티켓 번호를 확정하고, 아래 모든 `YMC-312`를 치환한다. 브랜치 생성 전 `git ls-remote origin "*YMC-312*"`로 기존 원격 브랜치가 없는지 확인한다.
- **커밋 형식**: `[YMC-312] type(scope): subject`. 커밋 메시지·PR 본문에 Co-Authored-By나 "Generated with Claude Code" 문구를 넣지 않는다.
- **브랜치**: 두 repo 모두 기본 브랜치는 `main`. 시작점을 명시해 생성한다 — `git fetch origin && git switch -c YMC-312-... origin/main`.
- **repo 경계**: `project-docs/`(Task 1), `app/`(Task 2~6). `ai/`는 절대 수정하지 않는다.
- **네이밍**: FE↔BE는 camelCase(`blockId`), BE↔AI는 snake_case(`block_id`). 변환은 BE 어댑터 경계에서만.
- **테스트 명령**: BE는 `app/be`에서 `./gradlew test`, FE는 `app/fe`에서 `npm test`와 `npm run typecheck`.
- **주석 규칙**: 핵심 1~2줄만. 티켓 번호·스펙 문서 인용 괄호를 주석에 넣지 않는다.
- **기존 세션 정책(결정)**: simple-agent 시절 세션에도 새 질문을 허용한다. AI 체크포인트 키 구조(simple: `thread_id`, inline: `[paper_id, thread_id]`)가 달라 그 세션의 과거 AI 맥락 없이 새 대화로 답변하는데, dev 단계라 이를 수용하고 코드로 막지 않는다 — Task 7 보고에 알려진 제약으로 기록한다.
- **운영 DB**: prod 프로필은 `ddl-auto: validate`다 — selection 컬럼을 `be/docs/db/chat.sql`에 반영하고(Task 2), prod 배포 전 alter 적용이 선행돼야 한다(Task 7).
- **context7 확인 지점**: Task 2의 `@JdbcTypeCode(SqlTypes.JSON)` 사용 전 Hibernate 6(프로젝트가 쓰는 Spring Boot 버전의 Hibernate) 공식 문서에서 record→JSON 매핑을 확인한다.

---

### Task 1: FE↔BE 계약 확장 (project-docs)

**Files:**
- Modify: `project-docs/contracts/frontend-backend/openapi.yaml`
- Modify: `project-docs/contracts/backend-ai/sse/simple-agent-run-stream.yml` (status만)
- Modify: `project-docs/contracts/README.md`

**Interfaces:**
- Produces: `ChatSelection`/`ChatSelectionAnchor` 스키마(camelCase `blockId`, `offset` optional·null 허용), `ChatMessageStreamRequest.selection`, `ChatMessageItem.selection`, `ChatStreamErrorDetail`에 `SELECTION_*` 4개 코드(retryable=false). Task 2~6의 DTO·타입이 이 shape을 그대로 따른다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs && git ls-remote origin "*YMC-312*" && git fetch origin && git switch -c YMC-312-inline-chat-contract origin/main
```

- [ ] **Step 2: components.schemas에 ChatSelection·ChatSelectionAnchor 추가**

`ChatMessageStreamRequest:` 스키마 정의(984행 부근) 위에 추가:

```yaml
    ChatSelection:
      type: object
      description: |
        논문 본문에서 선택한 영역의 블록 앵커. start/end는 getPaperContent blocks의
        globalOrder 순서를 따르며 start가 end보다 앞이거나 같다.
        블록 존재·순서·크기 검증은 AI가 수행하고 BE는 shape만 검증한다.
      required: [start, end]
      additionalProperties: false
      properties:
        start:
          $ref: "#/components/schemas/ChatSelectionAnchor"
        end:
          $ref: "#/components/schemas/ChatSelectionAnchor"

    ChatSelectionAnchor:
      type: object
      required: [blockId]
      additionalProperties: false
      properties:
        blockId:
          type: string
          minLength: 1
          description: getPaperContent 응답 blocks[].blockId와 같은 본문 블록 식별자.
        offset:
          type: [integer, "null"]
          minimum: 0
          description: |
            text 블록 내 UTF-16 code unit offset — start는 inclusive, end는 exclusive.
            현재 FE는 보내지 않는다(블록 단위 선택). 추후 확장을 위해 shape만 열어둔다.
```

- [ ] **Step 3: ChatMessageStreamRequest에 selection 추가**

`content:` property 뒤에 추가 (required는 그대로 `[clientMessageId, content]`):

```yaml
        selection:
          oneOf:
            - $ref: "#/components/schemas/ChatSelection"
            - type: "null"
          description: |
            선택 영역의 블록 앵커. 생략하거나 null이면 논문 전체 기반으로 답변한다.
            BE는 snake_case로 변환해 BE↔AI 요청의 `selection`으로 그대로 전달하고,
            user message에 함께 저장한다.
```

같은 스키마의 `clientMessageId` description 끝에 한 문장을 추가한다: `같은 clientMessageId로 content 또는 selection이 다른 요청이 오면 CLIENT_MESSAGE_ID_CONFLICT다.`

createChatMessageStream의 requestBody example(465~468행)에 selection을 추가:

```yaml
            example:
              sessionId: 4f0d5ca6-6f79-4f30-a970-d07e6527d076
              clientMessageId: 0bfbf67a-31d0-4585-88a5-0ddac640d57e
              content: 이 부분이 왜 그런거야?
              selection:
                start: { blockId: p0002-b0000 }
                end: { blockId: p0002-b0003 }
```

- [ ] **Step 4: ChatMessageItem에 selection 추가**

`required: [messageId, role, content, status, seq, createdAt, selection]`으로 바꾸고 property 추가:

```yaml
        selection:
          oneOf:
            - $ref: "#/components/schemas/ChatSelection"
            - type: "null"
          description: user 메시지가 선택 영역과 함께 전송된 경우의 블록 앵커. assistant와 선택 없는 질문은 null.
```

- [ ] **Step 5: x-upstream-event-mapping·limitations를 inline-pdf-agent로 교체**

392~394행:

```yaml
      x-upstream-event-mapping:
        contract: ../backend-ai/sse/inline-pdf-agent-run-stream.yml
        endpoint: "POST /api/v1/agents/inline-pdf-agent/runs/stream"
```

request 매핑(396~399행)을 다음으로 교체:

```yaml
        request:
          path.paperId: BE 권한·session association 검증에 사용하고 AI body.paper_id에 문자열로 전달한다.
          body.sessionId: 생략 시 새로 생성한 값을 포함해 AI body.thread_id에 UUID 문자열 그대로 전달한다.
          body.content: AI body.message에 UTF-8 질문 문자열 그대로 전달한다.
          body.selection: camelCase를 snake_case(block_id)로 변환해 AI body.selection에 전달한다. null이면 null.
          body.clientMessageId: BE 멱등 처리에만 사용하고 AI에는 전달하지 않는다.
```

events의 run.failed(406행)를 교체:

```yaml
          run.failed: FAILED DB commit 뒤 FE error로 변환한다 — error.code가 SELECTION_*이면 코드를 그대로 전달(retryable=false), 그 외(PAPER_*, INTERNAL_SERVER_ERROR)는 AI_RUN_FAILED로 변환한다.
```

x-contract-limitations(438~442행)에서 마지막 두 줄(simple-agent에 paper_id가 없다는 항목과 contract 확장 필요 항목)을 삭제하고 추가:

```yaml
        - selection의 블록 존재·순서·offset·크기 검증은 AI가 수행한다. 위반은 스트림 시작 후이므로 terminal error SSE event로 온다.
```

10~12행 부근의 참조 주석(`BE↔AI 채팅 스트림: .../simple-agent-run-stream.yml`)도 `inline-pdf-agent-run-stream.yml`로 바꾼다.

- [ ] **Step 6: ChatStreamErrorDetail에 SELECTION_* 4개 코드 추가**

enum(1254~1260행)에 추가:

```yaml
            - SELECTION_BLOCK_NOT_FOUND
            - SELECTION_RANGE_INVALID
            - SELECTION_OFFSET_INVALID
            - SELECTION_TOO_LARGE
```

oneOf(1266행~)에 기존 패턴대로 추가:

```yaml
        - title: 선택 블록 없음
          properties:
            code:
              const: SELECTION_BLOCK_NOT_FOUND
            retryable:
              const: false
        - title: 선택 범위 뒤집힘
          properties:
            code:
              const: SELECTION_RANGE_INVALID
            retryable:
              const: false
        - title: 선택 offset 오류
          properties:
            code:
              const: SELECTION_OFFSET_INVALID
            retryable:
              const: false
        - title: 선택 영역 크기 초과
          properties:
            code:
              const: SELECTION_TOO_LARGE
            retryable:
              const: false
```

description(1245~1248행)에 한 줄 추가: `SELECTION_*는 AI의 선택 영역 검증 실패다 — 같은 selection 재시도는 의미가 없어 retryable=false다.`

- [ ] **Step 7: simple-agent 계약 superseded 처리 + README 트리 갱신**

`backend-ai/sse/simple-agent-run-stream.yml`의 3행을 `status: superseded`로 바꾸고 그 아래 한 줄 추가:

```yaml
superseded_by: inline-pdf-agent-run-stream
```

`contracts/README.md`의 트리에서 sse/ 아래를 실제 4개 파일로 갱신:

```
    └── sse/
        ├── simple-agent-run-stream.yml       (superseded — inline-pdf-agent로 대체)
        ├── base-pdf-agent-run-stream.yml
        ├── inline-pdf-agent-run-stream.yml   BE ↔ AI 채팅 SSE endpoint (현행)
        └── document-parser-parse-stream.yml
```

- [ ] **Step 8: YAML 유효성 확인**

```bash
python3 -c "import yaml,glob; [yaml.safe_load(open(f)) for f in ['project-docs/contracts/frontend-backend/openapi.yaml','project-docs/contracts/backend-ai/sse/simple-agent-run-stream.yml']]; print('OK')"
```

Expected: `OK`

- [ ] **Step 9: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs && git add contracts && git commit -m "[YMC-312] docs(contracts): 채팅 selection 블록 앵커 추가·upstream을 inline-pdf-agent로 전환"
```

---

### Task 2: BE — ChatSelection 도메인·저장·이력 응답

**Files:**
- Create: `app/be/src/main/java/com/ymc/chat/domain/ChatSelection.java`
- Create: `app/be/src/main/java/com/ymc/chat/api/dto/ChatSelectionDto.java`
- Modify: `app/be/src/main/java/com/ymc/chat/domain/ChatMessage.java`
- Modify: `app/be/src/main/java/com/ymc/chat/service/ChatCommandService.java` (start 시그니처)
- Modify: `app/be/src/main/java/com/ymc/chat/api/dto/ChatMessageStreamRequest.java`
- Modify: `app/be/src/main/java/com/ymc/chat/api/dto/ChatMessageItemResponse.java`
- Modify: `app/be/src/main/java/com/ymc/chat/api/ChatController.java`
- Modify: `app/be/docs/db/chat.sql`
- Test: `app/be/src/test/java/com/ymc/chat/api/ChatSelectionPersistenceTest.java` (신규)

**Interfaces:**
- Produces: `ChatSelection(Anchor start, Anchor end)` + `ChatSelection.Anchor(String blockId, Integer offset)` (domain), `ChatSelectionDto.toDomain()`/`ChatSelectionDto.from(ChatSelection)` (api), `ChatCommandService.start(UUID ownerId, UUID paperId, UUID sessionIdOrNull, UUID clientMessageId, String content, ChatSelection selection)`, `ChatMessage.userMessage(session, clientMessageId, content, selection, seq, now)`, `ChatMessage.getSelection()`. Task 3이 `ChatSelection`을 AI 요청에 실어 보낸다.
- 참고: local·dev는 `ddl-auto: update`라 컬럼이 기동 시 자동 추가되지만, **prod는 `validate`라 `be/docs/db/chat.sql` 갱신과 배포 전 alter 적용이 필수**다 (Task 7 배포 절차 참고).

- [ ] **Step 1: 브랜치 확인 (app repo — BE·FE 공용 브랜치)**

`YMC-312-inline-chat` 브랜치는 스펙·플랜 문서 이동 커밋과 함께 이미 origin/main에서 생성돼 있다. 없을 때만 새로 만든다:

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git switch YMC-312-inline-chat 2>/dev/null || (git fetch origin && git switch -c YMC-312-inline-chat origin/main)
```

- [ ] **Step 2: context7로 Hibernate 6 JSON 매핑 확인**

`@JdbcTypeCode(SqlTypes.JSON)`를 record 타입 필드에 적용하는 방법과 Jackson 요구사항을 프로젝트 Hibernate 버전 문서로 확인한다. record 직접 매핑이 버전상 불가하면 String 필드 + ObjectMapper 변환으로 대체하고 아래 코드를 조정한다.

- [ ] **Step 3: 실패하는 테스트 작성**

`app/be/src/test/java/com/ymc/chat/api/ChatSelectionPersistenceTest.java`:

```java
package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatSelection;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

/** selection 저장·이력 응답 round-trip. AI 스트림은 다루지 않는다 — 시작 트랜잭션까지만. */
class ChatSelectionPersistenceTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("selection-" + UUID.randomUUID() + ".pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    @Test
    @DisplayName("selection이 user 메시지에 저장되고 이력 응답에 camelCase로 포함된다")
    void selectionRoundTrip() throws Exception {
        Paper paper = givenCompletedPaper();
        ChatSelection selection = new ChatSelection(
                new ChatSelection.Anchor("p0002-b0000", null),
                new ChatSelection.Anchor("p0002-b0003", null));

        var started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "이 부분 설명해줘", selection);

        ChatMessage user = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.USER).findFirst().orElseThrow();
        assertThat(user.getSelection()).isEqualTo(selection);

        String body = mockMvc.perform(
                        get("/api/papers/{paperId}/chat/sessions/{sessionId}/messages",
                                paper.getId(), started.sessionId()).with(userJwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"blockId\":\"p0002-b0000\"");
        assertThat(body).contains("\"selection\":null"); // assistant 행은 null
    }

    @Test
    @DisplayName("selection 없는 질문은 selection null로 저장된다")
    void nullSelection() {
        Paper paper = givenCompletedPaper();
        chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "전체 요약해줘", null);
        ChatMessage user = chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.USER).findFirst().orElseThrow();
        assertThat(user.getSelection()).isNull();
    }

    @Test
    @DisplayName("같은 clientMessageId·content라도 selection이 다르면 CLIENT_MESSAGE_ID_CONFLICT다")
    void differentSelectionConflicts() {
        Paper paper = givenCompletedPaper();
        UUID clientMessageId = UUID.randomUUID();
        ChatSelection first = new ChatSelection(
                new ChatSelection.Anchor("p0001-b0000", null),
                new ChatSelection.Anchor("p0001-b0000", null));
        chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "이 부분 설명해줘", first);

        ChatSelection other = new ChatSelection(
                new ChatSelection.Anchor("p0001-b0003", null),
                new ChatSelection.Anchor("p0001-b0003", null));
        assertThatThrownBy(() -> chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, clientMessageId, "이 부분 설명해줘", other))
                .isInstanceOf(com.ymc.common.error.ApiException.class)
                .hasMessageContaining("clientMessageId");
    }
}
```

주의: `IntegrationTest` 베이스가 제공하는 헬퍼 이름(`givenProcessingPaper`, `paperTransitions`, `reload`, `chatMessageRepository`, `mockMvc`, `userJwt`, `TEST_USER_ID`)은 `AiRelayIntegrationTest.java`와 동일 — 컴파일 오류가 나면 베이스 클래스를 열어 실제 이름에 맞춘다.

- [ ] **Step 4: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test --tests 'com.ymc.chat.api.ChatSelectionPersistenceTest'
```

Expected: 컴파일 실패 (`ChatSelection` 없음)

- [ ] **Step 5: domain — ChatSelection 값 객체**

`app/be/src/main/java/com/ymc/chat/domain/ChatSelection.java`:

```java
package com.ymc.chat.domain;

/** 논문 본문 선택 영역의 블록 앵커. chat_message.selection에 JSON으로 저장된다. */
public record ChatSelection(Anchor start, Anchor end) {

    /** offset은 text 블록 내 UTF-16 code unit — 현재 FE는 보내지 않아 null이다. */
    public record Anchor(String blockId, Integer offset) {
    }
}
```

- [ ] **Step 6: ChatMessage에 selection 컬럼 추가**

import 추가:

```java
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
```

필드 추가 (`content` 필드 아래):

```java
    /** user 메시지의 선택 영역 앵커. assistant와 선택 없는 질문은 null. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection", columnDefinition = "jsonb", updatable = false)
    private ChatSelection selection;
```

private 생성자에 `ChatSelection selection` 파라미터를 추가하고(`content` 뒤) `this.selection = selection;` 대입. 팩토리 수정:

```java
    /** 사용자 질문. 저장 즉시 COMPLETED다. */
    public static ChatMessage userMessage(ChatSession session, UUID clientMessageId,
            String content, ChatSelection selection, int seq, Instant now) {
        Objects.requireNonNull(content, "content");
        return new ChatMessage(session, ChatMessageRole.USER, content, selection,
                ChatMessageStatus.COMPLETED, clientMessageId, seq, now);
    }

    /** 생성 중인 assistant 답변 자리. content는 완료 시 조건부 UPDATE로 채운다. */
    public static ChatMessage assistantGenerating(
            ChatSession session, UUID clientMessageId, int seq, Instant now) {
        return new ChatMessage(session, ChatMessageRole.ASSISTANT, null, null,
                ChatMessageStatus.GENERATING, clientMessageId, seq, now);
    }
```

`be/docs/db/chat.sql`의 chat_message 정의에서 `content` 컬럼 아래에 추가 (이 파일은 prod 반영 산출물 — 엔티티와 함께 갱신 필수):

```sql
    content           text,
    -- user 메시지의 선택 영역 블록 앵커(계약 ChatSelection, camelCase JSON). assistant·선택 없는 질문은 null.
    selection         jsonb,
```

파일 하단에 기존 DB 반영용 구문도 주석으로 남긴다:

```sql
-- 이미 생성된 DB에는 아래를 적용한다 (selection 컬럼 추가, nullable이라 구버전 앱과도 호환):
-- alter table chat_message add column selection jsonb;
```

- [ ] **Step 7: ChatCommandService.start 시그니처 확장**

```java
    @Transactional
    public ChatStartResult start(UUID ownerId, UUID paperId, UUID sessionIdOrNull,
            UUID clientMessageId, String content, ChatSelection selection) {
```

저장부의 `ChatMessage.userMessage(session, clientMessageId, content, userSeq, now)`를 `ChatMessage.userMessage(session, clientMessageId, content, selection, userSeq, now)`로. import에 `com.ymc.chat.domain.ChatSelection`, `java.util.Objects` 추가.

**멱등 판정에 selection 포함** — `rejectDuplicate` 시그니처에 `ChatSelection selection`을 추가하고(호출부 2곳: start 초입, `requiresNewTx` 블록), content 비교 분기를 다음으로 교체한다:

```java
        if (!existingUser.get().getContent().equals(content)
                || !Objects.equals(existingUser.get().getSelection(), selection)) {
            throw new ApiException(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT,
                    "clientMessageId가 다른 요청에 이미 사용되었습니다.");
        }
```

같은 clientMessageId·content라도 selection이 다르면 의미가 다른 요청이므로 멱등이 아니라 충돌이다.

- [ ] **Step 8: api DTO — ChatSelectionDto·요청·응답·컨트롤러**

`app/be/src/main/java/com/ymc/chat/api/dto/ChatSelectionDto.java`:

```java
// chat/api/dto/ChatSelectionDto.java
package com.ymc.chat.api.dto;

import com.ymc.chat.domain.ChatSelection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 계약의 `ChatSelection`. shape 검증만 한다 — 블록 존재·순서 검증은 AI 담당. */
public record ChatSelectionDto(
        @NotNull @Valid AnchorDto start,
        @NotNull @Valid AnchorDto end) {

    public record AnchorDto(@NotBlank String blockId, @PositiveOrZero Integer offset) {
    }

    public ChatSelection toDomain() {
        return new ChatSelection(
                new ChatSelection.Anchor(start.blockId(), start.offset()),
                new ChatSelection.Anchor(end.blockId(), end.offset()));
    }

    public static ChatSelectionDto from(ChatSelection selection) {
        if (selection == null) {
            return null;
        }
        return new ChatSelectionDto(
                new AnchorDto(selection.start().blockId(), selection.start().offset()),
                new AnchorDto(selection.end().blockId(), selection.end().offset()));
    }
}
```

`ChatMessageStreamRequest.java`:

```java
// chat/api/dto/ChatMessageStreamRequest.java
package com.ymc.chat.api.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 계약의 `ChatMessageStreamRequest`. sessionId는 첫 질문에서, selection은 전체 질문에서 null이다. */
public record ChatMessageStreamRequest(
        UUID sessionId,
        @NotNull UUID clientMessageId,
        @NotBlank String content,
        @Valid ChatSelectionDto selection) {
}
```

`ChatMessageItemResponse.java`:

```java
/** 계약 ChatMessageItem. content는 GENERATING·FAILED assistant에서 null이다. */
public record ChatMessageItemResponse(
        UUID messageId, ChatMessageRole role, String content,
        ChatMessageStatus status, int seq, Instant createdAt, ChatSelectionDto selection) {

    public static ChatMessageItemResponse from(ChatMessage message) {
        return new ChatMessageItemResponse(
                message.getId(), message.getRole(), message.getContent(),
                message.getStatus(), message.getSeq(), message.getCreatedAt(),
                ChatSelectionDto.from(message.getSelection()));
    }
}
```

`ChatController.createMessageStream`:

```java
        UUID ownerId = UUID.fromString(jwt.getSubject());
        var selection = request.selection() == null ? null : request.selection().toDomain();
        ChatStartResult started = chatCommandService.start(
                ownerId, paperId, request.sessionId(), request.clientMessageId(),
                request.content(), selection);
```

- [ ] **Step 9: 테스트 통과 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test --tests 'com.ymc.chat.api.ChatSelectionPersistenceTest'
```

Expected: PASS. (record JSON 매핑이 Hibernate에서 실패하면 Step 2에서 확인한 대안으로 조정.)

- [ ] **Step 10: 전체 BE 테스트 — 기존 start 호출부 컴파일 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test
```

Expected: 기존 `chatCommandService.start(...)` 호출부가 컴파일 오류 — `AiRelayIntegrationTest.feDisconnectStillPersists`(278행 부근)와 `ChatMessageStreamIntegrationTest`(230행 부근)의 start 호출에 마지막 인자 `, null`을 추가해서 통과시킨다. 그 외 전부 PASS.

- [ ] **Step 11: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add be && git commit -m "[YMC-312] feat(be): 채팅 selection 블록 앵커 저장·이력 응답 추가"
```

---

### Task 3: BE — AI 경로를 inline-pdf-agent로 전환

**Files:**
- Modify: `app/be/src/main/java/com/ymc/chat/service/port/AiRunRequest.java`
- Modify: `app/be/src/main/java/com/ymc/chat/service/port/AiStreamListener.java`
- Modify: `app/be/src/main/java/com/ymc/chat/infra/ai/AiAgentWebClientAdapter.java`
- Modify: `app/be/src/main/java/com/ymc/chat/service/ChatStreamService.java`
- Modify: `app/be/src/main/java/com/ymc/chat/api/ChatController.java`
- Test: `app/be/src/test/java/com/ymc/support/FakeAiSseServer.java`, `app/be/src/test/java/com/ymc/chat/infra/AiAgentWebClientAdapterTest.java`, `app/be/src/test/java/com/ymc/chat/api/AiRelayIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2의 `ChatSelection`.
- Produces: `AiRunRequest(String threadId, String paperId, String message, ChatSelection selection)`, `AiStreamListener.onRunFailed(String code, String message)`, `ChatStreamService.begin(SseEmitter emitter, ChatStartResult started, String userContent, ChatSelection selection)`. wire: `POST /api/v1/agents/inline-pdf-agent/runs/stream`, body `{thread_id, paper_id, message, selection:{start:{block_id,offset},end:{block_id,offset}}|null}`.

- [ ] **Step 1: 어댑터 테스트를 새 계약으로 수정 (실패 상태로)**

`FakeAiSseServer`를 새 계약(모든 이벤트에 thread_id·paper_id 포함, error는 객체)에 맞춰 고친다. 통합 테스트는 서버가 만드는 sessionId를 미리 알 수 없으므로, 프레임에는 placeholder를 쓰고 서버가 요청 body의 실제 값으로 치환해 응답하게 한다:

```java
    // --- 계약 payload 헬퍼 (data.type == event 이름, 식별자는 요청 값으로 서빙 시 치환) ---
    public static Frame runStarted() {
        return Frame.of("run.started",
                "{\"type\":\"run.started\",\"thread_id\":\"{{thread_id}}\",\"paper_id\":\"{{paper_id}}\"}");
    }

    public static Frame delta(String delta) {
        return Frame.of("message.delta",
                "{\"type\":\"message.delta\",\"thread_id\":\"{{thread_id}}\",\"paper_id\":\"{{paper_id}}\",\"delta\":\"" + delta + "\"}");
    }

    public static Frame messageCompleted(String message) {
        return Frame.of("message.completed",
                "{\"type\":\"message.completed\",\"thread_id\":\"{{thread_id}}\",\"paper_id\":\"{{paper_id}}\",\"message\":\"" + message + "\"}");
    }

    public static Frame runCompleted() {
        return Frame.of("run.completed",
                "{\"type\":\"run.completed\",\"thread_id\":\"{{thread_id}}\",\"paper_id\":\"{{paper_id}}\"}");
    }

    public static Frame runFailed(String code, String message) {
        return Frame.of("run.failed",
                "{\"type\":\"run.failed\",\"thread_id\":\"{{thread_id}}\",\"paper_id\":\"{{paper_id}}\","
                        + "\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
```

핸들러의 프레임 전송부를 치환 로직 포함으로 교체 (body 캡처 직후 식별자를 뽑는다):

```java
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(body);
            String threadId = jsonField(body, "thread_id");
            String paperId = jsonField(body, "paper_id");
```

```java
                for (Frame frame : script.frames()) {
                    sleep(frame.delayMillis());
                    String data = frame.dataJson()
                            .replace("{{thread_id}}", threadId)
                            .replace("{{paper_id}}", paperId);
                    out.write(("event: " + frame.event() + "\ndata: " + data + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
```

클래스 하단에 헬퍼 추가:

```java
    private static String jsonField(String json, String name) {
        var matcher = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
```

클래스 javadoc의 계약 파일명도 `inline-pdf-agent-run-stream.yml`로 바꾼다. 기존 헬퍼 호출부는 전부 기계적으로 치환한다 — `runStarted("t")` → `runStarted()`, `delta("t", "안녕")` → `delta("안녕")`, `messageCompleted("t", "안녕하세요")` → `messageCompleted("안녕하세요")`, `runCompleted("t")` → `runCompleted()`, `runFailed("t", "raw")` → `runFailed("INTERNAL_SERVER_ERROR", "raw")`. `AiRelayIntegrationTest`의 인라인 `Frame` 리터럴 3곳(deadlineExceededWhileStreaming, heartbeatDuringSilence, feDisconnectStillPersists)도 data JSON에 `"thread_id":"{{thread_id}}","paper_id":"{{paper_id}}"`를 넣는 형태로 수정한다.

`AiAgentWebClientAdapterTest`:
- recorder의 `onRunFailed`를 `public void onRunFailed(String code, String message) { events.add("run-failed:" + code + ":" + message); }`로 교체.
- `AiRunRequest` 생성부를 4-인자로 교체 — 예: `new AiRunRequest("t-1", "paper-1", "질문", null)`.
- `successSequenceAndSnakeCaseBody` 테스트에 요청 바디 assert 추가:

```java
        assertThat(aiServer.lastRequestBody()).contains("\"thread_id\":\"t-1\"");
        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"paper-1\"");
        assertThat(aiServer.lastRequestBody()).contains("\"selection\":null");
```

- selection 직렬화 테스트 추가:

```java
    @Test
    @DisplayName("selection은 snake_case(block_id)로 직렬화된다")
    void selectionSnakeCaseBody() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted(),
                FakeAiSseServer.messageCompleted("답"),
                FakeAiSseServer.runCompleted()));
        var selection = new com.ymc.chat.domain.ChatSelection(
                new com.ymc.chat.domain.ChatSelection.Anchor("p0001-b0002", null),
                new com.ymc.chat.domain.ChatSelection.Anchor("p0001-b0005", null));

        adapter(WAIT).stream(new AiRunRequest("t-2", "paper-2", "이 부분?", selection), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-completed"));
        assertThat(aiServer.lastRequestBody()).contains("\"block_id\":\"p0001-b0002\"");
        assertThat(aiServer.lastRequestBody()).contains("\"block_id\":\"p0001-b0005\"");
        assertThat(aiServer.lastRequestBody()).doesNotContain("blockId");
    }
```

다른 리스너 구현체도 새 시그니처로 교체한다:
- `FakeAiAgentStreamAdapterTest`의 recorder: `public void onRunFailed(String code, String message) { events.add("run-failed:" + code + ":" + message); }`
- `ChatMessageStreamIntegrationTest`(122행 부근)의 mock 콜백: `listener.onRunFailed("INTERNAL_SERVER_ERROR", "upstream raw error");`

- run.failed 파싱 테스트가 이미 있으면 기대값을 `run-failed:INTERNAL_SERVER_ERROR:<메시지>` 형식으로 수정하고, 없으면 추가한다:

```java
    @Test
    @DisplayName("run.failed의 error 객체에서 code·message를 파싱한다")
    void runFailedObjectError() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted(),
                FakeAiSseServer.runFailed("SELECTION_TOO_LARGE", "Selection exceeds limits.")));

        adapter(WAIT).stream(new AiRunRequest("t-3", "paper-3", "질문", null), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("run-failed:")));
        assertThat(events).contains("run-failed:SELECTION_TOO_LARGE:Selection exceeds limits.");
    }
```

- 식별자 불일치 테스트 추가 — placeholder 대신 다른 값을 박은 프레임으로 검증한다:

```java
    @Test
    @DisplayName("이벤트의 thread_id가 요청과 다르면 transport error로 종결하고 콜백하지 않는다")
    void identifierMismatch() {
        aiServer.enqueue(Script.of(FakeAiSseServer.Frame.of("run.started",
                "{\"type\":\"run.started\",\"thread_id\":\"다른-thread\",\"paper_id\":\"{{paper_id}}\"}")));

        adapter(WAIT).stream(new AiRunRequest("t-4", "paper-4", "질문", null), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
        assertThat(events).doesNotContain("started");
    }
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test --tests 'com.ymc.chat.infra.AiAgentWebClientAdapterTest'
```

Expected: 컴파일 실패 (AiRunRequest 4-인자 생성자 없음)

- [ ] **Step 3: 포트·리스너 수정**

`AiRunRequest.java`:

```java
package com.ymc.chat.service.port;

import com.ymc.chat.domain.ChatSelection;

/** BE↔AI 계약(inline-pdf-agent-run-stream.yml)의 request body. thread_id = sessionId 문자열. */
public record AiRunRequest(String threadId, String paperId, String message, ChatSelection selection) {
}
```

`AiStreamListener.java`의 onRunFailed 교체:

```java
    /** AI가 run.failed를 보냄. code는 계약의 안정된 오류 코드, message는 raw라 FE에 그대로 노출하지 않는다. */
    void onRunFailed(String code, String message);
```

- [ ] **Step 4: 어댑터 수정**

`AiAgentWebClientAdapter`:

```java
    static final String STREAM_PATH = "/api/v1/agents/inline-pdf-agent/runs/stream";
```

```java
    /** wire 형식은 snake_case (계약) — 코드 컨벤션과 경계에서 변환한다. */
    record StreamRequestBody(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("paper_id") String paperId,
            String message,
            SelectionBody selection) {

        record SelectionBody(AnchorBody start, AnchorBody end) {
        }

        record AnchorBody(@JsonProperty("block_id") String blockId, Integer offset) {
        }

        static SelectionBody selectionOf(com.ymc.chat.domain.ChatSelection selection) {
            if (selection == null) {
                return null;
            }
            return new SelectionBody(
                    new AnchorBody(selection.start().blockId(), selection.start().offset()),
                    new AnchorBody(selection.end().blockId(), selection.end().offset()));
        }
    }
```

`bodyValue`:

```java
                .bodyValue(new StreamRequestBody(request.threadId(), request.paperId(),
                        request.message(), StreamRequestBody.selectionOf(request.selection())))
```

`dispatch`를 "한 번 파싱 → 식별자 대조 → 분기" 구조로 교체한다. 계약(backend_handling)이 모든 이벤트의 thread_id·paper_id 일치 확인을 요구한다 — 불일치는 파싱 위반과 동일하게 예외를 던져 기존 transport error 경로로 종결시킨다(콜백이 호출되지 않으므로 잘못된 delta·완성본이 저장·전달되지 않는다).

`subscribe`의 dispatch 호출을 `dispatch(event, request, listener, terminalSeen)`으로 바꾸고, `dispatch`와 `textField`를 다음으로 교체:

```java
    private void dispatch(ServerSentEvent<String> event, AiRunRequest request,
            AiStreamListener listener, AtomicBoolean terminalSeen) {
        String name = event.event() == null ? "" : event.event();
        try {
            switch (name) {
                case "run.started", "message.delta", "message.completed", "run.completed", "run.failed" -> {
                    JsonNode data = objectMapper.readTree(event.data());
                    verifyIdentifiers(data, request);
                    dispatchKnown(name, data, listener, terminalSeen);
                }
                default -> log.debug("알 수 없는 AI event 무시: {}", name);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // reactive 체인으로 던진다 — Reactor가 구독을 취소(=연결 종료=AI 생성 취소)하고
            // error 경로를 타서 onTransportError가 정확히 한 번 호출된다
            throw new IllegalStateException("AI event 처리 실패: " + name, e);
        }
    }

    private void dispatchKnown(String name, JsonNode data,
            AiStreamListener listener, AtomicBoolean terminalSeen) {
        switch (name) {
            case "run.started" -> listener.onRunStarted();
            case "message.delta" -> listener.onDelta(requiredText(data, "delta"));
            case "message.completed" -> listener.onMessageCompleted(requiredText(data, "message"));
            case "run.completed" -> {
                terminalSeen.set(true);
                listener.onRunCompleted();
            }
            case "run.failed" -> {
                terminalSeen.set(true);
                JsonNode error = data.get("error");
                if (error == null || !error.isObject()) {
                    throw new IllegalArgumentException("AI run.failed에 error 객체가 없습니다.");
                }
                listener.onRunFailed(requiredText(error, "code"), requiredText(error, "message"));
            }
            default -> throw new IllegalArgumentException("dispatchKnown에 올 수 없는 event: " + name);
        }
    }

    /** 잘못 라우팅된 스트림의 응답이 이 run에 귀속되지 않게 모든 이벤트에서 확인한다. */
    private static void verifyIdentifiers(JsonNode data, AiRunRequest request) {
        if (!request.threadId().equals(requiredText(data, "thread_id"))
                || !request.paperId().equals(requiredText(data, "paper_id"))) {
            throw new IllegalArgumentException("AI event의 thread_id·paper_id가 요청과 다릅니다.");
        }
    }

    private static String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "AI event data에 '" + fieldName + "' 문자열 필드가 없습니다.");
        }
        return value.asText();
    }
```

클래스 javadoc의 계약 파일명도 inline-pdf-agent로 갱신.

- [ ] **Step 5: ChatStreamService·컨트롤러 수정**

`begin`:

```java
    /** message.started를 보내고 AI 스트림을 시작한다. 호출 시점은 시작 트랜잭션 commit 후다. */
    public void begin(SseEmitter emitter, ChatStartResult started, String userContent,
            ChatSelection selection) {
        Run run = new Run(emitter, started);
        run.sendStarted();
        AiRunHandle handle = aiAgentStreamPort.stream(
                new AiRunRequest(started.sessionId().toString(), started.paperId().toString(),
                        userContent, selection), run);
        run.arm(handle);
    }
```

import `com.ymc.chat.domain.ChatSelection` 추가. `Run.onRunFailed` 교체:

```java
        @Override
        public void onRunFailed(String code, String message) {
            log.warn("AI run 실패. messageId={} code={} message={}",
                    ids.assistantMessageId(), code, truncate(message, 200));
            if (code.startsWith("SELECTION_")) {
                // 같은 selection 재시도는 의미가 없다 — 코드를 그대로 전달하고 retryable=false
                failWith(code, selectionErrorMessage(code), false);
                return;
            }
            failWith("AI_RUN_FAILED", "답변을 생성하지 못했습니다.", true);
        }
```

클래스 하단(truncate 근처)에 추가:

```java
    private static String selectionErrorMessage(String code) {
        return "SELECTION_TOO_LARGE".equals(code)
                ? "선택 영역이 너무 큽니다. 더 좁게 선택해 주세요."
                : "선택 영역을 확인할 수 없습니다. 본문을 다시 선택해 주세요.";
    }
```

`ChatController.createMessageStream`의 begin 호출:

```java
        chatStreamService.begin(emitter, started, request.content(), selection);
```

`FakeAiAgentStreamAdapter`는 요청 필드를 읽지 않으므로 수정 불필요.

- [ ] **Step 6: 어댑터 테스트 통과 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test --tests 'com.ymc.chat.infra.AiAgentWebClientAdapterTest'
```

Expected: PASS

- [ ] **Step 7: 통합 테스트 갱신 + SELECTION 매핑 테스트 추가**

`AiRelayIntegrationTest`:
- 헬퍼 호출부를 새 시그니처로 전부 치환 — `runFailedOverWire`는 `FakeAiSseServer.runFailed("INTERNAL_SERVER_ERROR", "upstream raw detail")` (기대값 변화 없음 — AI_RUN_FAILED 유지).
- 인라인 `Frame` 리터럴 3곳의 data JSON에 `"thread_id":"{{thread_id}}","paper_id":"{{paper_id}}"` 포함 (Step 1 참고).
- `feDisconnectStillPersists`의 begin 호출을 `chatStreamService.begin(emitter, started, "질문", null);`로 수정.
- 테스트 추가:

```java
    @Test
    @DisplayName("selection 요청이 AI body에 실리고, SELECTION_* 실패는 코드 그대로 retryable=false로 온다")
    void selectionRelayAndErrorMapping() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted(),
                FakeAiSseServer.runFailed("SELECTION_TOO_LARGE", "Selection exceeds limits.")));

        MvcResult result = mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", UUID.randomUUID().toString(),
                                "content", "이 부분 설명해줘",
                                "selection", Map.of(
                                        "start", Map.of("blockId", "p0001-b0000"),
                                        "end", Map.of("blockId", "p0001-b0002"))))))
                .andExpect(request().asyncStarted())
                .andReturn();

        ChatMessage assistant = awaitAssistantTerminal();
        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(aiServer.lastRequestBody()).contains("\"paper_id\":\"" + paper.getId() + "\"");
        assertThat(aiServer.lastRequestBody()).contains("\"block_id\":\"p0001-b0000\"");
        String stream = streamBody(result);
        assertThat(stream).contains("\"code\":\"SELECTION_TOO_LARGE\"");
        assertThat(stream).contains("\"retryable\":false");
        assertThat(stream).doesNotContain("Selection exceeds limits.");
    }
```

- [ ] **Step 8: 전체 BE 테스트**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test
```

Expected: 전부 PASS

- [ ] **Step 9: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add be && git commit -m "[YMC-312] feat(be): 채팅 AI 경로를 inline-pdf-agent로 전환 — paper_id·selection 전달, SELECTION_* 오류 매핑"
```

---

### Task 4: FE — Range→블록 앵커 변환

**Files:**
- Create: `app/fe/src/routes/study/selectionAnchors.ts`
- Create: `app/fe/src/routes/study/selectionAnchors.test.ts`
- Modify: `app/fe/src/routes/study/useTextSelection.ts`

**Interfaces:**
- Produces: `SelectionAnchors { start: { blockId: string }; end: { blockId: string } }`, `computeSelectionAnchors(range: Range): SelectionAnchors | null`, `TextSelection.anchors: SelectionAnchors | null`. Task 5~6이 이 타입을 그대로 쓴다.
- 참고: DOM Range는 start가 항상 문서 순서상 end보다 앞이다(역방향 드래그도 브라우저가 정규화) — 별도 정렬 불필요.

- [ ] **Step 1: 실패하는 테스트 작성**

`app/fe/src/routes/study/selectionAnchors.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { computeSelectionAnchors } from './selectionAnchors';

function setupBlocks(): void {
  document.body.innerHTML = `
    <div id="viewer">
      <section data-block-id="p0001-b0000"><p>첫 번째 문단</p></section>
      <section data-block-id="p0001-b0001"><p>두 번째 <strong>문단</strong></p></section>
    </div>
    <div id="outside">본문 밖 텍스트</div>
  `;
}

function rangeOf(startNode: Node, startOffset: number, endNode: Node, endOffset: number): Range {
  const range = document.createRange();
  range.setStart(startNode, startOffset);
  range.setEnd(endNode, endOffset);
  return range;
}

describe('computeSelectionAnchors', () => {
  it('한 블록 안의 선택은 start와 end가 같은 blockId다', () => {
    setupBlocks();
    const text = document.querySelector('[data-block-id="p0001-b0000"] p')!.firstChild!;
    expect(computeSelectionAnchors(rangeOf(text, 0, text, 3))).toEqual({
      start: { blockId: 'p0001-b0000' },
      end: { blockId: 'p0001-b0000' },
    });
  });

  it('블록 경계를 넘는 선택은 걸친 두 블록을 가리킨다', () => {
    setupBlocks();
    const first = document.querySelector('[data-block-id="p0001-b0000"] p')!.firstChild!;
    const strong = document.querySelector('[data-block-id="p0001-b0001"] strong')!.firstChild!;
    expect(computeSelectionAnchors(rangeOf(first, 2, strong, 1))).toEqual({
      start: { blockId: 'p0001-b0000' },
      end: { blockId: 'p0001-b0001' },
    });
  });

  it('블록 밖에 걸친 선택은 null이다', () => {
    setupBlocks();
    const inside = document.querySelector('[data-block-id="p0001-b0001"] p')!.firstChild!;
    const outside = document.getElementById('outside')!.firstChild!;
    expect(computeSelectionAnchors(rangeOf(inside, 0, outside, 2))).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test -- selectionAnchors
```

Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`app/fe/src/routes/study/selectionAnchors.ts`:

```ts
// 화면 선택(Range)을 본문 블록 앵커로 변환한다. PaperViewer가 각 블록을
// <section data-block-id>로 렌더링하므로 조상 탐색만으로 블록을 찾는다.
// offset은 보내지 않는다 — 블록 단위 선택(계약 ChatSelection 참고).

export interface SelectionAnchors {
  start: { blockId: string };
  end: { blockId: string };
}

export function computeSelectionAnchors(range: Range): SelectionAnchors | null {
  const start = closestBlockId(range.startContainer);
  const end = closestBlockId(range.endContainer);
  if (!start || !end) return null;
  return { start: { blockId: start }, end: { blockId: end } };
}

function closestBlockId(node: Node): string | null {
  const el = node instanceof Element ? node : node.parentElement;
  return el?.closest('[data-block-id]')?.getAttribute('data-block-id') ?? null;
}
```

- [ ] **Step 4: 통과 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test -- selectionAnchors
```

Expected: PASS (3 tests)

- [ ] **Step 5: useTextSelection에 anchors 추가**

`useTextSelection.ts`에서 import 추가:

```ts
import { computeSelectionAnchors, type SelectionAnchors } from './selectionAnchors';
```

인터페이스 확장:

```ts
export interface TextSelection {
  text: string;
  rect: DOMRect;
  anchors: SelectionAnchors | null;
  clear: () => void;
}
```

`setSelection` 호출 교체:

```ts
      setSelection({ text, rect: range.getBoundingClientRect(), anchors: computeSelectionAnchors(range), clear });
```

- [ ] **Step 6: 전체 테스트·타입 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test && npm run typecheck
```

Expected: 전부 PASS (SelectionLayer는 `...sel` 스프레드로 anchors를 그대로 담으므로 타입 오류 없음)

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add fe && git commit -m "[YMC-312] feat(fe): 본문 선택을 블록 앵커로 변환"
```

---

### Task 5: FE — selection 전송 배관·인라인 인용 제거

**Files:**
- Modify: `app/fe/src/chat/chatStream.ts`
- Modify: `app/fe/src/chat/chatState.ts`
- Modify: `app/fe/src/api/chatSessions.ts`
- Modify: `app/fe/src/routes/study/SelectionLayer.tsx`
- Modify: `app/fe/src/routes/StudyPage.tsx`
- Modify: `app/fe/src/routes/study/TutorPanel.tsx`
- Test: `app/fe/src/chat/chatStream.test.ts`, `app/fe/src/chat/chatState.test.ts`

**Interfaces:**
- Consumes: Task 4의 `SelectionAnchors`.
- Produces: `StreamOpts.selection?: SelectionAnchors | null`, `ChatMessage.selection: SelectionAnchors | null`(state), `ChatState.pending: { clientMessageId, content, selection } | null`, send 액션 `{ type: 'send'; clientMessageId; content; selection: SelectionAnchors | null; resend?: boolean }`, `ChatMessageItem.selection: SelectionAnchors | null`(api), `TutorPanelPendingContext.anchors: SelectionAnchors | null`, `SelectionLayerProps.onAsk(text, mode, anchors)`. Task 6이 `ChatMessage.selection`으로 칩을 그린다.

- [ ] **Step 1: 실패하는 테스트 추가**

`chatStream.test.ts`의 기존 헬퍼(`mockStreamFetch`, `frame`, `collect`)를 그대로 사용해 테스트 추가:

```ts
  it('selection이 있으면 요청 body에 포함한다', async () => {
    mockStreamFetch([frame('message.completed', { type: 'message.completed', content: 'x', status: 'COMPLETED' })]);
    await collect({ selection: { start: { blockId: 'b1' }, end: { blockId: 'b2' } } });
    const mockFetch = globalThis.fetch as unknown as ReturnType<typeof vi.fn>;
    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.selection).toEqual({ start: { blockId: 'b1' }, end: { blockId: 'b2' } });
  });
```

selection 미전송 케이스는 기존 테스트 `요청 바디·헤더가 계약과 일치한다`의 `expect(body).toEqual({ clientMessageId: 'c-1', content: '질문' })`가 이미 커버한다 — 새로 만들지 않는다.

`chatState.test.ts`에 추가:

```ts
it('send는 user 메시지와 pending에 selection을 담는다', () => {
  const sel = { start: { blockId: 'b1' }, end: { blockId: 'b1' } };
  const s = chatReducer(initialChatState, { type: 'send', clientMessageId: 'c1', content: '질문', selection: sel });
  expect(s.messages[0].selection).toEqual(sel);
  expect(s.pending).toEqual({ clientMessageId: 'c1', content: '질문', selection: sel });
});

it('historyLoaded는 항목의 selection을 보존한다', () => {
  const sel = { start: { blockId: 'b1' }, end: { blockId: 'b2' } };
  const s = chatReducer(initialChatState, {
    type: 'historyLoaded', sessionId: 's1',
    items: [{ messageId: 'm1', role: 'USER', content: '질문', status: 'COMPLETED', seq: 1, createdAt: 't', selection: sel }],
  });
  expect(s.messages[0].selection).toEqual(sel);
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test -- chat
```

Expected: FAIL (selection 프로퍼티 없음 — 타입·런타임 모두)

- [ ] **Step 3: chatStream·chatState·api 타입 구현**

`chatStream.ts`:

```ts
import type { SelectionAnchors } from '../routes/study/selectionAnchors';
```

`StreamOpts`에 `selection?: SelectionAnchors | null;` 추가. body 구성 교체:

```ts
  const body: { clientMessageId: string; content: string; sessionId?: string; selection?: SelectionAnchors } = { clientMessageId, content };
  if (sessionId) body.sessionId = sessionId;
  if (selection) body.selection = selection;
```

(함수 시그니처 구조분해에 `selection` 추가.)

`api/chatSessions.ts`의 `ChatMessageItem`에 추가:

```ts
import type { SelectionAnchors } from '../routes/study/selectionAnchors';
```

```ts
  selection: SelectionAnchors | null; // user 메시지의 선택 앵커, 그 외 null
```

`chatState.test.ts`의 `item({...})` 팩토리(85행 부근) 기본값에 `selection: null`을 추가해 기존 historyLoaded 테스트들이 새 필드로 컴파일되게 한다.

`chatState.ts`:
- `ChatMessage`에 `selection: SelectionAnchors | null;` 추가 (import 동일).
- send 액션 타입: `{ type: 'send'; clientMessageId: string; content: string; selection?: SelectionAnchors | null; resend?: boolean }` — optional로 둬서 selection 없는 기존 테스트가 그대로 컴파일되게 한다.
- `ChatState.pending`: `{ clientMessageId: string; content: string; selection: SelectionAnchors | null } | null`.
- reducer 'send': pending에 `selection: action.selection ?? null` 추가; 새 user 메시지에 `selection: action.selection ?? null`, assistant placeholder에 `selection: null`; resend 분기의 pending도 동일 확장(말풍선은 유지).
- 'historyLoaded': `selection: it.selection ?? null` 매핑 추가.
- 'duplicate' 등 다른 분기는 selection을 만들지 않으므로 그대로.

- [ ] **Step 4: SelectionLayer → StudyPage → TutorPanel 배관 + 인라인 인용 제거**

`SelectionLayer.tsx`:
- `onAsk: (text: string, mode: 'current' | 'new', anchors: SelectionAnchors | null) => void;` (import 추가).
- 각 Layer phase에 `anchors: SelectionAnchors | null` 추가 — toolbar 진입 시 `{ phase: 'toolbar', ...sel }` 스프레드가 sel.anchors를 담으므로 타입만 맞추면 된다. `handleAsk`에서 askChoice로 넘길 때 `anchors: layer.anchors` 전달, `handleAskChoice`에서 `onAsk(text, mode, anchors)`.
- **앵커 계산 실패 처리(결정)** — anchors가 null이면 selection 없이 전송한다(논문 전체 기반 답변). 발생 조건이 매우 드물고(뷰어 밖 선택은 useTextSelection이 이미 거르며, 브라우저가 끝점을 텍스트에 붙여줌) UI 추가 없이 수용하기로 결정. 별도 차단·안내 코드를 넣지 않는다.

`StudyPage.tsx`:

```ts
  function handleAsk(text: string, mode: 'current' | 'new', anchors: SelectionAnchors | null) {
    setPendingContext({ text, mode, anchors });
    setChatCollapsed(false);
  }
```

`TutorPanel.tsx`:
- `TutorPanelPendingContext`에 `anchors: SelectionAnchors | null;` 추가.
- `buildContent` 함수를 삭제한다.
- `run` 시그니처: `function run(clientMessageId: string, content: string, resend: boolean, selection: SelectionAnchors | null)` — dispatch send에 `selection`, streamChatMessage에 `selection` 전달.
- `handleSend`:

```ts
  function handleSend() {
    const question = input.trim();
    if (!question || state.streaming) return;
    setInput('');
    resetComposerHeight();
    run(crypto.randomUUID(), question, false, pendingContext?.anchors ?? null);
    if (pendingContext) onContextConsumed();
  }
```

- `handleRetry`: pending 재전송은 `run(state.pending.clientMessageId, state.pending.content, true, state.pending.selection)`, 확인된 실패 재시도는 `run(crypto.randomUUID(), lastUser.content, true, lastUser.selection)`.

- [ ] **Step 5: 통과 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test && npm run typecheck
```

Expected: 전부 PASS

- [ ] **Step 6: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add fe && git commit -m "[YMC-312] feat(fe): 선택 구절 인라인 삽입을 selection 필드 전송으로 교체"
```

---

### Task 6: FE — 선택 구절 칩 (전송·이력)

**Files:**
- Create: `app/fe/src/chat/selectionPreview.ts`
- Create: `app/fe/src/chat/selectionPreview.test.ts`
- Modify: `app/fe/src/routes/study/TutorPanel.tsx`
- Modify: `app/fe/src/routes/StudyPage.tsx`

**Interfaces:**
- Consumes: Task 5의 `ChatMessage.selection`, `PaperBlock`(markdown/paperContent.ts — StudyPage의 `blocks`는 DTO가 아니라 이 변환 모델이다: `{id, type, markdown?, tableHtml?, headingText?, headingLevel?}`).
- Produces: `resolveSelectionPreview(blocks: PaperBlock[], selection: SelectionAnchors): string | null`, `TutorPanelProps.blocks: PaperBlock[]`.

- [ ] **Step 1: 실패하는 테스트 작성**

`app/fe/src/chat/selectionPreview.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { resolveSelectionPreview } from './selectionPreview';
import type { PaperBlock } from '../markdown/paperContent';

const blocks: PaperBlock[] = [
  { id: 'b0', type: 'para', markdown: '첫 문단' },
  { id: 'b1', type: 'figure', markdown: '![](https://example/img.png)' },
  { id: 'b2', type: 'heading', markdown: '## 결론', headingText: '결론', headingLevel: 2 },
  { id: 'b3', type: 'para', markdown: '셋째 문단' },
];

describe('resolveSelectionPreview', () => {
  it('선택 범위의 텍스트 블록을 이어 붙인다 — 문단은 markdown, 제목은 headingText, 그림·표는 건너뜀', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: 'b0' }, end: { blockId: 'b3' } }))
      .toBe('첫 문단 결론 셋째 문단');
  });

  it('120자를 넘으면 말줄임한다', () => {
    const long: PaperBlock[] = [{ id: 'b0', type: 'para', markdown: 'a'.repeat(200) }];
    const preview = resolveSelectionPreview(long, { start: { blockId: 'b0' }, end: { blockId: 'b0' } });
    expect(preview!.length).toBe(121); // 120 + '…'
    expect(preview!.endsWith('…')).toBe(true);
  });

  it('블록을 찾지 못하면 null이다', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: '없음' }, end: { blockId: 'b2' } })).toBeNull();
  });

  it('범위가 뒤집혀 있으면 null이다', () => {
    expect(resolveSelectionPreview(blocks, { start: { blockId: 'b2' }, end: { blockId: 'b0' } })).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test -- selectionPreview
```

Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`app/fe/src/chat/selectionPreview.ts`:

```ts
// 저장된 블록 앵커를 로컬 본문에서 텍스트로 복원한다 — 칩 미리보기용.
// selection은 텍스트를 저장하지 않으므로(계약) 항상 blocks에서 다시 찾는다.
import type { PaperBlock } from '../markdown/paperContent';
import type { SelectionAnchors } from '../routes/study/selectionAnchors';

const PREVIEW_MAX = 120;

export function resolveSelectionPreview(
  blocks: PaperBlock[],
  selection: SelectionAnchors,
): string | null {
  const startIdx = blocks.findIndex((b) => b.id === selection.start.blockId);
  const endIdx = blocks.findIndex((b) => b.id === selection.end.blockId);
  if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return null;
  const text = blocks
    .slice(startIdx, endIdx + 1)
    .map(blockText)
    .map((t) => t.trim())
    .filter(Boolean)
    .join(' ');
  if (!text) return null;
  return text.length > PREVIEW_MAX ? `${text.slice(0, PREVIEW_MAX)}…` : text;
}

function blockText(b: PaperBlock): string {
  if (b.type === 'heading' || b.type === 'subheading') return b.headingText ?? '';
  if (b.type === 'para') return b.markdown ?? '';
  return ''; // equation·table·figure·other는 미리보기에서 제외
}
```

- [ ] **Step 4: 통과 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test -- selectionPreview
```

Expected: PASS (4 tests)

- [ ] **Step 5: TutorPanel에 칩 렌더링 + blocks prop**

`TutorPanelProps`에 `blocks: PaperBlock[];` 추가 (import: `import type { PaperBlock } from '../../markdown/paperContent';`, `import { resolveSelectionPreview } from '../../chat/selectionPreview';`).

`StudyPage.tsx`의 TutorPanel 사용부에 `blocks={blocks}` 추가 — StudyPage의 `blocks`는 `PaperBlock[]`이므로 그대로 타입이 맞는다.

user 말풍선 렌더링(`m.role === 'user'` 분기)을 교체 — 칩을 말풍선 위에 붙인다:

```tsx
            if (m.role === 'user') {
              const preview = m.selection ? resolveSelectionPreview(blocks, m.selection) : null;
              return (
                <div key={m.key} style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4 }}>
                  {m.selection ? (
                    <button
                      onClick={() => document.getElementById(m.selection!.start.blockId)?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
                      title="선택한 본문으로 이동"
                      style={{ ...contextChipStyle, maxWidth: 260, border: '1px solid var(--color-border)' }}
                    >
                      <Quotes size={12} color="var(--color-text-muted)" />
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {preview ?? '선택한 구절'}
                      </span>
                    </button>
                  ) : null}
                  <StudentMessage
                    style={{
                      background: 'var(--color-bg-surface)',
                      color: 'var(--color-text-body)',
                      border: 'none',
                      borderRadius: '14px 14px 4px 14px',
                      padding: '11px 15px',
                    }}
                  >
                    {m.content}
                  </StudentMessage>
                </div>
              );
            }
```

(`contextChipStyle`은 파일에 이미 있다. `Quotes`도 이미 import돼 있다.)

- [ ] **Step 6: 전체 테스트·타입 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test && npm run typecheck
```

Expected: 전부 PASS

- [ ] **Step 7: 커밋**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add fe && git commit -m "[YMC-312] feat(fe): 채팅 말풍선에 선택 구절 칩 표시 — 클릭 시 본문 이동"
```

---

### Task 7: 전체 검증·수동 확인·보고

**Files:**
- 없음 (검증만)

- [ ] **Step 1: 두 repo 전체 테스트**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test
```

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npm test && npm run typecheck
```

Expected: 전부 PASS

- [ ] **Step 2: (선택) 로컬 e2e 수동 확인**

`infra/local` compose로 PG·LocalStack·AI 서버를 띄울 수 있으면: 논문 열기 → 본문 드래그 → "질문하기" → 응답 스트리밍 확인, 이력 재조회 시 칩 표시 확인. AI 서버에 OPENAI key가 없으면 이 단계는 건너뛴다 (BE `ai.fake-stream=true`로 SSE 경로만 확인 가능 — fake는 selection을 무시한다).

- [ ] **Step 3: 배포 절차·알려진 제약 정리**

보고에 다음 두 가지를 포함해 사용자·팀이 배포 시 놓치지 않게 한다:

- **DB 반영 순서**: local·dev는 `ddl-auto: update`로 자동 반영. prod는 `validate`이므로 **BE 배포 전에** `alter table chat_message add column selection jsonb;`를 먼저 적용한다(`be/docs/db/chat.sql` 하단 주석 참고). 컬럼이 nullable이라 구버전 앱과 공존 가능 — 롤백 시 컬럼은 그대로 둬도 된다.
- **배포 순서**: BE를 FE보다 먼저(또는 동시에) 배포한다 — 구 BE는 새 FE의 `selection` 필드를 조용히 무시해(lenient Jackson) 선택 질문이 컨텍스트 없이 simple-agent로 가는 무증상 퇴행이 생긴다.
- **알려진 제약(수용된 결정)**: ① 전환 전(simple-agent 시절) 세션에도 새 질문이 허용되지만, AI 체크포인트 키 구조가 달라 그 세션의 과거 AI 맥락 없이 새 대화로 답변한다. ② 드물게 선택 앵커 계산이 실패하면 selection 없이 논문 전체 질문으로 전송된다. 둘 다 dev 단계로 수용했으며 필요 시 후속 티켓에서 다룬다.

- [ ] **Step 4: 변경 요약 보고 후 PR은 사용자 승인 대기**

두 repo의 `git log --oneline origin/main..HEAD`와 핵심 diff를 사용자에게 요약해 보여주고, PR 생성(project-docs 먼저 → app) 여부를 확인받는다. 임의로 push·PR 하지 않는다.
