# 채팅 SSE 스트리밍 (FE↔BE↔AI) 설계

- 날짜: 2026-07-23
- 범위: YMC-256 (BE 채팅 API·영속화) / YMC-257 (BE AI SSE client·relay) / YMC-258 (FE 채팅 UI·SSE 처리)
- 제외: YMC-260 (세션 히스토리 조회·삭제 — 다음 사이클), 사용량(rate limit) 제한
- 계약 SSOT:
  - FE↔BE: `project-docs/contracts/frontend-backend/openapi.yaml` — `POST /api/papers/{paperId}/chat/messages` (`ChatSseEvent`, `x-sse-lifecycle`, `x-upstream-event-mapping`)
  - BE↔AI: `project-docs/contracts/backend-ai/sse/simple-agent-run-stream.yml`
- 결정 근거: `project-docs/decisions/ADR-004-chat-streaming-be-relay.md`
- AI 서버: `POST /api/v1/agents/simple-agent/runs/stream` 구현 완료 (YMC-245, `ai/app/modules/simple_agent/router.py`)

## 1. 아키텍처

```
FE (React)                    BE (Spring MVC)                        AI (FastAPI)
─────────                     ────────────────                       ────────────
fetch POST                    ChatController
/api/papers/{id}/chat  ──►      │ 인증·권한·멱등 검증
  ReadableStream                │ user msg + GENERATING 저장 (tx commit)
  + TextDecoder                 │ SseEmitter 반환, message.started 전송
  + sseParser                   │
                              ChatStreamRelay (AiStreamListener)
                                │ AiAgentStreamPort.stream(...)  ──►  POST /runs/stream
                                │   (WebClient 어댑터)           ◄──   run.started
                                │ delta: publishOn(전용 executor)◄──   message.delta × N
  message.delta  ◄──────────    │   → emitter.send + 상한 누적   ◄──   message.completed
  message.completed ◄───────    │ 완료: COMPLETED commit 후 전송 ◄──   run.completed
                                │ 실패: FAILED commit 후 error   ◄──   run.failed
```

### 핵심 결정

| # | 결정 | 근거 |
|---|---|---|
| D1 | FE→BE 소비는 `fetch` + `ReadableStream` | POST + 인증 헤더가 필요해 EventSource 불가 (계약 명시) |
| D2 | BE→FE는 `SseEmitter` (MVC 유지) | 기존 스택 유지, YMC-257 티켓 명시 |
| D3 | BE→AI는 **WebClient + `publishOn` 핸드오프** | SSE 파싱을 프레임워크가 제공. event loop는 논블로킹 유지, 블로킹 작업(emitter.send·DB)은 전용 executor에서. `publishOn`의 prefetch가 자연스러운 backpressure 제공. `spring-boot-starter-webflux`는 클라이언트 용도로만 추가 |
| D4 | AI 호출은 `AiAgentStreamPort` 인터페이스 뒤에 격리 | 기존 `service/port` 패턴(FileStorage 등)과 동일. 256은 fake, 257이 WebClient 구현으로 교체. reactive 타입을 포트에 노출하지 않아 service 계층은 동기 유지 |
| D5 | 대화 원본은 BE 단일 writer, delta는 메모리 누적 | ADR-004. 토큰별 DB write 금지, 최종 저장은 `message.completed` 기준 |
| D6 | `GENERATING → COMPLETED/FAILED` 전이는 리포지토리 조건부 UPDATE | relay 정상 완료와 timeout 워치독이 경쟁 가능 — `WHERE status='GENERATING'` 가드로 한쪽만 성공 (paper D2 규칙 준용) |
| D7 | 타임아웃은 idle(침묵)과 deadline(총 시한) 이원화 | 오래 걸리지만 delta가 흐르는 정상 작업을 죽이지 않으면서, 행(hang)은 빠르게 감지 |

### 로컬 개발·검증 토폴로지

- BE 설정 `ai.base-url` — local 프로파일은 로컬 uvicorn 주소(AI 서버 별도 프로세스).
- `infra/local/docker-compose.yml`에는 AI 서비스 없음(현행 유지).
- 자동화 테스트는 fake/MockWebServer, 진짜 AI 연결은 각 티켓 DoD의 수동 E2E로 검증.

## 2. 브랜치·작업 순서

| 순서 | 브랜치 | 내용 |
|---|---|---|
| 1 | `YMC-256-chat-api-persistence` | 엔티티·API·영속화 + fake port |
| 2 | `YMC-257-ai-sse-relay` | WebClient 어댑터 + relay (256 머지 후) |
| 3 | `YMC-258-fe-chat-ui` | FE 파서·스트림 클라이언트·UI (256 머지 후 fake 기반 병행 가능) |

커밋은 `[YMC-XXX] type(scope): subject` 컨벤션.

## 3. YMC-256 — 도메인·영속화·API

### 컨텍스트

`com.ymc.chat` 신설 (새 최상위 컨텍스트 — 승인됨). `api / service / service/port / domain / infra/ai`.

### 엔티티

**ChatSession** — `chat_session`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | UUID | BE가 insert 전 생성. AI `thread_id`로 그대로 전달 |
| owner_id | UUID | user 컨텍스트 ID 참조 (컨텍스트 간 `@ManyToOne` 금지) |
| paper_id | UUID | paper 컨텍스트 ID 참조. 생성 시 고정 |
| created_at | Instant | |

정적 팩토리 `ChatSession.open(ownerId, paperId, now)`.

**ChatMessage** — `chat_message`

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | UUID | 계약의 `messageId` |
| session | `@ManyToOne(LAZY)` | 같은 컨텍스트 내부 — 연관관계 허용, LAZY 명시 |
| role | enum `USER`/`ASSISTANT` | |
| content | TEXT | user는 즉시, assistant는 완료 시 1회 저장 |
| status | enum `GENERATING`/`COMPLETED`/`FAILED` | user 메시지는 생성 즉시 COMPLETED |
| client_message_id | UUID nullable | user 메시지만. **unique 제약** = 멱등 근거 |
| created_at | Instant | 세션 내 정렬 기준 |
| completed_at | Instant nullable | assistant 종결 시각 |

스키마는 기존 방식대로 JPA `ddl-auto`(local `update` / prod `validate`)로 관리. 인덱스: `chat_message(session_id, created_at)`.

### API 흐름

```
POST /api/papers/{paperId}/chat/messages
 1. JWT 인증(401) → paper 존재·소유(404/403) → paper 학습 가능 상태 검증
 2. sessionId 있음 → 소유·paper 일치 검증(404 CHAT_SESSION_NOT_FOUND)
    sessionId 없음 → ChatSession.open
 3. clientMessageId 중복 → 새 run 미생성 (멱등)
 4. [한 트랜잭션] user message(COMPLETED) + assistant message(GENERATING) 저장 → commit
 5. commit 후 SseEmitter 반환 + message.started 전송
 6. AiAgentStreamPort.stream(...) 시작 — 256에서는 fake 구현
```

- 4→5 순서 보장을 위해 트랜잭션 서비스(`ChatCommandService`)와 스트리밍 시작(`ChatStreamRelay`)을 분리한다. `@Transactional` 메서드 종료(commit) 후 relay가 비동기 시작.
- 세션당 동시 실행 1개: "GENERATING assistant 존재 시 거부" 조회 검증으로 시작. 완전한 race-free(partial unique index)는 ddl-auto 한계로 미룸 — 현 규모에서 조회 검증 + client_message_id unique로 충분하다고 판단.

### 포트

```java
// service/port — 프레임워크 중립 콜백 스타일
public interface AiAgentStreamPort {
    AiRunHandle stream(AiRunRequest request, AiStreamListener listener);
}
public interface AiStreamListener {
    void onRunStarted();
    void onDelta(String delta);
    void onMessageCompleted(String message);
    void onRunCompleted();
    void onRunFailed(String error);
    void onTransportError(Exception cause);
}
// AiRunHandle.cancel() — 워치독이 호출
```

fake 구현(고정 delta 몇 개 → completed → run.completed 순 콜백)은 256에서 `infra/ai`에 두고, 257에서 WebClient 어댑터가 본 구현이 되면 테스트 스코프로 이동.

### 계약 보완 필요 (contract-first)

- **중복 `clientMessageId` 응답이 계약에 미정** — `409 + code DUPLICATE_MESSAGE` 제안. 구현 전 `project-docs/contracts/frontend-backend/openapi.yaml`에 409 응답 추가 PR 선행.
- 세션당 동시 실행 거부 응답(409 계열)도 같은 PR에서 정의.
- 파싱 미완료 paper의 거부 응답 코드가 계약에 정의돼 있는지 확인, 미정이면 같은 PR에서 정의.

## 4. YMC-257 — WebClient 어댑터 + relay

### 구성

```
service/ChatStreamRelay          오케스트레이터 (AiStreamListener 구현)
infra/ai/AiAgentWebClientAdapter AiAgentStreamPort 구현 (WebClient)
infra/ai/AiWebClientConfig       WebClient 빈 + ai.base-url
```

### reactive 체인

```java
webClient.post().uri("/api/v1/agents/simple-agent/runs/stream")
    .bodyValue(new AiRunRequest(threadId, message))
    .retrieve()
    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
    .timeout(idleTimeout)            // 이벤트 사이 침묵 감지
    .publishOn(chatRelayScheduler)   // 이후 블로킹 허용
    .subscribe(ev -> dispatch(ev, listener),
               err -> listener.onTransportError(err),
               () -> onUpstreamEof(listener));
```

### 스레딩 모델

- event loop k개(코어 수): 모든 WebClient 연결이 공유, 논블로킹 유지 — 여기서 블로킹 금지.
- `chatRelayScheduler`: 전용 executor 기반(`Schedulers.fromExecutorService`). Reactor는 **구독 하나 안에서** 신호를 직렬화하므로 스트림 내 delta 순서 보장. 서로 다른 스트림은 풀 크기까지 병렬.
- backpressure: `publishOn` prefetch(기본 256)만큼만 upstream에 요청 → worker가 느리면 요청이 줄어 TCP까지 압력 전달. 별도 버퍼 정책 불필요.
- 구독의 `Disposable`을 `AiRunHandle`로 감싸 반환. `cancel()` → dispose → 연결 종료 → AI가 LLM 생성 취소 (ADR-004).

### 이벤트 매핑 (계약 `x-upstream-event-mapping`)

| AI 이벤트 | relay 동작 | FE 전송 |
|---|---|---|
| `run.started` | 내부 로깅만 | — |
| `message.delta` | 상한 있는 StringBuilder 누적 | `message.delta` 즉시 |
| `message.completed` | 최종 content 후보 보관 (성공 아님) | — |
| `run.completed` | completed 수신 확인 → 조건부 UPDATE로 COMPLETED commit | commit 후 `message.completed`(messageId + 전체 content) → emitter 정상 종료 |
| `run.failed` | FAILED commit. raw error 미노출 | `error`(AI_RUN_FAILED, retryable) |

프로토콜 위반 매핑:

- `message.completed` 없이 `run.completed`, 또는 completed 후 `run.completed` 없이 EOF → `AI_PROTOCOL_ERROR`
- terminal 없이 EOF (transport 오류 포함) → `AI_STREAM_DISCONNECTED`
- deadline 도달 → 워치독 dispose → `AI_TIMEOUT`

세 경우 공통: partial delta 저장 안 함 → FAILED commit → FE `error` → emitter 종료. FAILED commit조차 실패하면 terminal 없이 닫고 경보 로그 (계약 명시).

- 누적 delta ≠ `message.completed.message`이면 경고 로그 남기고 completed 우선 (ADR-004).

### 타이머 4종

| 타이머 | 값(안, 설정 프로퍼티) | 역할 |
|---|---|---|
| idle timeout | `chat.stream.idle-timeout=60s` | AI 이벤트 사이 침묵 감지 — 행 스트림 조기 정리 (`Flux.timeout`) |
| deadline | `chat.stream.deadline=10m` | 총 시한 안전망 — 워치독(ScheduledExecutor)이 dispose. 인프라 timeout보다 짧게 |
| heartbeat | 15s (계약 고정) | FE 방향 마지막 outbound 이후 15s 침묵 시 `heartbeat` event 생성 |
| SseEmitter timeout | deadline + 여유 | MVC async timeout이 워치독보다 먼저 발화하지 않도록 |

### FE disconnect

`emitter.onCompletion/onError/onTimeout` → `feConnected=false` 플래그만. 이후 FE 전송 스킵, **upstream 소비·최종 저장은 deadline 안에서 계속** (계약 `frontendDisconnect`). FE 사유로 FAILED 전이하지 않음.

### 구현 시 context7로 확인

- reactor-netty `responseTimeout`이 스트리밍 응답에서 어느 시점 기준인지 (헤더 vs 전체 바디) — 잘못 걸면 정상 스트림이 끊김.
- `bodyToFlux(ServerSentEvent)`의 정확한 시그니처·event 이름 노출 방식.
- `Flux.timeout`이 첫 이벤트 전 대기에도 적용되는지 (첫 신호 전 침묵 = 연결 후 run.started 지연).

## 5. YMC-258 — FE 파서·스트림 클라이언트·UI

### 파일 구성

```
fe/src/
├── api.js              (기존 유지)
├── chat/
│   ├── sseParser.js    SSE frame 증분 파서 — 순수 함수, UI 무관
│   ├── chatStream.js   POST + ReadableStream 소비 → 콜백
│   └── ChatPanel.jsx   메시지 목록 + 입력창 + 스트리밍 표시
└── App.jsx             ChatPanel 연결
```

### sseParser.js

계약 `x-stream-handling.frontend`의 함정을 파서가 흡수:

- `TextDecoder('utf-8', {stream: true})` — network chunk가 UTF-8 문자 중간에서 잘려도 안전.
- 내부 버퍼 누적, **빈 줄에서만 frame 확정** → `event:` + `data:` 조립 → `JSON.parse`.
- 형태: `push(chunk) → 완성된 event 배열` 순수 함수 — vitest 단위 테스트 용이.

### chatStream.js — 종결 판정

- `clientMessageId = crypto.randomUUID()` 요청마다 생성.
- HTTP 비-200 → 기존 `apiError` 헬퍼 재사용.
- `message.completed` → 성공 / `error` → 실패(code·retryable 전달) / **terminal 없는 EOF → 실패** / `heartbeat` → 무시.
- 인증은 기존 api.js 방식(같은 오리진 fetch, 쿠키) 그대로 — Bearer 전환 시 함께 변경.

### ChatPanel 상태 모델

```
messages: [{ messageId, role, content, status }]
sessionId: message.started에서 저장 → 후속 질문에 재사용
```

| 이벤트 | UI |
|---|---|
| 전송 직후 | user 메시지 + assistant placeholder(GENERATING) |
| `message.started` | placeholder에 messageId 부여, sessionId 저장 |
| `message.delta` | 해당 메시지 content에 append |
| `message.completed` | 전체 content로 replace, COMPLETED |
| `error`/EOF | FAILED 표시, `retryable`이면 재시도 버튼 |

- 생성 중 입력창 비활성화 (세션당 동시 1개와 일관).
- content는 plain text 렌더(`white-space: pre-wrap`). markdown 렌더링은 follow-up.
- 언마운트 시 `AbortController.abort()` — BE는 저장을 완주하므로 안전.

## 6. 테스트 전략

### YMC-256 — MockMvc + fake port (Testcontainers PG)

- 성공 SSE 흐름: fake delta → `started → delta → completed` 순서·payload.
- 인증·권한: 401 / 남의 paper 403 / 없는 paper 404 / 남의·없는 session 404.
- 파싱 미완료 paper 거부 (상태 조건은 구현 시 PaperStatus 확인).
- 멱등: 같은 clientMessageId 재전송 시 row 미중복.
- 세션 연속성: sessionId 재사용 시 같은 세션 축적.
- commit-then-started: fake 콜백 시점 DB 조회로 GENERATING commit 확인.
- 조건부 UPDATE 경쟁: `→COMPLETED` vs `→FAILED` 한쪽만 성공.

### YMC-257 — MockWebServer(가짜 AI) + relay 통합

계약 `x-contract-test-cases` 직역:

- 정상 시퀀스 → COMPLETED 저장 + FE `message.completed`.
- `run.failed` → FAILED + `error(AI_RUN_FAILED)`, raw error 미노출.
- `message.completed` 없이 `run.completed` → `AI_PROTOCOL_ERROR`.
- terminal 없이 EOF → `AI_STREAM_DISCONNECTED`.
- 침묵 → idle timeout → `AI_TIMEOUT` (awaitility로 FAILED 대기).
- FE disconnect 후 upstream 완주·COMPLETED 저장 (ADR-004 follow-up 항목).
- 누적 ≠ completed → 경고 로그 + completed 우선.

### YMC-258 — vitest

- sseParser: 한글 UTF-8 chunk 분할 / frame이 chunk 경계에 걸침 / 한 chunk에 다수 frame / heartbeat 무시.
- chatStream: mock ReadableStream — 콜백 순서, error event, terminal 없는 EOF → 실패, HTTP 4xx → apiError.
- ChatPanel: delta append → completed replace, FAILED + 재시도 렌더.

### 수동 E2E (각 티켓 DoD)

1. 257: BE + 로컬 AI(uvicorn) — 실제 스트리밍, deadline 강제 발동 시 AI 생성 취소 로그 확인.
2. 258: FE→BE→AI 전체 — 질문 → 스트리밍 → 같은 세션 후속 질문.
3. 한계: 재접속 후 메시지 조회는 조회 API(YMC-260) 부재로 불가 — DB 조회로 저장만 검증.

## 7. Follow-ups (이번 슬라이스 제외)

- **세션 히스토리 조회·삭제** — YMC-260. 조회 API 계약을 openapi.yaml에 먼저 추가.
- **오래된 GENERATING → FAILED 정리 배치** — BE 프로세스 재시작으로 남은 row (ADR-004 trade-off).
- **upstream heartbeat 계약 확장** — 긴 침묵이 정상인 에이전트 모듈(base_pdf_agent 등) 도입으로 idle timeout 오탐이 생기면, BE↔AI 계약에 heartbeat 이벤트를 추가해 침묵 감지를 생존 확인으로 전환. AI 구현 수정 필요.
- **markdown 렌더링** — completed의 `contentFormat: markdown` 대응.
- **사용량(rate limit) 제한** — 계약 언급 항목, 정책 미정.
- **partial unique index** — 세션당 GENERATING 1개의 race-free 보장 (마이그레이션 도구 도입 시).
