# 채팅 세션 히스토리 조회·삭제 (YMC-260) 설계

- Jira: [YMC-260](https://geunhh.atlassian.net/browse/YMC-260) — [APP] 채팅 세션 히스토리 조회·삭제 구현
- 선행 설계: [2026-07-23 채팅 SSE 스트리밍 설계](2026-07-23-chat-sse-streaming-design.md) — 엔티티·잠금·상태 전이는 그 설계를 전제한다.
- 범위: **계약(project-docs) + BE만.** FE(세션 목록 UI·재방문 로드·삭제 확인)는 YMC-289 FE 리디자인 머지 후
  별도 작업으로 한다 — history UI가 289의 TutorPanel 위에 얹히기 때문(TutorPanel.tsx 픽션 조정 2호 주석 참조).
- 브랜치: app은 `main`에서 `YMC-260-chat-session-history` 분기. 계약은 project-docs PR 선행 (contract-first).

## 1. 계약 (frontend-backend/openapi.yaml)

operation 3개를 추가한다. 기존 `/api/papers/{paperId}/chat/messages`의
"session message 조회 API는 별도 OpenAPI operation으로 정의해야 한다" 메모를 실제 참조로 갱신한다.

### GET /api/papers/{paperId}/chat/sessions — 세션 목록

- 200: `ChatSessionSummary` 배열. `lastMessageAt` 내림차순. 페이지네이션 없음(MVP — paper당 세션 수가 작다).
- `ChatSessionSummary`: `sessionId`(uuid) · `title`(string, 첫 user 질문 앞부분) · `lastMessageAt`(date-time) · `createdAt`(date-time). 전부 required.
- 메시지가 한 건도 없는 세션은 반환하지 않는다 — start 트랜잭션이 세션과 첫 메시지 쌍을 같은 commit으로
  저장하므로 정상 경로에서는 존재하지 않는 상태다.

### GET /api/papers/{paperId}/chat/sessions/{sessionId}/messages — 메시지 히스토리

- 200: `ChatMessageItem` 배열. `seq` 오름차순. 페이지네이션 없음(MVP).
- `ChatMessageItem`: `messageId`(uuid) · `role`(`USER`|`ASSISTANT`) · `content`(string, nullable — GENERATING·FAILED assistant는 null) · `status`(기존 `ChatMessageStatus` 재사용) · `seq`(integer) · `createdAt`(date-time).

### DELETE /api/papers/{paperId}/chat/sessions/{sessionId} — 세션 삭제

- 204: 세션과 소속 메시지를 함께 삭제. **GENERATING assistant가 있어도 삭제한다** — 진행 중이던 relay의
  조건부 UPDATE(`markCompleted`/`markFailed`)는 0행으로 끝나며 기존 설계가 이미 이 경쟁을 허용한다.

### 공통 에러 (새 코드 없음)

- 논문 없음: `PAPER_NOT_FOUND` 404. 타인 논문: `FORBIDDEN` 403 — 기존 chat/messages 계약·
  `validateChatReady`와 동일한 의미론(논문은 존재를 숨기지 않는다).
- 세션 없음·타인 세션·다른 논문의 세션: `CHAT_SESSION_NOT_FOUND` 404 (기존 의미론 — 세션은 존재를 숨긴다).
- 인증 실패: 기존 공통 401.

### 버전

호환되는 추가이므로 patch 올림 (README 규칙: 호환 필드 추가·설명 보완은 patch).

## 2. 스키마 변경 (ddl-auto: update가 반영)

### ChatMessage.seq — 세션 내 단조 증가

user·assistant 쌍이 같은 `Instant now`로 저장돼 `created_at`만으로는 쌍 안의 순서가 불안정하다.
`seq int not null`을 추가하고 히스토리 정렬 키로 쓴다.

- 부여: start 트랜잭션에서 user=`max(seq)+1`, assistant=`max(seq)+2`. 기존 세션은 PESSIMISTIC_WRITE
  잠금이 이미 시작을 직렬화하므로(선행 설계 §3) max 조회·부여에 경쟁이 없다. 새 세션은 1·2 고정.
- 인덱스 `ix_chat_message_session_created`는 `(session_id, seq)`로 교체한다 — 조회 정렬 키와 일치시킨다.

### ChatSession.title · lastMessageAt — 목록용 비정규화

- `title varchar(120)`: 첫 user 질문의 앞 120자(코드포인트 기준 truncate). 세션 생성 시 1회 저장, 이후 불변.
- `last_message_at`: 메시지 쌍 저장 시 갱신. start 트랜잭션이 세션 행을 이미 잠그고 있으므로 추가
  경쟁·비용이 없다. 목록 정렬 키.
- 갱신은 엔티티 메서드로 (`recordActivity(now)` 등 의도가 드러나는 이름, be/CLAUDE.md 규칙).

### 기존 dev 데이터

dev 단계라 마이그레이션 없이 간다. `ddl-auto: update`는 not null 컬럼 추가 시 기존 행 때문에 실패할 수
있으므로 로컬 DB는 리셋한다(compose volume 삭제). 운영 데이터 없음.

## 3. BE 구현

### 구성 (컨텍스트 `com.ymc.chat` 안에서)

| 단위 | 역할 |
|---|---|
| `api/ChatController` | endpoint 3개 추가 (GET sessions, GET messages, DELETE session). HTTP↔DTO 변환만 |
| `api/dto/ChatSessionSummaryResponse` · `ChatMessageItemResponse` | 계약 스키마 1:1 |
| `service/ChatQueryService` (신규) | 목록·히스토리 조회. 읽기 전용 트랜잭션 |
| `service/ChatCommandService.deleteSession()` (추가) | 검증 + bulk delete. 쓰기 트랜잭션 |
| `domain/ChatSessionRepository` | `findAllByOwnerIdAndPaperIdOrderByLastMessageAtDesc` 파생 쿼리 추가 |
| `domain/ChatMessageRepository` | `findAllBySessionIdOrderBySeqAsc` · `deleteBySessionId`(bulk `@Modifying`) · `findMaxSeqBySessionId` 추가 |

### 권한 검증 경로

- 논문 검증: 기존 `PaperChatAccessValidator.validateChatReady`는 "채팅 가능 상태(파싱 완료)"까지 검증한다.
  조회·삭제는 논문 상태와 무관해야 하므로(파싱 재처리 중이어도 과거 대화는 보여야 한다) **소유만 검증하는
  메서드를 validator에 추가**해 쓴다 (`validateOwned(paperId, ownerId)` — 없으면 PAPER_NOT_FOUND 404,
  타인 소유면 FORBIDDEN 403. `validateChatReady`에서 상태 검증만 뺀 것).
- 세션 검증: `ChatCommandService.resolveSession`과 같은 규칙 — ownerId·paperId 불일치는 존재를 숨기고
  `CHAT_SESSION_NOT_FOUND` 404. 조회·삭제 공통이므로 재사용 가능한 private/헬퍼로 둔다.

### 삭제 트랜잭션

`deleteBySessionId`(bulk) → `chatSessionRepository.delete(session)` 순서, 단일 트랜잭션.
DB cascade·JPA cascade에 의존하지 않는다(엔티티에 컬렉션 매핑을 추가하지 않는다).

### start() 변경

`ChatCommandService.start`에 seq 부여(`findMaxSeqBySessionId` 사용), 새 세션 title 저장,
`session.recordActivity(now)` 호출을 추가한다. 기존 멱등·잠금 로직은 건드리지 않는다.

## 4. 테스트 전략 (AC 매핑)

선행 설계의 스타일(MockMvc 통합 + Testcontainers PG)을 따른다. 신규 테스트 클래스
`ChatSessionHistoryIntegrationTest` 하나에 모은다.

- 목록: 내 세션만, `lastMessageAt` 내림차순, title·시각 필드 정확성.
- 히스토리: seq 오름차순, user/assistant status·content 보존(GENERATING이면 content null).
- 삭제: 204 후 세션·메시지 모두 없음. GENERATING 중 삭제도 성공.
- 권한: 타인 세션 조회·삭제 404 CHAT_SESSION_NOT_FOUND, 타인 논문 403 FORBIDDEN,
  없는 논문 404 PAPER_NOT_FOUND, 다른 논문의 sessionId 조합 404.
- seq 부여: 같은 세션 연속 질문 2회 → seq 1,2,3,4 단조 증가 (기존 start 경로 회귀 포함).

## 5. 결정 기록

| 결정 | 선택 | 근거 |
|---|---|---|
| 정렬 보장 | `seq` 컬럼 추가 (vs createdAt+role 타이브레이크) | 쌍이 같은 createdAt — 암묵 규칙 대신 명시적 정렬 키. 세션 잠금으로 부여 비용 0 |
| GENERATING 중 삭제 | 허용 (vs 409 거부) | relay 조건부 UPDATE가 이미 소실을 허용. 단순하고 사용자 의도에 부합 |
| 목록 데이터 | 세션에 title·last_message_at 비정규화 (vs 조회 시 subquery) | start가 세션 행을 이미 잠가 갱신 무비용. 목록 쿼리 단순화 |
| 페이지네이션 | 없음 (MVP) | paper당 세션 수 작음. 필요 시 계약 patch로 추가 |
