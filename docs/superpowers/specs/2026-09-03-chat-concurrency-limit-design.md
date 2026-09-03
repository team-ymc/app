# 사용자별 AI 채팅 동시 실행 상한 — 설계 스펙

- 날짜: 2026-09-03 (같은 날 Codex 리뷰 반영 — 사용자 잠금 후 중복 재판정, 사용자 행 부재 처리,
  잠금 체인 3단계, `chat_session(owner_id)` 인덱스, 엣지 케이스 수용 명시, 테스트 확장)
- 티켓: YMC-326
- 범위: BE(`app/be`) 채팅 시작 트랜잭션 + DDL 인덱스 1개, FE(`app/fe`) 오류 처리. AI·인프라 변경 없음.
- 정책 SSOT: `project-docs/features/FT-011-플랜-사용량-제한.md` Story 4 (이 스펙과 같은 시기에 개정)
- 계약 SSOT: `project-docs/contracts/frontend-backend/openapi.yaml` — `POST /api/papers/{paperId}/chat/messages` 429에 `CHAT_CONCURRENCY_LIMIT_EXCEEDED` 추가
- 관련 결정: ADR-006(원장을 세고 집계 카운터를 두지 않는다), FT-011 Resolved(같은 세션 1개 제한)

## 1. 목표와 불변식

한 사용자가 여러 채팅 세션을 열어 AI 질의를 병렬로 실행하는 수를 제한한다.

- **활성 세션**은 `ASSISTANT` 메시지가 `GENERATING`인 세션이다. 논리 삭제된 세션도 생성 중이면 활성이다 (삭제가 AI 실행을 취소하지 않으므로).
- 사용자 전체 활성 세션 수가 상한(플랜 공통 정책값, 초기 3) 이상이면 새 질의를 시작하지 않는다.
- 거절된 요청은 흔적을 남기지 않는다. 새 세션 행, 논문 접근 기록, 세션 활동 시각 갱신이 모두 `start` 트랜잭션 안에 있어 함께 롤백된다. 사용량 예약은 거절 뒤에 오므로 만들어지지 않는다.
- 같은 세션 내 실행 1개 제한(`CHAT_RUN_IN_PROGRESS`)은 그대로다. 세션 내 검사가 먼저, 사용자 전체 검사가 나중이다.
- 같은 `clientMessageId` 재전송의 멱등 계약(`DUPLICATE_MESSAGE`)은 동시성 거절보다 우선한다.
- 커밋된 활성 세션 수는 어떤 동시 요청 조합에서도 상한을 넘지 않는다. "세고 → 넣는" 구간이 사용자 단위로 직렬화된다.
- 문서 파싱은 대상이 아니다.

## 2. 세는 방식: 카운터가 아니라 원장

활성 수는 별도 컬럼에 유지하지 않고 `chat_message`의 `GENERATING` 행을 센다. ADR-006이 사용량에서 택한 것과 같은 이유다.

- 카운터 컬럼은 `COMPLETED`·`FAILED` 전이와 정체 정리(`StaleChatCleanup`) 모든 경로에서 감소를 빠뜨리면 영구히 상한에 갇힌다.
- `GENERATING` 행은 이미 실행 상태의 진실 원천이라 세면 어긋날 수 없다. 종결 전이는 조건부 UPDATE라 이긴 쪽만 반영되고, 롤백이면 상태와 사용량 정산이 함께 되돌아간다.
- 사용자당 최대 상한 개수의 행만 매칭되므로 세는 비용은 무시할 수준이다. 단 세션을 소유자로 좁히는 인덱스가 필요하다(§4).

## 3. 직렬화: `users` 행 비관적 잠금

세고 넣는 사이에 다른 요청이 끼어들지 못하도록 사용자 행을 `PESSIMISTIC_WRITE`(`SELECT ... FOR UPDATE`)로 잠근다.

- 표준 SQL이라 DB 종속이 없다. advisory lock은 PostgreSQL 전용이라 쓰지 않는다.
- `users` 행은 OAuth 최초 로그인 저장 외에 갱신되는 곳이 없어 경합이 없다.
- **사용자 행이 없으면 `IllegalStateException`으로 실패시킨다(500).** 인증된 요청의 `ownerId`는 항상 `users`에 있으므로 부재는 인증과 데이터가 어긋난 비정상이다. 상한 검사를 건너뛰는 fail-open은 이 기능의 목적과 반대라 택하지 않는다. `paper.owner_id`·`chat_session.owner_id`에 `users` FK를 두는 것은 이번 범위 밖이다.
- **잠금 획득 직후, 세기 전에 `rejectDuplicate`를 다시 판정한다.** 새 세션 요청은 세션 잠금이 없어(요청마다 새 UUID) 같은 `clientMessageId`의 두 요청이 모두 첫 판정을 통과한 뒤 사용자 잠금에서만 직렬화된다. 재판정 없이 세면 앞선 요청이 마지막 슬롯을 채웠을 때 재전송이 `DUPLICATE_MESSAGE` 대신 429를 받는다. 잠금을 잡은 시점의 조회는 앞선 커밋을 반드시 보므로 재판정이 정확하다.
- **잠금 체인은 `chat_session → users → usage_bucket` 순으로 고정한다.** `usageService.reserve`가 이어서 월간 버킷 행을 잠그기 때문에 실제 체인은 세 단계다. 세 행 중 둘 이상을 한 트랜잭션에서 잠그는 다른 코드는 현재 없다(문서 등록은 버킷만, 사용량 정산은 원장 행만). 역순으로 잠그는 트랜잭션을 만들면 교착이 나므로 이 규칙을 `ChatCommandService` 주석에 남긴다.
- 새 세션 경로는 세션 잠금 없이 사용자 잠금부터 잡는다. 새 세션 UUID에는 경쟁 상대가 없어 순서 규칙과 충돌하지 않는다.
- 잠금 대기 timeout은 두지 않는다. 이 트랜잭션은 DB 작업만 하고 커밋하며(AI 호출은 커밋 뒤) 세션·버킷 잠금과 같은 방식이다. DB가 던지는 잠금 예외(교착 감지 등)는 기존 방침대로 500으로 둔다.

재검토 조건: `users` 행을 요청마다 갱신하는 기능(마지막 활동 시각 등)이 생기거나 순서 규칙을 지키기 어려운 코드가 생기면 사용자별 잠금 전용 행으로 옮긴다. 잠그는 리포지토리 호출 한 줄과 테이블 하나가 바뀐다.

## 4. BE 변경

### `ChatCommandService.start` 흐름

```text
validateChatReady                         (기존)
rejectDuplicate                           (기존, 잠금 전 빠른 탈출)
resolveSession + 세션 행 잠금              (기존, 기존 세션일 때)
rejectDuplicate 재판정                     (기존, 기존 세션일 때)
세션 내 GENERATING → 409                   (기존)
users 행 잠금                              (신규)
rejectDuplicate 재판정                     (신규, 새 세션·기존 세션 모두)
사용자 전체 GENERATING 수 ≥ 상한 → 429     (신규)
usageService.reserve (usage_bucket 잠금)   (기존)
user·assistant 메시지 저장                 (기존)
```

### 신규·변경 코드

| 위치 | 변경 |
|---|---|
| `common/error/ErrorCode` | `CHAT_CONCURRENCY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS)` |
| `user/domain/UserRepository` | `@Lock(PESSIMISTIC_WRITE) @Query("select u from User u where u.id = :id") Optional<User> findWithLockById(UUID id)` — `ChatSessionRepository.findWithLockById`와 같은 형태 |
| `chat/domain/ChatMessageRepository` | `@Query("select count(m) from ChatMessage m where m.session.ownerId = :ownerId and m.role = :role and m.status = :status") long countBySessionOwnerIdAndRoleAndStatus(UUID ownerId, ChatMessageRole role, ChatMessageStatus status)` — `deletedAt` 조건 없음 |
| `chat/service/ChatCommandService` | 위 흐름의 신규 세 단계. 잠금 체인 주석. 오류 메시지: "동시에 진행 중인 답변이 N개입니다. 하나가 끝나면 다시 시도하세요." |
| `plan/infra/PlanProperties` | `Chat chat` 중첩 레코드, `maxActiveSessions`(null·0 이하 거부). 플랜별이 아니라 공통 |
| `application.yml` | `plan.chat.max-active-sessions: 3` |
| `be/docs/db/chat.sql` | `create index ix_chat_session_owner on chat_session (owner_id)` |

정책값을 `PlanProperties`에 두는 이유는 FT-011의 정책이고 배포 설정으로 덮는 방식이 같기 때문이다. 플랜별로 달라지면 `policy` 맵 쪽으로 옮긴다.

### 인덱스

세는 쿼리는 `chat_session.owner_id`로 세션을 좁힌 뒤 `chat_message.session_id`로 조인한다. 현재 `chat_session`에 `owner_id` 인덱스가 없어 사용자 잠금을 쥔 채 세션 테이블을 훑게 되므로 인덱스를 추가한다. 세션을 좁힌 뒤 매칭되는 `GENERATING` 행은 사용자당 최대 상한 개수라 `chat_message(role, status)` 인덱스는 두지 않는다. 구현 시 `EXPLAIN`으로 세션 인덱스 사용을 확인한다.

DDL은 로컬 스키마 재생성으로 반영하고, dev DB에는 배포 절차대로 수동 반영한다. prod는 아직 스택이 없으므로 첫 배포 전 반영 대상으로 운영 런북에 기록한다.

## 5. 수용하는 엣지 케이스

상한을 넘기는 방향이 아니라 일시적으로 더 보수적인 결과가 나오는 경우다. 코드로 없애지 않고 여기 남긴다.

- **커밋 직후 서버 사망**: `start` 커밋 뒤 스트리밍 시작 전에 서버가 죽으면 `GENERATING` 행이 남아 정체 정리(`plan.cleanup.chat-generating-deadline`, 30분)가 `FAILED`로 내릴 때까지 슬롯 하나를 차지한다. 그동안 그 사용자의 실효 상한은 1 줄어든다.
- **완료와 진입의 동시 발생**: 종결 전이는 사용자 잠금을 잡지 않는다(수를 줄이는 방향이라 상한 보장에 불필요). 완료 커밋 직전에 들어온 시작은 아직 상한으로 세어 429를 받을 수 있다. FE가 재시도를 허용하므로 다시 보내면 통과한다. 종결 전이에 사용자 잠금을 추가하면 스트리밍 종료가 채팅 시작과 경합하게 되어 택하지 않는다.

## 6. 계약과 FE

- openapi: 429 응답 설명에 코드 추가, 오류 코드 목록·enum 등록. 본문은 기존 `Error`(code·message).
- FE `chat/chatStream.ts`: HTTP 오류는 `retryable: false`가 기본인데 이 코드만 `retryable: true`. 다른 세션 답변이 끝나면 같은 내용으로 다시 보낼 수 있어야 한다.
- FE `TutorPanel`: `canRetry`가 `retryable`을 이미 보므로 변경 없음. 문구는 서버 메시지를 그대로 쓴다. plan 재조회는 불필요하다(사용량이 변하지 않는다).

## 7. 테스트

BE 통합 테스트(신규 파일). 채팅 시작을 부르는 픽스처는 `users` 행을 함께 만든다. 기존 `ChatCommandService` 통합 테스트도 사용자 행 없이 시작하면 잠금을 못 잡으므로 픽스처를 같이 고친다.

상한 판정:
1. 세션 3개가 각각 `GENERATING`이면 4번째 세션의 시작이 429이고, 세션·메시지·`usage_record`·논문 접근 기록이 추가로 생기지 않는다.
2. 그중 하나를 `COMPLETED`로 전이하면 시작이 통과한다.
3. 논리 삭제된 세션의 `GENERATING`도 활성으로 센다.
4. 세는 조건: 다른 사용자의 `GENERATING`, `COMPLETED`·`FAILED` assistant, `USER` role 행은 세지 않는다.
5. 같은 세션 재요청은 사용자 전체 검사 전에 `CHAT_RUN_IN_PROGRESS`(409)로 끝난다.

경합:
6. 활성 2개에서 서로 다른 새 세션 시작 2건을 동시에 보내면 정확히 하나만 성공한다. 기존 세션 2건 조합도 같다.
7. 같은 `clientMessageId`의 새 세션 시작 2건이 동시에 들어오면 진 쪽은 429가 아니라 `DUPLICATE_MESSAGE`를 받는다.
8. `reserve` 뒤 assistant 저장 전에 실패를 주입하면 롤백으로 월간 사용량과 동시성 슬롯이 모두 풀린다.

기타:
9. 사용자 행이 없으면 `IllegalStateException`.
10. `PlanPropertiesTest`: `chat.max-active-sessions` null·0·음수 거부, 정상 바인딩.
11. HTTP 수준: 상태 429, `code`, `message` 단언.

FE:
12. `chatStream.test.ts`: 이 코드에서 `retryable: true`. `TutorPanel`의 재시도 UI는 `retryable`을 이미 따르므로 별도 테스트를 두지 않는다.

## 8. 순서

1. project-docs PR: FT-011 Story 4 신설·Out of Scope·Resolved·Done Criteria 개정, openapi 오류 코드 추가.
2. app PR: 이 스펙, BE, DDL 인덱스, FE, 테스트. `## 의존`에 project-docs PR.
3. dev DB에 인덱스 DDL 반영. prod 첫 배포 전 반영 대상으로 운영 런북에 기록.
4. Jira YMC-326 본문에 상한 3·플랜 공통·정책값·오류 코드 반영.
