# 사용자별 AI 채팅 동시 실행 상한 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 한 사용자가 동시에 답변을 생성 중인 채팅 세션을 최대 N개(플랜 공통, 초기 3)로 제한하고, 초과 요청을 `429 CHAT_CONCURRENCY_LIMIT_EXCEEDED`로 거절한다.

**Architecture:** 채팅 시작 트랜잭션(`ChatCommandService.start`)에서 `users` 행을 `PESSIMISTIC_WRITE`로 잠근 뒤 `GENERATING` assistant 메시지를 세어 판정한다. 카운터 컬럼 없이 원장(메시지 행)을 세며, 잠금 순서는 `chat_session → users → usage_bucket`으로 고정한다. 정책값은 `PlanProperties`에 두고 배포 설정으로 덮는다.

**Tech Stack:** Spring Boot 3 / Spring Data JPA / PostgreSQL (Testcontainers), React + Vitest, OpenAPI 3.1 YAML

**Spec:** `docs/superpowers/specs/2026-09-03-chat-concurrency-limit-design.md`

## Global Constraints

- 커밋 메시지는 `[YMC-326] type(scope): subject`. Claude 서명·Co-Authored-By 금지.
- 코드 주석은 핵심 1~2줄, 티켓·문서 출처 괄호 금지.
- 오류 코드 이름: `CHAT_CONCURRENCY_LIMIT_EXCEEDED`, HTTP 429.
- 설정 키: `plan.chat.max-active-sessions` (기본 3, 1 이상).
- 오류 메시지: `동시에 진행 중인 답변이 {N}개입니다. 하나가 끝나면 다시 시도하세요.`
- 인덱스 이름: `ix_chat_session_owner`.
- 리포지토리: `UserRepository.findWithLockById(UUID)`, `ChatMessageRepository.countBySessionOwnerIdAndRoleAndStatus(UUID, ChatMessageRole, ChatMessageStatus)`.
- project-docs는 `## 배경 · ## 변경 · ## 의존`, app은 `## 배경 · ## 변경사항 · ## 검증 · ## 의존` PR 양식.
- BE 테스트 실행: `cd app/be && ./gradlew test --tests '<FQCN>'`. 통합 테스트는 Docker(Testcontainers) 필요.
- FE 테스트 실행: `cd app/fe && npx vitest run <path>`.

---

## File Structure

| 저장소 | 파일 | 책임 |
|---|---|---|
| project-docs | `features/FT-011-플랜-사용량-제한.md` | Story 4(동시 실행 상한) 정책, 경계·Resolved·Done 개정 |
| project-docs | `features/FT-007-ai-튜터-채팅.md` | Out of Scope에 FT-011 포인터 한 줄 |
| project-docs | `contracts/frontend-backend/openapi.yaml` | 429 설명, 오류 코드 목록·enum |
| app/be | `plan/infra/PlanProperties.java` | `Chat(maxActiveSessions)` 정책값 |
| app/be | `resources/application.yml` | `plan.chat.max-active-sessions: 3` |
| app/be | `common/error/ErrorCode.java` | 새 오류 코드 |
| app/be | `user/domain/UserRepository.java` | 사용자 행 잠금 쿼리 |
| app/be | `chat/domain/ChatMessageRepository.java` | 소유자별 GENERATING 수 |
| app/be | `chat/service/ChatCommandService.java` | 사용자 잠금 → 중복 재판정 → 상한 판정 |
| app/be | `support/IntegrationTest.java` | 테스트 사용자 행 픽스처 |
| app/be | `chat/api/ChatConcurrencyIntegrationTest.java` (신규) | 상한·경합(users 행 선점)·롤백·HTTP 테스트 |
| app/be | `user/service/OAuthUserServiceIntegrationTest.java` | 사용자 수 단언을 상대값으로 |
| app/be | `docs/db/chat.sql` | `ix_chat_session_owner` |
| app/fe | `src/chat/chatStream.ts` | 이 코드만 `retryable: true` |

---

### Task 1: project-docs — FT-011 Story 4, FT-007 포인터, openapi 오류 코드

**Files:**
- Modify: `project-docs/features/FT-011-플랜-사용량-제한.md`
- Modify: `project-docs/features/FT-007-ai-튜터-채팅.md:26`
- Modify: `project-docs/contracts/frontend-backend/openapi.yaml` (429 설명 ~635행, 오류 코드 목록 ~1587행, enum ~1606행)

**Interfaces:**
- Produces: 오류 코드 문자열 `CHAT_CONCURRENCY_LIMIT_EXCEEDED` (Task 3·6이 그대로 사용)

- [ ] **Step 1: project-docs 브랜치 생성**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs && git fetch -q origin && git switch -c YMC-326-chat-concurrency-limit origin/main
```

- [ ] **Step 2: FT-011 Out of Scope 수정**

`## 2. Feature Boundary` → `### Out of Scope`의 마지막 줄을 바꾼다.

이전:
```markdown
- 분당 요청 수, 동시 실행 수와 IP 기반 남용 방지 등 운영 rate limit → 별도 운영 정책
```
이후:
```markdown
- 분당 요청 수와 IP 기반 남용 방지 등 운영 rate limit → 별도 운영 정책 (YMC-327, YMC-350)
```

그리고 `### In Scope` 마지막에 한 줄 추가:
```markdown
- 사용자 전체에서 동시에 답변을 생성하는 채팅 세션 수의 상한
```

- [ ] **Step 3: FT-011 Story 4 신설**

`### Story 3` 블록 끝(`- Depends on: Story 1-2` 다음 빈 줄) 뒤, `## 5. Done Criteria` 앞에 삽입:

```markdown
### Story 4. 시스템은 사용자의 동시 AI 실행을 제한한다 `MVP`

- type: SYSTEM
- Source Userflows: UF-003 Step 3-5
- Acceptance Criteria:
  - 활성 세션은 `ASSISTANT` 메시지가 `GENERATING`인 채팅 세션이다. 논리 삭제된 세션도 생성 중이면 활성으로 센다.
  - 사용자 전체 활성 세션 상한은 플랜과 무관한 공통 정책값이며 코드에 고정하지 않고 배포 정책값으로 주입한다. 베타 초기 정책값은 3이다.
  - 활성 세션 수가 상한 이상이면 새 질의를 시작하지 않고, 사용량을 예약하지 않으며, 구분 가능한 오류 코드를 반환한다.
  - 같은 세션에서는 한 번에 하나의 질의만 허용하며, 같은 세션의 재요청은 사용자 전체 상한보다 먼저 세션 내 실행 중 오류로 거절한다.
  - 같은 `clientMessageId`의 재전송은 상한 판정보다 먼저 기존 실행 상태를 반환한다.
  - 동시 요청이 몰려도 커밋된 활성 세션 수는 상한을 넘지 않는다.
  - 문서 파싱에는 사용자별 동시 제한을 두지 않는다.
- Depends on: Story 2, FT-007 Story 2

```

- [ ] **Step 4: FT-011 Done Criteria와 Resolved 수정**

`## 5. Done Criteria` 목록 끝에 추가:
```markdown
- 사용자 전체 활성 세션이 상한에 도달하면 새 질의가 구분 가능한 오류로 거절되고, 동시 요청에서도 상한을 넘지 않는다.
```

`## 6. Open Questions`의 아래 Resolved 항목을 교체한다.

이전:
```markdown
> **Resolved** — MVP는 같은 채팅 세션의 AI 실행을 1개로 제한하되, 서로 다른 세션의 질의를 막는 사용자 전체 동시 실행 제한은 두지 않는다.
```
이후:
```markdown
> **Resolved** — 같은 채팅 세션의 AI 실행은 1개로 제한한다. 사용자 전체 활성 세션 상한은 플랜 공통 정책값(초기 3)으로 두며 Story 4가 정의한다(YMC-326). 초기 결정은 "사용자 전체 제한을 두지 않는다"였으나 여러 세션을 열어 병렬 실행하는 경로를 막기 위해 변경했다.
```

- [ ] **Step 5: FT-007 Out of Scope 포인터 추가**

`features/FT-007-ai-튜터-채팅.md` 26행 다음에 추가:
```markdown
- 사용자 전체 동시 AI 실행 상한 → FT-011 플랜·사용량 제한 (Story 4)
```

- [ ] **Step 6: openapi 429 설명 수정**

`POST /api/papers/{paperId}/chat/messages`의 `"429":` 블록을 바꾼다.

이전:
```yaml
        "429":
          description: "채팅 사용량 제한 초과. code: CHAT_USAGE_LIMIT_EXCEEDED"
```
이후:
```yaml
        "429":
          description: |
            월간 채팅 사용량 초과면 CHAT_USAGE_LIMIT_EXCEEDED(다음 버킷까지 재시도 불가).
            사용자 전체에서 답변을 생성 중인 세션이 상한에 달했으면 CHAT_CONCURRENCY_LIMIT_EXCEEDED
            (다른 세션의 답변이 끝나면 같은 내용으로 재시도 가능). 두 경우 모두 사용량을 예약하지 않는다.
```

- [ ] **Step 7: openapi 오류 코드 목록·enum 추가**

`- CHAT_USAGE_LIMIT_EXCEEDED: 채팅 사용량 제한 초과 (429)` 줄 다음에:
```yaml
            - CHAT_CONCURRENCY_LIMIT_EXCEEDED: 사용자 전체 동시 AI 실행 상한 초과 (429)
```
enum 배열의 `CHAT_USAGE_LIMIT_EXCEEDED,` 다음에:
```yaml
              CHAT_CONCURRENCY_LIMIT_EXCEEDED,
```

- [ ] **Step 8: YAML 문법 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/project-docs && python3 -c "import yaml,sys; yaml.safe_load(open('contracts/frontend-backend/openapi.yaml')); print('yaml ok')"
```
Expected: `yaml ok`

- [ ] **Step 9: 커밋 2개 (사용자 승인 후)**

```bash
git add features/FT-011-플랜-사용량-제한.md features/FT-007-ai-튜터-채팅.md
git commit -m "[YMC-326] docs(features): FT-011 사용자 동시 AI 실행 상한 Story 4"
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-326] docs(contracts): 채팅 429에 CHAT_CONCURRENCY_LIMIT_EXCEEDED 추가"
```

---

### Task 2: BE 정책값 — `PlanProperties.Chat`

**Files:**
- Modify: `app/be/src/main/java/com/ymc/plan/infra/PlanProperties.java`
- Modify: `app/be/src/main/resources/application.yml:72-84`
- Test: `app/be/src/test/java/com/ymc/plan/infra/PlanPropertiesTest.java`

**Interfaces:**
- Produces: `PlanProperties.chat().maxActiveSessions()` → `int` (Task 4가 사용). 생성자 시그니처가 `(policy, cleanup, chat)`로 바뀐다.

- [ ] **Step 1: app 브랜치 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git branch --show-current
```
Expected: `YMC-326-chat-concurrency-limit` (스펙 커밋 `11075dd`가 있는 브랜치). project-docs도 같은 브랜치명을 쓰므로 반드시 `app`에서 확인한다.

- [ ] **Step 2: 실패하는 테스트 작성**

`PlanPropertiesTest`에 추가하고, 기존 생성자 호출 5곳에 세 번째 인자 `chat()`를 넣는다: `props()` 안 1곳, `new PlanProperties(null, cleanup())`, `new PlanProperties(policy, cleanup())` 2곳(`missingPlanPolicyRejected`, `missingUsageTypePolicyRejected`), `new PlanProperties(policies(), null)`.

```java
    @Test
    @DisplayName("application.yml 기본값 — chat.max-active-sessions는 3")
    void chatMaxActiveSessions() {
        assertThat(props().chat().maxActiveSessions()).isEqualTo(3);
    }

    @Test
    @DisplayName("chat 설정 전체가 없으면 거부한다")
    void nullChatRejected() {
        assertThatThrownBy(() -> new PlanProperties(policies(), cleanup(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat");
    }

    @Test
    @DisplayName("max-active-sessions는 1 이상이어야 한다")
    void nonPositiveMaxActiveSessionsRejected() {
        assertThatThrownBy(() -> new PlanProperties.Chat(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-active-sessions");
        assertThatThrownBy(() -> new PlanProperties.Chat(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-active-sessions");
        assertThatThrownBy(() -> new PlanProperties.Chat(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-active-sessions");
    }

    private PlanProperties props() {
        return new PlanProperties(policies(), cleanup(), chat());
    }

    private PlanProperties.Chat chat() {
        return new PlanProperties.Chat(3);
    }
```

- [ ] **Step 3: 실패 확인**

```bash
cd app/be && ./gradlew test --tests 'com.ymc.plan.infra.PlanPropertiesTest'
```
Expected: 컴파일 실패 (`Chat` 없음, 생성자 인자 수 불일치)

- [ ] **Step 4: 구현**

`PlanProperties.java`:

```java
/** 플랜·기능별 사용량 정책, 정리 주기, 채팅 동시 실행 상한. 값은 배포 설정으로 덮는다. */
@ConfigurationProperties(prefix = "plan")
public record PlanProperties(Map<PlanCode, Map<UsageType, Policy>> policy, Cleanup cleanup, Chat chat) {

    public PlanProperties {
        if (policy == null) {
            throw new IllegalArgumentException("플랜 정책이 필요합니다.");
        }
        if (cleanup == null) {
            throw new IllegalArgumentException("cleanup 설정이 필요합니다.");
        }
        if (chat == null) {
            throw new IllegalArgumentException("chat 설정이 필요합니다.");
        }
        // ... 기존 플랜/사용유형 검증 그대로
    }

    /** 사용자 전체 동시 GENERATING 세션 상한. 플랜과 무관한 공통값이다. */
    public record Chat(Integer maxActiveSessions) {
        public Chat {
            if (maxActiveSessions == null || maxActiveSessions < 1) {
                throw new IllegalArgumentException("chat.max-active-sessions는 1 이상이어야 합니다.");
            }
        }
    }
    // Policy, Cleanup, policyOf 기존 그대로
}
```

`application.yml`의 `plan:` 블록에 `cleanup:` 앞에 추가:

```yaml
  chat:
    max-active-sessions: 3           # 사용자당 동시 GENERATING 세션 상한 (플랜 공통)
```

- [ ] **Step 5: 통과 확인 + YAML 바인딩 확인**

```bash
./gradlew test --tests 'com.ymc.plan.infra.PlanPropertiesTest' --tests 'com.ymc.chat.api.ChatUsageIntegrationTest'
```
Expected: PASS. `application.yml`의 `plan.chat.max-active-sessions`가 `Chat.maxActiveSessions`로 묶이는지는 통합 테스트의 Spring 컨텍스트 기동이 검증한다(키가 빠지면 생성자가 예외를 던져 컨텍스트가 뜨지 않는다). 기존 `policy`·`cleanup`과 같은 방식이라 별도 바인딩 테스트는 두지 않는다.

- [ ] **Step 6: 커밋 (사용자 승인 후)**

```bash
git add src/main/java/com/ymc/plan/infra/PlanProperties.java src/main/resources/application.yml src/test/java/com/ymc/plan/infra/PlanPropertiesTest.java
git commit -m "[YMC-326] feat(plan): 채팅 동시 실행 상한 정책값 추가"
```

---

### Task 3: BE 오류 코드·리포지토리·테스트 사용자 픽스처

**Files:**
- Modify: `app/be/src/main/java/com/ymc/common/error/ErrorCode.java:61-65`
- Modify: `app/be/src/main/java/com/ymc/user/domain/UserRepository.java`
- Modify: `app/be/src/main/java/com/ymc/chat/domain/ChatMessageRepository.java`
- Modify: `app/be/src/test/java/com/ymc/support/IntegrationTest.java` (resetState, 헬퍼)
- Modify: `app/be/src/test/java/com/ymc/user/service/OAuthUserServiceIntegrationTest.java:39,63` (사용자 수 단언)
- Create: `app/be/src/test/java/com/ymc/chat/api/ChatConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED` (429)
  - `Optional<User> UserRepository.findWithLockById(UUID id)`
  - `long ChatMessageRepository.countBySessionOwnerIdAndRoleAndStatus(UUID ownerId, ChatMessageRole role, ChatMessageStatus status)`
  - `IntegrationTest.givenUser(UUID id)`; `resetState`가 `TEST_USER_ID`·`OTHER_USER_ID` 행을 항상 만든다
  - 테스트 클래스 `ChatConcurrencyIntegrationTest`의 헬퍼 `givenCompletedPaper(UUID owner, String filename)`, `startNewSession(UUID owner, Paper paper)`

- [ ] **Step 1: 실패하는 테스트 작성 (세는 조건)**

`ChatConcurrencyIntegrationTest.java` 신규:

```java
package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class ChatConcurrencyIntegrationTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    Paper givenCompletedPaper(UUID owner, String filename) {
        Paper paper = paperRepository.save(Paper.register(owner, filename, Instant.now()));
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    ChatStartResult startNewSession(UUID owner, Paper paper) {
        return chatCommandService.start(owner, paper.getId(), null, UUID.randomUUID(), "질문");
    }

    long activeCount(UUID owner) {
        return chatMessageRepository.countBySessionOwnerIdAndRoleAndStatus(
                owner, ChatMessageRole.ASSISTANT, ChatMessageStatus.GENERATING);
    }

    @Test
    @DisplayName("활성 수는 소유자의 GENERATING만 센다 — 완료·실패·다른 사용자는 제외, 삭제된 세션은 포함")
    void countsOnlyOwnersGeneratingAssistants() {
        Paper mine = givenCompletedPaper(TEST_USER_ID, "mine.pdf");
        Paper theirs = givenCompletedPaper(OTHER_USER_ID, "theirs.pdf");

        ChatStartResult generating = startNewSession(TEST_USER_ID, mine);
        ChatStartResult completed = startNewSession(TEST_USER_ID, mine);
        ChatStartResult failed = startNewSession(TEST_USER_ID, mine);
        startNewSession(OTHER_USER_ID, theirs);

        chatMessageTransitions.complete(completed.assistantMessageId(), "답변", completed.clientMessageId(), null);
        chatMessageTransitions.fail(failed.assistantMessageId(), failed.clientMessageId());
        tx.executeWithoutResult(s -> {
            ChatSession session = chatSessionRepository.findById(generating.sessionId()).orElseThrow();
            session.markDeleted(Instant.now());
        });

        assertThat(activeCount(TEST_USER_ID)).isEqualTo(1);
        assertThat(activeCount(OTHER_USER_ID)).isEqualTo(1);
    }
}
```

`ChatStartResult`는 `record(UUID paperId, UUID sessionId, UUID assistantMessageId, UUID clientMessageId)`다.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.chat.api.ChatConcurrencyIntegrationTest'
```
Expected: 컴파일 실패 (`countBySessionOwnerIdAndRoleAndStatus` 없음)

- [ ] **Step 3: ErrorCode 추가**

`CHAT_USAGE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),` 다음에:

```java
    /** 사용자 전체 동시 AI 실행 상한 초과 */
    CHAT_CONCURRENCY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
```

- [ ] **Step 4: UserRepository 잠금 쿼리**

```java
package com.ymc.user.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

    /** 사용자 행을 PESSIMISTIC_WRITE로 잠근다. 채팅 시작의 "사용자 전체 동시 실행" 판정을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithLockById(UUID id);
}
```

- [ ] **Step 5: ChatMessageRepository 세기 쿼리**

`existsBySessionIdAndStatus` 다음에 추가:

```java
    /** 소유자의 GENERATING assistant 수 = 활성 세션 수. 논리 삭제된 세션도 센다. */
    @Query("""
            select count(m) from ChatMessage m
            where m.session.ownerId = :ownerId and m.role = :role and m.status = :status
            """)
    long countBySessionOwnerIdAndRoleAndStatus(
            UUID ownerId, ChatMessageRole role, ChatMessageStatus status);
```

- [ ] **Step 6: IntegrationTest 사용자 픽스처**

`IntegrationTest.java`에 `JdbcTemplate` 주입과 헬퍼를 추가하고, `resetState`의 `userRepository.deleteAll();` 바로 뒤에 두 사용자를 만든다.

```java
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    // resetState 안, userRepository.deleteAll() 다음:
        givenUser(TEST_USER_ID);
        givenUser(OTHER_USER_ID);

    /** 테스트 JWT subject에 대응하는 users 행. 채팅 시작이 사용자 행을 잠그므로 항상 둔다. */
    protected void givenUser(UUID id) {
        jdbcTemplate.update(
                "insert into users (id, provider, provider_id, email, display_name, created_at) "
                        + "values (?, 'GOOGLE', ?, ?, ?, now())",
                id, "sub-" + id, id + "@test.local", "테스트 사용자");
    }
```

import: `org.springframework.jdbc.core.JdbcTemplate`.

- [ ] **Step 7: 기존 사용자 수 단언 보정**

`resetState`가 사용자 2행을 항상 만들므로 `OAuthUserServiceIntegrationTest`의 `userRepository.count()` 단언 두 곳(39행, 63행)이 깨진다. 절대값 대신 로그인 전 수를 기준으로 바꾼다.

```java
        // 각 테스트 메서드 첫 줄에
        long before = userRepository.count();
        // ... 기존 로그인 호출 ...
        assertThat(userRepository.count()).isEqualTo(before + 1);
```

- [ ] **Step 8: 통과 확인 + 기존 테스트 영향 확인**

```bash
./gradlew test --tests 'com.ymc.chat.api.ChatConcurrencyIntegrationTest' --tests 'com.ymc.user.*' --tests 'com.ymc.chat.api.ChatUsageIntegrationTest'
```
Expected: PASS

- [ ] **Step 9: 커밋 (사용자 승인 후)**

```bash
git add src/main/java/com/ymc/common/error/ErrorCode.java src/main/java/com/ymc/user/domain/UserRepository.java src/main/java/com/ymc/chat/domain/ChatMessageRepository.java src/test/java/com/ymc/support/IntegrationTest.java src/test/java/com/ymc/chat/api/ChatConcurrencyIntegrationTest.java src/test/java/com/ymc/user/service/OAuthUserServiceIntegrationTest.java
git commit -m "[YMC-326] feat(chat): 동시 실행 상한 오류 코드와 사용자 잠금·활성 수 쿼리"
```

---

### Task 4: BE 채팅 시작 — 사용자 잠금, 중복 재판정, 상한 판정

**Files:**
- Modify: `app/be/src/main/java/com/ymc/chat/service/ChatCommandService.java:28-113`
- Modify: `app/be/src/test/java/com/ymc/chat/service/ChatCommandServiceRaceTest.java` (생성자 인자 추가)
- Test: `app/be/src/test/java/com/ymc/chat/api/ChatConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 `PlanProperties.chat().maxActiveSessions()`, Task 3 리포지토리 두 메서드와 `ErrorCode`
- Produces: `start`가 상한에서 `ApiException(CHAT_CONCURRENCY_LIMIT_EXCEEDED)`을 던진다

- [ ] **Step 1: 실패하는 테스트 작성 (상한·통과·세션 내 우선·롤백·사용자 없음·HTTP)**

`ChatConcurrencyIntegrationTest`에 추가. import에 `com.ymc.common.error.ApiException`, `com.ymc.common.error.ErrorCode`, `com.ymc.chat.service.DuplicateChatMessageException`, `org.springframework.http.MediaType`, `org.springframework.test.web.servlet.request.MockMvcRequestBuilders`, `org.springframework.test.web.servlet.result.MockMvcResultMatchers`, `static org.assertj.core.api.Assertions.assertThatThrownBy`, `static org.assertj.core.api.Assertions.catchThrowableOfType`.

```java
    @Test
    @DisplayName("활성 세션이 상한(3)이면 4번째 시작은 429이고 세션·메시지·예약이 추가되지 않는다")
    void fourthStartRejectedAtLimit() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "limit.pdf");
        for (int i = 0; i < 3; i++) {
            startNewSession(TEST_USER_ID, paper);
        }
        long sessions = chatSessionRepository.count();
        long messages = chatMessageRepository.count();
        long records = usageRecordRepository.count();
        Instant accessedBefore = reload(paper.getId()).getLastAccessedAt();

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> startNewSession(TEST_USER_ID, paper));

        assertThat(rejected.code()).isEqualTo(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED);
        assertThat(chatSessionRepository.count()).isEqualTo(sessions);
        assertThat(chatMessageRepository.count()).isEqualTo(messages);
        assertThat(usageRecordRepository.count()).isEqualTo(records);
        assertThat(reload(paper.getId()).getLastAccessedAt()).isEqualTo(accessedBefore);
    }

    @Test
    @DisplayName("기존 세션으로 온 4번째 요청도 429이고 세션 활동 시각이 갱신되지 않는다")
    void fourthStartOnExistingSessionRejected() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "existing.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        chatMessageTransitions.complete(first.assistantMessageId(), "답변", first.clientMessageId(), null);
        startNewSession(TEST_USER_ID, paper); // 다시 3개 활성, first 세션은 유휴
        Instant activityBefore = chatSessionRepository.findById(first.sessionId()).orElseThrow().getLastMessageAt();

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), first.sessionId(),
                        UUID.randomUUID(), "후속 질문"));

        assertThat(rejected.code()).isEqualTo(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED);
        assertThat(chatSessionRepository.findById(first.sessionId()).orElseThrow().getLastMessageAt())
                .isEqualTo(activityBefore);
    }

    @Test
    @DisplayName("활성 세션 하나가 완료되면 다시 시작할 수 있다")
    void startAllowedAfterOneCompletes() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "free-slot.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);

        chatMessageTransitions.complete(first.assistantMessageId(), "답변", first.clientMessageId(), null);

        assertThat(startNewSession(TEST_USER_ID, paper)).isNotNull();
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("논리 삭제된 세션의 GENERATING도 상한에 센다")
    void deletedGeneratingSessionStillCounts() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "deleted.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        tx.executeWithoutResult(s -> chatSessionRepository.findById(first.sessionId())
                .orElseThrow().markDeleted(Instant.now()));

        assertThatThrownBy(() -> startNewSession(TEST_USER_ID, paper))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("같은 세션 재요청은 사용자 전체 상한보다 먼저 CHAT_RUN_IN_PROGRESS로 거절된다")
    void sameSessionRejectedBeforeUserLimit() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "same-session.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), first.sessionId(),
                        UUID.randomUUID(), "후속 질문"));

        assertThat(rejected.code()).isEqualTo(ErrorCode.CHAT_RUN_IN_PROGRESS);
    }

    @Test
    @DisplayName("start 뒤 트랜잭션이 롤백되면 예약과 활성 슬롯이 함께 풀린다")
    void rollbackFreesReservationAndSlot() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "rollback.pdf");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            startNewSession(TEST_USER_ID, paper);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(activeCount(TEST_USER_ID)).isZero();
        assertThat(usageRecordRepository.count()).isZero();
    }

    @Test
    @DisplayName("사용자 행이 없으면 IllegalStateException")
    void missingUserRowFails() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "no-user.pdf");
        jdbcTemplate.update("delete from users where id = ?", TEST_USER_ID);

        assertThatThrownBy(() -> startNewSession(TEST_USER_ID, paper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("HTTP: 상한에서 429와 CHAT_CONCURRENCY_LIMIT_EXCEEDED를 반환한다")
    void httpReturns429WithCode() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "http.pdf");
        for (int i = 0; i < 3; i++) {
            startNewSession(TEST_USER_ID, paper);
        }

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"%s","content":"질문"}""".formatted(UUID.randomUUID())))
                .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("CHAT_CONCURRENCY_LIMIT_EXCEEDED"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("동시에 진행 중인 답변이 3개입니다. 하나가 끝나면 다시 시도하세요."));
    }
```

`ApiException`의 코드 접근자는 `code()`다. `Paper`와 `ChatSession`은 Lombok `@Getter`라 `getLastAccessedAt()`·`getLastMessageAt()`이 있다.

- [ ] **Step 2: 실패 확인**

```bash
./gradlew test --tests 'com.ymc.chat.api.ChatConcurrencyIntegrationTest'
```
Expected: 새 테스트들이 FAIL (4번째 시작이 통과함 등). `countsOnlyOwnersGeneratingAssistants`는 PASS 유지.

- [ ] **Step 3: ChatCommandService 구현**

필드·생성자에 `UserRepository userRepository`, `PlanProperties planProperties`를 추가하고, `start`에서 세션 내 GENERATING 검사(`CHAT_RUN_IN_PROGRESS` throw) 바로 다음에 넣는다.

```java
        // 잠금 순서: chat_session → users → usage_bucket. 역순으로 잠그는 트랜잭션을 만들지 않는다.
        userRepository.findWithLockById(ownerId)
                .orElseThrow(() -> new IllegalStateException("사용자 행 없음: " + ownerId));
        // 사용자 잠금을 기다리는 동안 같은 clientMessageId가 커밋됐을 수 있다.
        // 새 세션 경로는 세션 잠금이 없어 이 재판정이 유일한 멱등 방어선이다.
        rejectDuplicate(ownerId, paperId, clientMessageId, content);
        int maxActive = planProperties.chat().maxActiveSessions();
        long active = chatMessageRepository.countBySessionOwnerIdAndRoleAndStatus(
                ownerId, ChatMessageRole.ASSISTANT, ChatMessageStatus.GENERATING);
        if (active >= maxActive) {
            throw new ApiException(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED,
                    "동시에 진행 중인 답변이 " + maxActive + "개입니다. 하나가 끝나면 다시 시도하세요.");
        }
```

클래스 Javadoc의 두 번째 문단을 이렇게 바꾼다:

```java
 * <p>세션당 동시 실행 1개는 기존 세션 행의 PESSIMISTIC_WRITE 잠금으로, 사용자 전체 활성 세션 상한은
 * users 행 잠금 아래에서 GENERATING assistant를 세어 보장한다. 새 세션은 방금 만든 UUID라
 * 세션 잠금 경쟁 상대가 없다.
```

`start`의 `@throws` 목록에 추가:
```java
     * @throws ApiException CHAT_CONCURRENCY_LIMIT_EXCEEDED — 사용자 전체 활성 세션이 상한
```

import: `com.ymc.user.domain.UserRepository`, `com.ymc.plan.infra.PlanProperties`.

- [ ] **Step 4: 단위 테스트 생성자 보정**

`ChatCommandServiceRaceTest`에 mock 두 개 추가(스텁하지 않음 — 해당 경로는 사용자 잠금 전에 끝난다):

```java
    @Mock
    UserRepository userRepository;

    @Mock
    PlanProperties planProperties;
```

`ChatCommandServiceTest`는 `IntegrationTest`를 상속해 `@Autowired`로 받으므로 손댈 것이 없다.

- [ ] **Step 5: 통과 확인**

```bash
./gradlew test --tests 'com.ymc.chat.api.ChatConcurrencyIntegrationTest' --tests 'com.ymc.chat.service.*' --tests 'com.ymc.chat.api.ChatUsageIntegrationTest' --tests 'com.ymc.chat.api.ChatMessageStreamIntegrationTest'
```
Expected: PASS

- [ ] **Step 6: 경합 테스트 작성 (users 행 선점 방식)**

두 요청을 "같은 순간에 출발"시키는 것만으로는 잠금이 없어도 순차 실행으로 통과할 수 있다. 대신 테스트 스레드가 먼저 `users` 행을 잠근 채 두 요청을 출발시키고, 둘 다 잠금 대기에 들어간 것을 `pg_stat_activity`로 확인한 뒤 풀어준다. 잠금 구현이 있으면 둘은 반드시 직렬화되고, 없으면 대기 없이 동시에 달려 둘 다 통과한다.

`ChatConcurrencyIntegrationTest`에 추가. import에 `java.util.Arrays`, `java.util.List`, `java.util.concurrent.*`, `com.ymc.user.domain.User`.

```java
    @Test
    @DisplayName("활성 2개에서 새 세션 시작 2건이 사용자 잠금에서 직렬화되어 하나만 성공한다")
    void concurrentNewSessionStartsAdmitExactlyOne() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "race-new.pdf");
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);

        List<Throwable> outcomes = runWhileUserLockHeld(TEST_USER_ID,
                () -> startNewSession(TEST_USER_ID, paper),
                () -> startNewSession(TEST_USER_ID, paper));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(t -> t instanceof ApiException e
                && e.code() == ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED).hasSize(1);
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("활성 2개에서 서로 다른 기존 세션의 시작 2건도 하나만 성공한다")
    void concurrentExistingSessionStartsAdmitExactlyOne() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "race-existing.pdf");
        ChatStartResult a = startNewSession(TEST_USER_ID, paper);
        ChatStartResult b = startNewSession(TEST_USER_ID, paper);
        chatMessageTransitions.complete(a.assistantMessageId(), "답변", a.clientMessageId(), null);
        chatMessageTransitions.complete(b.assistantMessageId(), "답변", b.clientMessageId(), null);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper); // 활성 2개, a·b는 유휴

        List<Throwable> outcomes = runWhileUserLockHeld(TEST_USER_ID,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), a.sessionId(), UUID.randomUUID(), "a 후속"),
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), b.sessionId(), UUID.randomUUID(), "b 후속"));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(t -> t instanceof ApiException e
                && e.code() == ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED).hasSize(1);
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 clientMessageId의 새 세션 시작 2건은 잠금 후 재판정으로 진 쪽이 DUPLICATE_MESSAGE를 받는다")
    void concurrentSameClientMessageIdYieldsDuplicate() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "race-dup.pdf");
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        UUID clientMessageId = UUID.randomUUID();

        List<Throwable> outcomes = runWhileUserLockHeld(TEST_USER_ID,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문"),
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문"));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(t -> t instanceof DuplicateChatMessageException).hasSize(1);
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    /**
     * 테스트 스레드가 users 행을 잠근 채 두 작업을 출발시키고, 둘 다 잠금 대기에 들어간 뒤 풀어준다.
     * 각 작업의 예외(성공이면 null)를 돌려준다.
     */
    private List<Throwable> runWhileUserLockHeld(UUID userId, Runnable first, Runnable second) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                userRepository.findWithLockById(userId).orElseThrow();
                held.countDown();
                awaitOrFail(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).as("holder가 잠금을 잡음").isTrue();

            Future<Throwable> f1 = pool.submit(() -> attempt(first));
            Future<Throwable> f2 = pool.submit(() -> attempt(second));
            awaitLockWaiters(2);
            release.countDown();

            holder.get(10, TimeUnit.SECONDS);
            return Arrays.asList(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 잠금 대기 중인 DB 세션이 n개가 될 때까지 기다린다 (최대 5초). 잠금 구현이 없으면 여기서 실패한다. */
    private void awaitLockWaiters(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= n) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("잠금 대기 세션 " + n + "개가 생기지 않음 — 사용자 잠금이 동작하지 않는다");
    }

    private static Throwable attempt(Runnable task) {
        try {
            task.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
```

`tx`(`TransactionTemplate`)와 `userRepository`, `jdbcTemplate`은 `IntegrationTest`가 제공한다. 같은 `clientMessageId` 테스트는 두 요청이 첫 `rejectDuplicate`를 통과한 채 사용자 잠금에서 기다리므로, 잠금 후 재판정이 없으면 진 쪽이 `DUPLICATE_MESSAGE` 대신 429를 받아 실패한다.

- [ ] **Step 7: 경합 테스트 통과 확인**

```bash
./gradlew test --tests 'com.ymc.chat.api.ChatConcurrencyIntegrationTest'
```
Expected: PASS. `awaitLockWaiters`가 실패하면 사용자 잠금이 잡히지 않는 것이다.

- [ ] **Step 8: 커밋 (사용자 승인 후)**

```bash
git add src/main/java/com/ymc/chat/service/ChatCommandService.java src/test/java/com/ymc/chat/service/ChatCommandServiceRaceTest.java src/test/java/com/ymc/chat/api/ChatConcurrencyIntegrationTest.java
git commit -m "[YMC-326] feat(chat): 사용자 전체 활성 세션 상한 판정"
```

---

### Task 5: DDL 인덱스와 실행 계획 확인

**Files:**
- Modify: `app/be/docs/db/chat.sql` (`chat_session` 테이블 정의 다음)
- Modify: `geunhh/aws/operations/dev-배포-runbook.md` (prod 첫 배포 전 반영 대상 기록, 별도 저장소·별도 커밋)

**Interfaces:**
- Consumes: Task 3 세기 쿼리
- Produces: 없음

- [ ] **Step 1: DDL 추가**

`create table chat_session (...);` 바로 다음에:

```sql
-- 사용자 전체 활성 세션 수를 세는 쿼리가 소유자로 세션을 좁힌다 (users 행 잠금 아래에서 실행).
create index ix_chat_session_owner on chat_session (owner_id);
```

- [ ] **Step 2: 로컬 DB에 적용하고 실행 계획 확인**

로컬 PostgreSQL(`infra/local/up.sh`)이 떠 있어야 한다. 접속 정보는 `infra/local/postgresql/README.md` 기준 `postgresql://ymc:1234@localhost:5432/ymc_dev`다.

```bash
psql postgresql://ymc:1234@localhost:5432/ymc_dev -c "create index if not exists ix_chat_session_owner on chat_session (owner_id);"
psql postgresql://ymc:1234@localhost:5432/ymc_dev -c "explain select count(*) from chat_message m join chat_session s on s.id = m.session_id where s.owner_id = '00000000-0000-0000-0000-000000000001' and m.role = 'ASSISTANT' and m.status = 'GENERATING';"
```
`explain` 앞에 `analyze chat_session; analyze chat_message;`를 먼저 실행한다.
Expected: 인덱스가 존재하고 오류 없이 계획이 나온다. 행이 수십 개뿐인 로컬 테이블에서는 planner가 `Seq Scan`을 고를 수 있으므로 특정 Scan 노드를 단언하지 않는다. 인덱스 선택을 보고 싶으면 `set enable_seqscan = off;` 뒤 다시 `explain`해 `ix_chat_session_owner`가 쓰이는지 확인한다. 테스트 컨테이너는 `ddl-auto: update`라 인덱스가 없어도 동작하므로 테스트 결과에는 영향이 없다.

- [ ] **Step 3: dev DB에 반영 (사용자가 실행)**

`infra/docs/runbooks/rds-dbeaver.md` 절차대로 SSM 터널을 열고 psql로 인덱스를 만든다. 자격증명은 Secrets Manager의 dev DB 비밀에서 읽는다.

```bash
cd /Users/geunhh/Desktop/team-ymc/infra && AWS_PROFILE=ymc scripts/db-tunnel.sh dev
# 다른 셸에서 (DB 이름·사용자는 런북 참고)
psql "postgresql://<user>:<password>@localhost:15432/<db>" -c "create index if not exists ix_chat_session_owner on chat_session (owner_id);"
# 끝나면
AWS_PROFILE=ymc scripts/db-tunnel.sh dev --cleanup
```
Expected: `CREATE INDEX`. app PR의 `## 검증`에 "dev DB 인덱스 반영 완료"를 적는다. 반영 전에 BE를 배포해도 기능은 동작하고 세는 쿼리만 느리다.

- [ ] **Step 4: app 커밋 (사용자 승인 후)**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add be/docs/db/chat.sql && git commit -m "[YMC-326] feat(db): chat_session owner_id 인덱스"
```

- [ ] **Step 5: 런북 기록과 geunhh 커밋 (사용자 승인 후)**

`geunhh/aws/operations/dev-배포-runbook.md` "미해결 (티켓)" 목록 위에 한 줄:

```markdown
- prod 첫 배포 전 수동 DDL: `app/be/docs/db/chat.sql`의 `ix_chat_session_owner` (dev는 2026-09-XX 반영).
```

```bash
cd /Users/geunhh/Desktop/team-ymc/geunhh && git add aws/operations/dev-배포-runbook.md && git commit -m "prod 첫 배포 전 DDL 반영 대상 기록"
```
geunhh는 티켓 키 없이 한 줄 제목만 쓴다. 이 저장소의 다른 미커밋 파일은 담지 않는다.

---

### Task 6: FE — 동시성 429만 재시도 가능

**Files:**
- Modify: `app/fe/src/chat/chatStream.ts:57-66`
- Test: `app/fe/src/chat/chatStream.test.ts`

**Interfaces:**
- Consumes: 오류 코드 문자열 `CHAT_CONCURRENCY_LIMIT_EXCEEDED`
- Produces: `failed` 액션의 `retryable`이 이 코드에서만 `true`

- [ ] **Step 1: 실패하는 테스트 작성**

`chatStream.test.ts`의 `'그 외 HTTP 오류: ...'` 테스트 다음에:

```ts
  it('429 CHAT_CONCURRENCY_LIMIT_EXCEEDED: 재시도 가능한 확인된 실패로 콜백한다', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false, status: 429, body: null,
      json: async () => ({ code: 'CHAT_CONCURRENCY_LIMIT_EXCEEDED', message: '동시에 진행 중인 답변이 3개입니다.' }),
    }) as unknown as typeof fetch;
    const actions = await collect();
    expect(actions.at(-1)).toMatchObject({
      type: 'failed', confirmed: true, code: 'CHAT_CONCURRENCY_LIMIT_EXCEEDED', retryable: true,
    });
  });
```

- [ ] **Step 2: 실패 확인**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npx vitest run src/chat/chatStream.test.ts
```
Expected: 새 테스트 FAIL (`retryable: false`)

- [ ] **Step 3: 구현**

`chatStream.ts`의 HTTP 오류 분기:

```ts
    onEvent({
      type: 'failed', confirmed: true,
      code: errorBody.code || `HTTP_${res.status}`,
      message: errorBody.message || '요청에 실패했습니다.',
      // 동시 실행 상한은 다른 세션 답변이 끝나면 같은 내용으로 다시 보낼 수 있다
      retryable: errorBody.code === 'CHAT_CONCURRENCY_LIMIT_EXCEEDED',
    });
```

- [ ] **Step 4: 통과 확인**

```bash
npx vitest run src/chat/chatStream.test.ts src/routes/study
```
Expected: PASS

- [ ] **Step 5: 커밋 (사용자 승인 후)**

```bash
cd /Users/geunhh/Desktop/team-ymc/app && git add fe/src/chat/chatStream.ts fe/src/chat/chatStream.test.ts && git commit -m "[YMC-326] feat(fe): 동시 실행 상한 429는 재시도 가능으로 표시"
```

---

### Task 7: 전체 검증, PR, Jira

**Files:** 없음 (절차)

- [ ] **Step 1: BE 전체 테스트**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/be && ./gradlew test
```
Expected: PASS

- [ ] **Step 2: FE 전체 테스트·타입 검사**

```bash
cd /Users/geunhh/Desktop/team-ymc/app/fe && npx vitest run && npx tsc --noEmit
```
Expected: PASS

- [ ] **Step 3: project-docs 푸시·PR (사용자 승인 후)**

본문 `## 배경 · ## 변경 · ## 의존`. 의존에 app PR(예정)을 적는다.

- [ ] **Step 4: app 푸시·PR (사용자 승인 후)**

본문 `## 배경 · ## 변경사항 · ## 검증 · ## 의존`. 검증에 통합 테스트 결과와 "dev DB 인덱스 미반영" 여부를 적고, 의존에 project-docs PR을 링크한다.

- [ ] **Step 5: Jira YMC-326 본문 갱신 (사용자 승인 후)**

결정 절에 "상한 3, 플랜 공통 정책값 `plan.chat.max-active-sessions`, 오류 `429 CHAT_CONCURRENCY_LIMIT_EXCEEDED`, 예약 전 거절, 재시도 가능"과 PR 링크를 반영한다.
