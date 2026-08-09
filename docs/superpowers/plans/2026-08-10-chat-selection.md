# 채팅 selection 릴레이 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Paper Viewer 선택 구절을 블록 앵커+UTF-16 offset으로 채팅 요청에 실어 AI에 릴레이한다 (저장 없음).

**Architecture:** FE는 검증 완료된 reflog 커밋 2개(`db4de0a` 블록 앵커+칩, `c574fd1` 문자 offset)를 cherry-pick으로 복원하고 이력(저장) 잔재만 걷어낸다. BE는 요청 DTO → 포트 → 어댑터 snake_case 직렬화의 최소 릴레이를 신규 작성한다. 스펙: `docs/superpowers/specs/2026-08-10-chat-selection-design.md`.

**Tech Stack:** React 19 + vitest(jsdom) / Spring Boot 3 + JUnit5.

## Global Constraints

- 브랜치 `YMC-312-inline-chat`, 베이스 `2fd9d2d`(그 위 docs 커밋 2개 있음).
- FE 테스트는 반드시 `fe/`에서 실행 (`npm test` = vitest run). BE는 `be/`에서 gradlew.
- 커밋 메시지에 Co-Authored-By·Generated with Claude Code 금지.
- 코드 주석은 핵심 1~2줄, 티켓 번호·스펙 섹션 인용 괄호 금지.
- BE는 selection을 저장하지 않는다 — `ChatCommandService`·`ChatMessage`·DB 불변.
- 의미 검증(블록 존재·atomic·상한)은 AI 소관 — BE는 bean validation 형식 검증만.

---

### Task 1: FE 블록 앵커+칩 복원 (cherry-pick db4de0a)

**Files:**
- Cherry-pick: `db4de0a` — `fe/src/routes/study/selectionAnchors.ts`(신규), `fe/src/chat/selectionPreview.ts`(신규), `TutorPanel.tsx`, `chatStream.ts`, `chatState.ts`, `chatSessions.ts`, `useTextSelection.ts`, `SelectionLayer.tsx`, `StudyPage.tsx` + 각 테스트

**Interfaces:**
- Produces: `SelectionAnchors {start:{blockId,offset?}, end:{blockId,offset?}}`, `streamChatMessage`의 `selection?` 옵션, `chatState`의 `send.selection`

- [ ] **Step 1: cherry-pick**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git cherry-pick db4de0a
```

Expected: 충돌 없이 적용 (베이스 이후 BE 파일만 변경됐음). 충돌 나면 중단하고 보고.

- [ ] **Step 2: FE 테스트**

```bash
cd fe && npm test
```

Expected: PASS 전부. (커밋은 cherry-pick이 이미 만들었음)

### Task 2: FE 문자 offset 복원 (cherry-pick c574fd1)

**Files:**
- Cherry-pick: `c574fd1` — `fe/src/markdown/rehypeSourcePos.ts`(신규), `fe/src/routes/study/sourceOffset.ts`(신규), `paperContent.ts`, `PaperMarkdown.tsx`, `PaperViewer.tsx`, `selectionAnchors.ts`, `selectionPreview.ts`, `package.json` + 각 테스트

**Interfaces:**
- Consumes: Task 1의 `SelectionAnchors`
- Produces: anchor에 `offset` 채워짐 (UTF-16, 검증 실패 시 생략)

- [ ] **Step 1: cherry-pick과 의존성 설치**

```bash
git cherry-pick c574fd1 && cd fe && npm install
```

Expected: 충돌 없음. `unist-util-visit`, `@types/hast` 설치됨.

- [ ] **Step 2: FE 테스트·타입 체크**

```bash
npm test && npm run typecheck
```

Expected: PASS 전부 (offset 테스트 포함), 타입 에러 0.

### Task 3: FE 이력 selection 잔재 제거

복원 코드에는 저장+이력 범위였던 흔적이 있다 — 계약상 이력 응답에 selection이 없으므로 걷어낸다. 이 세션에서 보낸 메시지의 칩(로컬 상태)은 유지된다.

**Files:**
- Modify: `fe/src/api/chatSessions.ts`, `fe/src/chat/chatState.ts`
- Test: `fe/src/chat/chatState.test.ts`

**Interfaces:**
- Produces: `ChatMessageItem`에 `selection` 없음. `historyLoaded`는 항상 `selection: null`.

- [ ] **Step 1: 타입에서 제거**

`chatSessions.ts`: `ChatMessageItem`의 `selection: SelectionAnchors | null;` 필드와 `import type { SelectionAnchors } ...` 줄 삭제.

`chatState.ts`: `historyLoaded` 매핑의 `selection: it.selection ?? null`을 `selection: null`로 변경 (주석: `// 이력 응답에는 selection이 없다`).

- [ ] **Step 2: 테스트 갱신**

`chatState.test.ts`: `historyLoaded는 항목의 selection을 보존한다` 테스트 삭제, `item()` 픽스처의 `selection: null` 필드 삭제.

- [ ] **Step 3: 검증·커밋**

```bash
npm test && npm run typecheck
cd .. && git add fe && git commit -m "fix(fe): 이력 selection 잔재 제거 — 계약은 요청 릴레이만"
```

Expected: PASS, 타입 에러 0.

### Task 4: BE selection 릴레이

**Files:**
- Create: `be/src/main/java/com/ymc/chat/api/dto/ChatSelectionDto.java`
- Modify: `be/src/main/java/com/ymc/chat/api/dto/ChatMessageStreamRequest.java`, `ChatController.java`, `service/ChatStreamService.java`, `service/port/AiRunRequest.java`, `infra/ai/AiAgentWebClientAdapter.java`
- Test: `be/src/test/java/com/ymc/chat/infra/AiAgentWebClientAdapterTest.java`, `api/AiRelayIntegrationTest.java`, 컴파일 수정: `infra/FakeAiAgentStreamAdapterTest.java`

**Interfaces:**
- Consumes: FE가 보내는 `selection {start:{blockId,offset?}, end:{…}}` (camelCase)
- Produces: AI 요청 body의 `selection {start:{block_id,offset?}, …}` (snake_case, null 생략)

- [ ] **Step 1: 실패하는 어댑터 단위 테스트**

`AiAgentWebClientAdapterTest`에 추가 (기존 `successSequenceAndSnakeCaseBody` 패턴):

```java
@Test
@DisplayName("selection은 snake_case로 직렬화되고, 없으면 필드 자체가 생략된다")
void selectionSerialization() {
    aiServer.enqueue(Script.of(
            FakeAiSseServer.runStarted("t-6"),
            FakeAiSseServer.messageCompleted("t-6", "답"),
            FakeAiSseServer.runCompleted("t-6")));

    ChatSelectionDto selection = new ChatSelectionDto(
            new ChatSelectionDto.Anchor("p0-b0", 3),
            new ChatSelectionDto.Anchor("p0-b2", null));
    adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-6", "p-6", "질문", selection), recorder);

    await().atMost(WAIT).until(() -> events.contains("run-completed"));
    assertThat(aiServer.lastRequestBody())
            .contains("\"selection\":{\"start\":{\"block_id\":\"p0-b0\",\"offset\":3},\"end\":{\"block_id\":\"p0-b2\"}}");
}

@Test
@DisplayName("selection이 null이면 body에 selection 키가 없다")
void nullSelectionOmitted() {
    aiServer.enqueue(Script.of(
            FakeAiSseServer.runStarted("t-7"),
            FakeAiSseServer.runCompleted("t-7")));

    adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-7", "p-7", "질문", null), recorder);

    await().atMost(WAIT).until(() -> events.contains("run-completed"));
    assertThat(aiServer.lastRequestBody()).doesNotContain("selection");
}
```

import 추가: `com.ymc.chat.api.dto.ChatSelectionDto`.
기존 `new AiRunRequest("t-N", "p-N", "질문")` 5곳은 4-arg `(…, null)`로 바꾼다.

- [ ] **Step 2: 컴파일 실패 확인**

```bash
cd be && ./gradlew compileTestJava -q
```

Expected: FAIL — `ChatSelectionDto` 없음, `AiRunRequest` 3-arg.

- [ ] **Step 3: 구현**

`ChatSelectionDto.java` (신규):

```java
package com.ymc.chat.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 계약의 ChatSelection. 형식 검증만 한다 — 블록 존재·atomic·상한 판정은 AI 소관. */
public record ChatSelectionDto(
        @NotNull @Valid Anchor start,
        @NotNull @Valid Anchor end) {

    /** offset은 UTF-16 code unit. null이면 블록 단위 앵커다. */
    public record Anchor(@NotBlank String blockId, @Min(0) Integer offset) {
    }
}
```

`ChatMessageStreamRequest.java` — 필드 추가:

```java
public record ChatMessageStreamRequest(
        UUID sessionId,
        @NotNull UUID clientMessageId,
        @NotBlank String content,
        @Valid ChatSelectionDto selection) {
}
```

(import `jakarta.validation.Valid` 추가)

`AiRunRequest.java`:

```java
/** BE↔AI 계약(inline-pdf-agent-run-stream.yml)의 request body. thread_id = sessionId, paper_id = paperId 문자열. */
public record AiRunRequest(String threadId, String paperId, String message, ChatSelectionDto selection) {
}
```

(import `com.ymc.chat.api.dto.ChatSelectionDto`)

`ChatController.createMessageStream` — begin 호출만 변경:

```java
chatStreamService.begin(emitter, started, request.content(), request.selection());
```

`ChatStreamService.begin`:

```java
public void begin(SseEmitter emitter, ChatStartResult started, String userContent, ChatSelectionDto selection) {
    Run run = new Run(emitter, started);
    run.sendStarted();
    AiRunHandle handle = aiAgentStreamPort.stream(
            new AiRunRequest(started.sessionId().toString(), started.paperId().toString(), userContent, selection), run);
    run.arm(handle);
}
```

(import `com.ymc.chat.api.dto.ChatSelectionDto`)

`AiAgentWebClientAdapter` — wire 레코드 교체:

```java
/** wire 형식은 snake_case (계약) — 코드 컨벤션과 경계에서 변환한다. */
record StreamRequestBody(
        @JsonProperty("thread_id") String threadId,
        @JsonProperty("paper_id") String paperId,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) SelectionBody selection) {
}

record SelectionBody(AnchorBody start, AnchorBody end) {
    static SelectionBody from(ChatSelectionDto dto) {
        return dto == null ? null
                : new SelectionBody(AnchorBody.from(dto.start()), AnchorBody.from(dto.end()));
    }
}

record AnchorBody(
        @JsonProperty("block_id") String blockId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer offset) {
    static AnchorBody from(ChatSelectionDto.Anchor anchor) {
        return new AnchorBody(anchor.blockId(), anchor.offset());
    }
}
```

bodyValue 변경:

```java
.bodyValue(new StreamRequestBody(request.threadId(), request.paperId(), request.message(),
        SelectionBody.from(request.selection())))
```

세 레코드는 어댑터 클래스 안에 나란히(중첩 없이) 둔다.
import 추가: `com.fasterxml.jackson.annotation.JsonInclude`, `com.ymc.chat.api.dto.ChatSelectionDto`.

`FakeAiAgentStreamAdapterTest`의 `new AiRunRequest("t-1", "p-1", "질문")` → `(…, null)`.

- [ ] **Step 4: 단위 테스트 통과 확인**

```bash
./gradlew test --tests "com.ymc.chat.infra.*" -q
```

Expected: PASS 전부 (새 2개 포함).

- [ ] **Step 5: 릴레이 통합 테스트**

`AiRelayIntegrationTest`의 `startStream`은 그대로 두고 selection 버전 테스트를 추가:

```java
@Test
@DisplayName("selection이 wire까지 snake_case로 전달된다")
void selectionRelayedOverWire() throws Exception {
    Paper paper = givenCompletedPaper();
    aiServer.enqueue(Script.of(
            FakeAiSseServer.runStarted("t"),
            FakeAiSseServer.messageCompleted("t", "답"),
            FakeAiSseServer.runCompleted("t")));

    MvcResult result = mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                    .with(userJwt())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                            "clientMessageId", UUID.randomUUID().toString(),
                            "content", "질문",
                            "selection", Map.of(
                                    "start", Map.of("blockId", "p0-b0", "offset", 0),
                                    "end", Map.of("blockId", "p0-b1"))))))
            .andExpect(request().asyncStarted())
            .andReturn();

    awaitAssistantTerminal();
    streamBody(result);
    assertThat(aiServer.lastRequestBody()).contains("\"block_id\":\"p0-b0\"");
    assertThat(aiServer.lastRequestBody()).doesNotContain("blockId");
}
```

```bash
./gradlew test --tests "com.ymc.chat.api.AiRelayIntegrationTest" -q
```

Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
cd .. && git add be && git commit -m "feat(be): 채팅 selection을 AI로 릴레이

요청 DTO의 selection(camelCase)을 형식 검증만 하고 inline-pdf-agent
요청 body(snake_case)로 전달한다. 저장·이력에는 넣지 않는다."
```

### Task 5: 전체 검증·push

- [ ] **Step 1: BE 전체 chat 테스트**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.*"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: FE 전체 테스트·타입**

```bash
cd ../fe && npm test && npm run typecheck
```

Expected: PASS, 에러 0.

- [ ] **Step 3: push (PR #36 브랜치에 이어짐)**

```bash
cd .. && git push origin YMC-312-inline-chat
```

push 후 사용자에게 핵심 diff 요약을 보여주고 배포 여부를 묻는다 (dev 배포는 BE 먼저).
