# YMC-257 AI SSE client·relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** YMC-256의 fake 포트를 실제 WebClient 어댑터로 교체하고, 타이머 4종(idle/deadline/heartbeat/emitter)·누적 상한·계약의 모든 프로토콜 실패 매핑을 완성해 BE↔AI 스트리밍을 실전 수준으로 만든다.

**Architecture:** `AiAgentStreamPort`의 WebClient 구현(`AiAgentWebClientAdapter`)이 reactive 체인(`bodyToFlux(ServerSentEvent) → timeout(idle) → publishOn(전용 scheduler)`)으로 AI SSE를 소비해 기존 콜백 리스너에 전달한다. `ChatStreamService`는 deadline 워치독·heartbeat·누적 상한·오류 코드 매핑을 얻는다. fake 어댑터는 `ai.fake-stream` 조건부 빈으로 남아 기존 통합 테스트를 지탱한다.

**Tech Stack:** spring-boot-starter-webflux(클라이언트 용도만) / Reactor / SseEmitter(기존) / 테스트: JDK `com.sun.net.httpserver` 기반 fake AI SSE 서버 (신규 테스트 의존성 없음)

**설계 SSOT:** `docs/superpowers/specs/2026-07-23-chat-sse-streaming-design.md` §4
**계약 SSOT:** FE↔BE `project-docs/contracts/frontend-backend/openapi.yaml` / BE↔AI `project-docs/contracts/backend-ai/sse/simple-agent-run-stream.yml`

## Global Constraints

- 계약이 코드보다 앞선다 — `AI_RESPONSE_TOO_LARGE`는 계약에 없으므로 Task 1(project-docs PR)이 선행·머지되어야 한다.
- BE↔AI wire 형식은 snake_case (`thread_id`) — BE 코드 컨벤션(camelCase)과 경계에서 변환한다.
- `AiAgentStreamPort`/`AiStreamListener` 시그니처는 YMC-256에서 동결 — 이번 티켓에서 바꾸지 않는다.
- reactive 타입(Flux 등)을 service 계층에 노출하지 않는다 (설계 D4). 어댑터 안에서만 쓴다.
- delta 토큰별 DB 저장 금지, partial 저장 금지, terminal event는 DB commit 후 전송 (ADR-004).
- heartbeat 15s는 계약 고정값 — 프로퍼티 기본값 15s, 테스트에서만 단축한다.
- 타이머 관계: `idle-timeout < deadline < emitter-timeout` (설계 §4 표). 프로퍼티 검증으로 강제.
- 커밋 `[YMC-257] type(scope): subject` 한 줄, 트레일러 금지.
- 새 runtime 의존성은 `spring-boot-starter-webflux` 하나뿐. 테스트 의존성도 추가하지 않는다 (fake 서버는 JDK 내장 HttpServer).

## 설계 대비 확정 사항 (context7 확인 결과 반영)

- **reactor-netty `responseTimeout`은 걸지 않는다** — 스트리밍 응답에서 발화 시점(헤더 vs 전체 바디)이 문서상 보장되지 않아 정상 장기 스트림을 끊을 위험이 있다. connect timeout(5s) + `Flux.timeout`(침묵) + relay 워치독(총 시한)이 전 구간을 덮는다.
- `Flux.timeout(Duration)`은 구독 시점부터 첫 신호까지도 적용된다 — "연결 후 run.started 지연"도 idle timeout이 잡는다.
- `bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})` — `event()`가 SSE event 이름, `data()`가 data 문자열.
- **fake 어댑터는 test 스코프 이동 대신 `ai.fake-stream` 조건부 빈으로 main에 유지** (설계와 다른 부분) — 이동하면 포트 빈 2개 충돌·스파이 대상 모호·컨텍스트 캐시 분열이 생긴다. 조건부 빈이 기존 IntegrationTest 구조를 그대로 지킨다.

## 사전 조건

- main에 YMC-256 머지 완료 (e68a3f1) — 확인: `git log origin/main --oneline -3`에 PR #18 머지 커밋.
- Docker 기동 중 (통합 테스트).

## File Structure (전체 조감)

```text
project-docs/contracts/frontend-backend/openapi.yaml     # Task 1: AI_RESPONSE_TOO_LARGE
app/be/build.gradle                                      # Task 2: webflux (Modify)
app/be/src/main/resources/application.yml                # Task 2: ai.*, chat.stream.* (Modify)
app/be/src/main/java/com/ymc/chat/
├── infra/
│   ├── ChatStreamConfig.java                            # Task 2: scheduler·timer 빈 (Create)
│   └── ai/
│       ├── AiProperties.java                            # Task 2 (Create)
│       ├── ChatStreamProperties.java                    # Task 2 (Create)
│       ├── AiWebClientConfig.java                       # Task 2 (Create)
│       ├── FakeAiAgentStreamAdapter.java                # Task 2: @ConditionalOnProperty (Modify)
│       └── AiAgentWebClientAdapter.java                 # Task 3 (Create)
├── api/
│   ├── ChatController.java                              # Task 5: emitter timeout 프로퍼티화 (Modify)
│   └── dto/ChatSseEventData.java                        # Task 5: Heartbeat record (Modify)
└── service/ChatStreamService.java                       # Task 4·5: 매핑·상한·타이머 (Modify)
app/be/src/test/java/com/ymc/
├── support/
│   ├── IntegrationTest.java                             # Task 2: ai.fake-stream=true (Modify)
│   └── FakeAiSseServer.java                             # Task 3: JDK HttpServer SSE 픽스처 (Create)
└── chat/
    ├── infra/AiAgentWebClientAdapterTest.java           # Task 3 (Create)
    └── api/AiRelayIntegrationTest.java                  # Task 4·5 (Create)
```

---

### Task 1: 계약 PR — AI_RESPONSE_TOO_LARGE (project-docs)

설계 §3 계약 보완 목록에 있었으나 이전 계약 PR(project-docs#16)에서 누락된 항목. 누적 상한 초과의 terminal `error` 코드를 계약에 먼저 넣는다. **project-docs 레포에서 작업.**

**Files:**
- Modify: `project-docs/contracts/frontend-backend/openapi.yaml`

**Interfaces:**
- Produces: `ChatStreamErrorDetail.code`에 `AI_RESPONSE_TOO_LARGE` (retryable: false) — Task 4의 relay 코드가 이 문자열을 그대로 쓴다.

- [ ] **Step 1: 브랜치 생성**

```bash
cd project-docs && git fetch origin && git switch -c YMC-257-response-too-large-contract origin/main
```

- [ ] **Step 2: ChatStreamErrorDetail enum·oneOf 확장**

`ChatStreamErrorDetail` 스키마의 `enum` 배열(`MESSAGE_PERSISTENCE_FAILED` 뒤)에 추가:

```yaml
            - AI_RESPONSE_TOO_LARGE
```

같은 스키마의 `oneOf` 목록 끝에 추가:

```yaml
        - title: AI 응답 길이 상한 초과
          properties:
            code:
              const: AI_RESPONSE_TOO_LARGE
            retryable:
              const: false
```

`description`(스키마 상단 주석부)에 한 줄 보충: `AI_RESPONSE_TOO_LARGE는 BE가 delta 누적 상한을 초과해 스트림을 중단한 경우다 — partial은 저장되지 않는다.`

- [ ] **Step 3: 버전 patch 올림 + YAML 검증**

`info.version` patch +1 (현재 0.2.1이면 0.2.2). 검증:

```bash
python3 -c "import yaml,io;yaml.safe_load(io.open('contracts/frontend-backend/openapi.yaml',encoding='utf-8'))" && echo YAML_OK
```

- [ ] **Step 4: 커밋·푸시·PR**

```bash
git add contracts/frontend-backend/openapi.yaml
git commit -m "[YMC-257] docs(contracts): 채팅 SSE error 코드 AI_RESPONSE_TOO_LARGE 추가"
git push -u origin YMC-257-response-too-large-contract
gh pr create --title "[YMC-257] docs(contracts): AI_RESPONSE_TOO_LARGE 추가" --body "..."
```

PR 본문은 팀 템플릿(Summary/Changes/Verification/Review Focus/Notes). **머지 확인 후 다음 태스크 진행** (contract-first).

---

### Task 2: app 브랜치 + webflux 의존성 + 설정 골격

**Files:**
- Modify: `app/be/build.gradle`
- Modify: `app/be/src/main/resources/application.yml`
- Create: `app/be/src/main/java/com/ymc/chat/infra/ai/AiProperties.java`
- Create: `app/be/src/main/java/com/ymc/chat/infra/ai/ChatStreamProperties.java`
- Create: `app/be/src/main/java/com/ymc/chat/infra/ChatStreamConfig.java`
- Create: `app/be/src/main/java/com/ymc/chat/infra/ai/AiWebClientConfig.java`
- Modify: `app/be/src/main/java/com/ymc/chat/infra/ai/FakeAiAgentStreamAdapter.java`
- Modify: `app/be/src/test/java/com/ymc/support/IntegrationTest.java`

**Interfaces:**
- Produces (Task 3·4·5가 사용):
  - `AiProperties(String baseUrl)` — prefix `ai`
  - `ChatStreamProperties(Duration idleTimeout, Duration deadline, Duration heartbeatInterval, int maxContentLength, Duration emitterTimeout)` — prefix `chat.stream`
  - 빈: `WebClient aiWebClient` / `Scheduler chatRelayScheduler` / `ScheduledExecutorService chatTimerExecutor`
  - fake 어댑터는 `ai.fake-stream=true`일 때만 등록, 실제 어댑터(Task 3)는 그 외 기본

- [ ] **Step 1: 브랜치 생성 + 계획 문서 커밋**

```bash
cd app && git fetch origin && git switch -c YMC-257-ai-sse-relay origin/main
git add docs/superpowers/plans/2026-07-23-ymc-257-ai-sse-relay.md
git commit -m "[YMC-257] docs(plans): AI SSE relay 구현 계획"
```

- [ ] **Step 2: build.gradle에 webflux 추가**

`implementation 'org.springframework.boot:spring-boot-starter-web'` 아래에:

```gradle
	// FT-007 채팅 — AI SSE 소비용 WebClient (클라이언트 용도만, 서버는 MVC 유지).
	// spring-boot-starter-web이 함께 있으면 auto-config는 MVC를 선택한다.
	implementation 'org.springframework.boot:spring-boot-starter-webflux'
```

- [ ] **Step 3: 프로퍼티 record 2개 작성**

```java
// chat/infra/ai/AiProperties.java
package com.ymc.chat.infra.ai;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml의 ai.* 바인딩 — AI 서버 연결 정보 (FT-007). */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(String baseUrl) {

    public AiProperties {
        Objects.requireNonNull(baseUrl, "ai.base-url은 필수다.");
    }
}
```

```java
// chat/infra/ai/ChatStreamProperties.java
package com.ymc.chat.infra.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 chat.stream.* 바인딩 — 스트림 타이머와 상한 (설계 §4 타이머 표).
 *
 * <p>idle(침묵 감지) < deadline(총 시한 안전망) < emitter(MVC async timeout) 순서가 지켜져야
 * 워치독이 인프라 timeout보다 먼저 발화한다. 어긋난 설정은 기동에서 실패시킨다.
 */
@ConfigurationProperties(prefix = "chat.stream")
public record ChatStreamProperties(
        Duration idleTimeout,
        Duration deadline,
        Duration heartbeatInterval,
        int maxContentLength,
        Duration emitterTimeout) {

    public ChatStreamProperties {
        if (idleTimeout.compareTo(deadline) >= 0) {
            throw new IllegalArgumentException("chat.stream.idle-timeout은 deadline보다 짧아야 합니다.");
        }
        if (deadline.compareTo(emitterTimeout) >= 0) {
            throw new IllegalArgumentException("chat.stream.deadline은 emitter-timeout보다 짧아야 합니다.");
        }
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("chat.stream.max-content-length는 양수여야 합니다.");
        }
    }
}
```

- [ ] **Step 4: application.yml에 설정 블록 추가**

`auth:` 블록 뒤에 (공통 영역):

```yaml
# FT-007 채팅 — AI 서버 연동과 스트림 타이머 (설계 §4)
ai:
  base-url: http://localhost:8000   # 로컬 AI 서버(uvicorn). prod는 내부망 주소로 덮는다
  fake-stream: false                # true면 고정 delta fake 어댑터 (자동화 테스트용)

chat:
  stream:
    idle-timeout: 60s               # AI 이벤트 사이 침묵 상한 — 행(hang) 감지
    deadline: 10m                   # 총 시한 안전망 — 워치독. 인프라 timeout보다 짧게 (ADR-004)
    heartbeat-interval: 15s         # FE 방향 침묵 시 heartbeat (계약 고정값 — 바꾸면 계약 위반)
    max-content-length: 65536       # delta 누적 상한 (문자 수). 초과 시 AI_RESPONSE_TOO_LARGE
    emitter-timeout: 11m            # MVC async timeout — deadline보다 커야 워치독이 먼저 발화
```

- [ ] **Step 5: 빈 설정 2개 작성**

```java
// chat/infra/ChatStreamConfig.java
package com.ymc.chat.infra;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ymc.chat.infra.ai.AiProperties;
import com.ymc.chat.infra.ai.ChatStreamProperties;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 채팅 스트림의 실행 자원 (설계 §4 스레딩 모델).
 *
 * <p>relay 작업(emitter.send·DB)은 event loop이 아니라 이 scheduler에서 실행된다.
 * virtual thread라 스트림 수만큼 점유해도 비용이 거의 없다.
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, ChatStreamProperties.class})
public class ChatStreamConfig {

    /** publishOn 핸드오프 대상. 종료 시 진행 중 작업까지 정리한다. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService chatRelayExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    Scheduler chatRelayScheduler(ExecutorService chatRelayExecutor) {
        return Schedulers.fromExecutorService(chatRelayExecutor);
    }

    /** deadline 워치독·heartbeat 타이머 전용. 작업이 즉시 반환되는 것만 올린다. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService chatTimerExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-stream-timer");
            t.setDaemon(true);
            return t;
        });
    }
}
```

```java
// chat/infra/ai/AiWebClientConfig.java
package com.ymc.chat.infra.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/** AI 서버용 WebClient (FT-007). */
@Configuration
public class AiWebClientConfig {

    /**
     * responseTimeout은 걸지 않는다 — 스트리밍 응답에서 발화 시점(헤더 수신 vs 전체 바디)이
     * 문서상 보장되지 않아 정상 장기 스트림을 끊을 위험이 있다. 침묵은 어댑터의
     * Flux.timeout(idle), 총 시한은 relay 워치독이 담당한다 (설계 §4).
     */
    @Bean
    WebClient aiWebClient(AiProperties aiProperties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        return WebClient.builder()
                .baseUrl(aiProperties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

- [ ] **Step 6: fake 어댑터를 조건부 빈으로**

`FakeAiAgentStreamAdapter.java`의 `@Component` 위에 추가하고 javadoc 갱신:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 고정 delta를 흘려보내는 fake 구현. ai.fake-stream=true일 때만 등록된다 —
 * 자동화 테스트가 AI 서버·LLM key 없이 SSE 경로를 검증하기 위한 것으로,
 * 실제 어댑터는 {@link AiAgentWebClientAdapter}다 (YMC-257).
 * ...기존 두 번째 문단 유지...
 */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "true")
public class FakeAiAgentStreamAdapter implements AiAgentStreamPort {
```

- [ ] **Step 7: IntegrationTest 베이스에 fake 활성화**

`support/IntegrationTest.java`의 `@SpringBootTest`를 다음으로 교체:

```java
@SpringBootTest(properties = "ai.fake-stream=true")
```

(기존 256 통합 테스트들은 계속 fake를 스파이로 쓴다. Task 4의 relay 테스트만 이 값을 `@DynamicPropertySource`로 뒤집는다 — 그쪽이 우선순위가 높다.)

- [ ] **Step 8: 컴파일 + 기존 스위트 확인**

```bash
cd be && ./gradlew compileJava -q && ./gradlew test
```

Expected: BUILD SUCCESSFUL — Task 3의 실제 어댑터가 아직 없으므로 `ai.fake-stream=true` 컨텍스트만 뜬다. 전체 그린이어야 한다.

- [ ] **Step 9: 커밋**

```bash
git add be/build.gradle be/src/main/resources/application.yml be/src/main/java/com/ymc/chat/infra be/src/test/java/com/ymc/support/IntegrationTest.java
git commit -m "[YMC-257] feat(chat): 스트림 설정·실행 자원과 fake 조건부 전환"
```

---

### Task 3: FakeAiSseServer 픽스처 + AiAgentWebClientAdapter

**Files:**
- Create: `app/be/src/test/java/com/ymc/support/FakeAiSseServer.java`
- Create: `app/be/src/main/java/com/ymc/chat/infra/ai/AiAgentWebClientAdapter.java`
- Test: `app/be/src/test/java/com/ymc/chat/infra/AiAgentWebClientAdapterTest.java`

**Interfaces:**
- Consumes: Task 2 빈들, 동결된 `AiAgentStreamPort.stream(AiRunRequest, AiStreamListener)` → `AiRunHandle`
- Produces:
  - `AiAgentWebClientAdapter` — `ai.fake-stream` false(기본)일 때의 포트 구현. Task 4·5의 통합 테스트가 이 빈을 통해 wire 레벨을 검증
  - `FakeAiSseServer` — `start()`, `close()`, `baseUrl()`, `enqueue(Script)`, `lastRequestBody()`. `Script.of(frames...)` + `Frame(String event, String dataJson, long delayMillis)`, `Script.thenHangMillis(long)` — frames가 끝나면 연결이 닫히므로 "terminal 없는 EOF"는 terminal frame을 빼면 된다. Task 4·5 테스트가 재사용

- [ ] **Step 1: FakeAiSseServer 작성**

JDK 내장 HttpServer로 SSE를 프레임 단위 지연·침묵·비정상 종료까지 스크립트할 수 있는 픽스처. 신규 의존성 없음.

```java
// test/java/com/ymc/support/FakeAiSseServer.java
package com.ymc.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * BE↔AI SSE 계약(simple-agent-run-stream.yml)을 흉내내는 테스트 전용 서버.
 * 프레임 단위 지연, terminal 없는 EOF, 장시간 침묵(행)을 스크립트로 재현한다.
 */
public final class FakeAiSseServer implements AutoCloseable {

    /** SSE 프레임 하나 — 전송 전 delayMillis만큼 침묵한다. */
    public record Frame(String event, String dataJson, long delayMillis) {
        public static Frame of(String event, String dataJson) {
            return new Frame(event, dataJson, 0);
        }
    }

    /** 응답 시나리오. frames 전송 후 hangMillis 침묵하고 연결을 닫는다. */
    public record Script(List<Frame> frames, long hangMillis) {
        public static Script of(Frame... frames) {
            return new Script(List.of(frames), 0);
        }

        public Script thenHangMillis(long millis) {
            return new Script(frames, millis);
        }
    }

    // --- 계약 payload 헬퍼 (data.type == event 이름 규칙) ---
    public static Frame runStarted(String threadId) {
        return Frame.of("run.started",
                "{\"type\":\"run.started\",\"thread_id\":\"" + threadId + "\"}");
    }

    public static Frame delta(String threadId, String delta) {
        return Frame.of("message.delta",
                "{\"type\":\"message.delta\",\"thread_id\":\"" + threadId + "\",\"delta\":\"" + delta + "\"}");
    }

    public static Frame messageCompleted(String threadId, String message) {
        return Frame.of("message.completed",
                "{\"type\":\"message.completed\",\"thread_id\":\"" + threadId + "\",\"message\":\"" + message + "\"}");
    }

    public static Frame runCompleted(String threadId) {
        return Frame.of("run.completed",
                "{\"type\":\"run.completed\",\"thread_id\":\"" + threadId + "\"}");
    }

    public static Frame runFailed(String threadId, String error) {
        return Frame.of("run.failed",
                "{\"type\":\"run.failed\",\"thread_id\":\"" + threadId + "\",\"error\":\"" + error + "\"}");
    }

    private HttpServer server;
    private final ConcurrentLinkedQueue<Script> scripts = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("fake AI 서버 기동 실패", e);
        }
        server.createContext("/", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Script script = scripts.poll();
            if (script == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // length 0 → chunked, 프레임별 flush 가능
            try (OutputStream out = exchange.getResponseBody()) {
                for (Frame frame : script.frames()) {
                    sleep(frame.delayMillis());
                    out.write(("event: " + frame.event() + "\ndata: " + frame.dataJson() + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                sleep(script.hangMillis());
            } catch (IOException ignored) {
                // 클라이언트(BE)가 먼저 끊은 경우 — 취소·timeout 시나리오에서 정상
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public void enqueue(Script script) {
        scripts.add(script);
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String lastRequestBody() {
        return lastRequestBody.get();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 2: 실패하는 어댑터 테스트 작성**

```java
// test/java/com/ymc/chat/infra/AiAgentWebClientAdapterTest.java
package com.ymc.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.infra.ai.AiAgentWebClientAdapter;
import com.ymc.chat.infra.ai.ChatStreamProperties;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;
import com.ymc.support.FakeAiSseServer;
import com.ymc.support.FakeAiSseServer.Script;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** 순수 단위 테스트 — 스프링 컨텍스트·Docker 불필요. fake AI SSE 서버를 직접 상대한다. */
class AiAgentWebClientAdapterTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static FakeAiSseServer aiServer;
    static Scheduler scheduler;

    final List<String> events = new CopyOnWriteArrayList<>();

    final AiStreamListener recorder = new AiStreamListener() {
        public void onRunStarted() { events.add("started"); }
        public void onDelta(String delta) { events.add("delta:" + delta); }
        public void onMessageCompleted(String message) { events.add("completed:" + message); }
        public void onRunCompleted() { events.add("run-completed"); }
        public void onRunFailed(String error) { events.add("run-failed:" + error); }
        public void onTransportError(Exception cause) {
            events.add("transport-error:" + cause.getClass().getSimpleName());
        }
    };

    @BeforeAll
    static void startServer() {
        aiServer = new FakeAiSseServer();
        aiServer.start();
        scheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor());
    }

    @AfterAll
    static void stopServer() {
        aiServer.close();
        scheduler.dispose();
    }

    private AiAgentWebClientAdapter adapter(Duration idleTimeout) {
        return new AiAgentWebClientAdapter(
                WebClient.builder().baseUrl(aiServer.baseUrl()).build(),
                scheduler,
                new ChatStreamProperties(idleTimeout, Duration.ofSeconds(30),
                        Duration.ofSeconds(15), 65536, Duration.ofSeconds(31)),
                new ObjectMapper());
    }

    @Test
    @DisplayName("성공 시퀀스를 순서대로 콜백하고, 요청 바디는 snake_case(thread_id)다")
    void successSequenceAndSnakeCaseBody() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-1"),
                FakeAiSseServer.delta("t-1", "안녕"),
                FakeAiSseServer.delta("t-1", "하세요"),
                FakeAiSseServer.messageCompleted("t-1", "안녕하세요"),
                FakeAiSseServer.runCompleted("t-1")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-1", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-completed"));
        assertThat(events).containsExactly(
                "started", "delta:안녕", "delta:하세요", "completed:안녕하세요", "run-completed");
        assertThat(aiServer.lastRequestBody()).contains("\"thread_id\":\"t-1\"");
        assertThat(aiServer.lastRequestBody()).contains("\"message\":\"질문\"");
        assertThat(aiServer.lastRequestBody()).doesNotContain("threadId");
    }

    @Test
    @DisplayName("run.failed를 error 문자열과 함께 전달한다")
    void runFailed() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-2"),
                FakeAiSseServer.runFailed("t-2", "boom")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-2", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.contains("run-failed:boom"));
        assertThat(events).doesNotContain("run-completed");
    }

    @Test
    @DisplayName("이벤트 사이 침묵이 idle timeout을 넘으면 TimeoutException으로 전달된다")
    void idleSilenceTimesOut() {
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t-3")).thenHangMillis(10_000));

        adapter(Duration.ofMillis(300)).stream(new AiRunRequest("t-3", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
        assertThat(events).contains("transport-error:" + TimeoutException.class.getSimpleName());
    }

    @Test
    @DisplayName("terminal 없이 EOF면 onTransportError다")
    void eofWithoutTerminal() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t-4"),
                FakeAiSseServer.delta("t-4", "일부")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-4", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
        assertThat(events).doesNotContain("run-completed");
    }

    @Test
    @DisplayName("data JSON이 깨졌으면 onTransportError다")
    void malformedDataIsTransportError() {
        aiServer.enqueue(Script.of(
                FakeAiSseServer.Frame.of("message.delta", "{not-json"),
                FakeAiSseServer.runCompleted("t-5")));

        adapter(Duration.ofSeconds(5)).stream(new AiRunRequest("t-5", "질문"), recorder);

        await().atMost(WAIT).until(() -> events.stream().anyMatch(e -> e.startsWith("transport-error:")));
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.infra.AiAgentWebClientAdapterTest"
```

Expected: 컴파일 실패 — `AiAgentWebClientAdapter` 없음

- [ ] **Step 4: 어댑터 구현**

```java
// chat/infra/ai/AiAgentWebClientAdapter.java
package com.ymc.chat.infra.ai;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.service.port.AiAgentStreamPort;
import com.ymc.chat.service.port.AiRunHandle;
import com.ymc.chat.service.port.AiRunRequest;
import com.ymc.chat.service.port.AiStreamListener;

import lombok.RequiredArgsConstructor;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

/**
 * BE↔AI 계약(simple-agent-run-stream.yml)의 WebClient 구현 (설계 §4).
 *
 * <p>reactive 체인은 이 클래스 밖으로 나가지 않는다 — 리스너 콜백은 전부
 * {@code chatRelayScheduler}(virtual thread)에서 순서대로 호출되므로 relay는 블로킹해도 된다.
 * 침묵 감지는 {@code Flux.timeout}(구독 시점부터 첫 이벤트에도 적용), 총 시한은 relay 워치독 담당.
 */
@Component
@ConditionalOnProperty(name = "ai.fake-stream", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
public class AiAgentWebClientAdapter implements AiAgentStreamPort {

    private static final Logger log = LoggerFactory.getLogger(AiAgentWebClientAdapter.class);

    static final String STREAM_PATH = "/api/v1/agents/simple-agent/runs/stream";

    private final WebClient aiWebClient;
    private final Scheduler chatRelayScheduler;
    private final ChatStreamProperties chatStreamProperties;
    private final ObjectMapper objectMapper;

    /** wire 형식은 snake_case (계약) — 코드 컨벤션과 경계에서 변환한다. */
    record StreamRequestBody(@JsonProperty("thread_id") String threadId, String message) {
    }

    @Override
    public AiRunHandle stream(AiRunRequest request, AiStreamListener listener) {
        AtomicBoolean terminalSeen = new AtomicBoolean(false);
        Disposable subscription = aiWebClient.post()
                .uri(STREAM_PATH)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(new StreamRequestBody(request.threadId(), request.message()))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .timeout(chatStreamProperties.idleTimeout())
                .publishOn(chatRelayScheduler)
                .subscribe(
                        event -> dispatch(event, listener, terminalSeen),
                        listener::onTransportError,
                        () -> {
                            if (!terminalSeen.get()) {
                                listener.onTransportError(new IllegalStateException(
                                        "terminal event 없이 upstream 스트림이 종료되었습니다."));
                            }
                        });
        return subscription::dispose; // dispose → 연결 종료 → AI가 생성 취소 (ADR-004)
    }

    private void dispatch(
            ServerSentEvent<String> event, AiStreamListener listener, AtomicBoolean terminalSeen) {
        String name = event.event() == null ? "" : event.event();
        try {
            switch (name) {
                case "run.started" -> listener.onRunStarted();
                case "message.delta" -> listener.onDelta(textField(event.data(), "delta"));
                case "message.completed" -> listener.onMessageCompleted(textField(event.data(), "message"));
                case "run.completed" -> {
                    terminalSeen.set(true);
                    listener.onRunCompleted();
                }
                case "run.failed" -> {
                    terminalSeen.set(true);
                    listener.onRunFailed(textField(event.data(), "error"));
                }
                default -> log.debug("알 수 없는 AI event 무시: {}", name);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            listener.onTransportError(e);
        }
    }

    /** data JSON에서 필수 문자열 필드를 꺼낸다. 없으면 계약 위반 — transport error로 처리된다. */
    private String textField(String data, String fieldName) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(data);
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    "AI event data에 '" + fieldName + "' 문자열 필드가 없습니다.");
        }
        return value.asText();
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.infra.AiAgentWebClientAdapterTest"
```

Expected: 5 tests PASS

- [ ] **Step 6: 전체 스위트 1회 (조건부 빈 전환 회귀 확인) 후 커밋**

```bash
./gradlew test
git add be/src/main/java/com/ymc/chat/infra/ai/AiAgentWebClientAdapter.java be/src/test/java/com/ymc/support/FakeAiSseServer.java be/src/test/java/com/ymc/chat/infra/AiAgentWebClientAdapterTest.java
git commit -m "[YMC-257] feat(chat): AI SSE WebClient 어댑터"
```

---

### Task 4: relay 오류 매핑·누적 상한 + wire 통합 테스트 1차

**Files:**
- Modify: `app/be/src/main/java/com/ymc/chat/service/ChatStreamService.java`
- Test: `app/be/src/test/java/com/ymc/chat/api/AiRelayIntegrationTest.java` (Create)

**Interfaces:**
- Consumes: Task 3 어댑터(빈), `ChatStreamProperties`, 동결된 `ChatMessageTransitions`
- Produces: `ChatStreamService.begin(SseEmitter, ChatStartResult, String)` 시그니처는 불변. Run 내부에 `handle` 보관 — Task 5의 워치독이 사용

- [ ] **Step 1: ChatStreamService 수정 — 주입·필드**

클래스 주입에 `ChatStreamProperties chatStreamProperties` 추가 (`@RequiredArgsConstructor` 유지, `final` 필드 추가). `begin`은 handle을 Run에 넘긴다:

```java
    public void begin(SseEmitter emitter, ChatStartResult started, String userContent) {
        Run run = new Run(emitter, started);
        run.sendStarted();
        AiRunHandle handle = aiAgentStreamPort.stream(
                new AiRunRequest(started.sessionId().toString(), userContent), run);
        run.attach(handle);
    }
```

Run 필드 추가 (기존 필드 유지):

```java
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final Object sendLock = new Object();
        private volatile AiRunHandle handle;

        void attach(AiRunHandle handle) {
            this.handle = handle;
        }

        private void cancelUpstream() {
            AiRunHandle current = handle;
            if (current != null) {
                current.cancel();
            }
        }
```

(import 추가: `com.ymc.chat.service.port.AiRunHandle`, `java.util.concurrent.TimeoutException`)

- [ ] **Step 2: 종결 멱등 가드 + 누적 상한**

`onDelta`를 다음으로 교체:

```java
        @Override
        public void onDelta(String delta) {
            if (finished.get()) {
                return; // 이미 종결 — 늦게 도착한 delta는 버린다
            }
            if (accumulated.length() + delta.length() > chatStreamProperties.maxContentLength()) {
                // idle timeout은 "안 올 때"만 잡는다 — "너무 많이 올 때"는 이 상한이 잡는다 (설계 §4)
                log.warn("delta 누적 상한 초과. messageId={} 누적={}자",
                        ids.assistantMessageId(), accumulated.length());
                cancelUpstream();
                failWith("AI_RESPONSE_TOO_LARGE", "답변이 허용 길이를 초과했습니다.", false);
                return;
            }
            accumulated.append(delta);
            send("message.delta", ChatSseEventData.Delta.of(
                    ids.paperId(), ids.sessionId(), ids.assistantMessageId(), delta));
        }
```

`onRunCompleted`·`failWith` 진입에 멱등 가드 추가 — 기존 본문 앞에:

```java
            if (!finished.compareAndSet(false, true)) {
                return; // 워치독·상한·transport 중 하나가 이미 종결함
            }
```

(`failWith`는 이 가드를 자체 포함하므로, `onRunCompleted`의 내부 실패 경로들이 `failWith`를 부르던 자리는 **가드를 통과한 뒤**다 — `onRunCompleted`에서 `failWith`를 호출하는 대신 가드 없는 내부 메서드로 분리한다:)

```java
        /** 종결 결정권을 이미 가진 쪽(onRunCompleted)이 부르는 실패 경로. */
        private void failLocked(String code, String message, boolean retryable) {
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

        private void failWith(String code, String message, boolean retryable) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            failLocked(code, message, retryable);
        }
```

`onRunCompleted` 내부의 `failWith(...)` 호출 3곳(AI_PROTOCOL_ERROR·MESSAGE_PERSISTENCE_FAILED)은 전부 `failLocked(...)`로 바꾼다 (이미 가드를 통과했으므로).

- [ ] **Step 3: onTransportError 코드 매핑**

기존 `onTransportError`를 교체 (계약 protocolFailures 매핑):

```java
        @Override
        public void onTransportError(Exception cause) {
            if (cause instanceof TimeoutException) {
                // 어댑터의 idle timeout — 이벤트 사이 침묵이 상한을 넘었다
                log.warn("AI 스트림 침묵 초과. messageId={}", ids.assistantMessageId());
                cancelUpstream();
                failWith("AI_TIMEOUT", "답변 생성 시간이 초과되었습니다.", true);
                return;
            }
            if (finalContent != null) {
                // message.completed 후 run.completed 없이 종료 — 계약의 AI_PROTOCOL_ERROR
                log.warn("최종 답변 후 종료 신호 없이 스트림 종료. messageId={} causeType={}",
                        ids.assistantMessageId(), cause.getClass().getSimpleName());
                failWith("AI_PROTOCOL_ERROR", "답변 생성 결과가 올바르지 않습니다.", false);
                return;
            }
            log.warn("AI 스트림 단절. messageId={}", ids.assistantMessageId(), cause);
            failWith("AI_STREAM_DISCONNECTED", "답변 생성 연결이 끊어졌습니다.", true);
        }
```

- [ ] **Step 4: send에 락과 outbound 시각 기록**

```java
        private volatile long lastOutboundNanos = System.nanoTime();

        private void send(String eventName, Object payload) {
            if (!feConnected.get()) {
                return;
            }
            synchronized (sendLock) { // 타이머 스레드(heartbeat, Task 5)와 relay 스레드가 겹칠 수 있다
                try {
                    emitter.send(SseEmitter.event().name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON));
                    lastOutboundNanos = System.nanoTime();
                } catch (IOException | IllegalStateException e) {
                    feConnected.set(false);
                    log.debug("FE 전송 중단 — 연결 종료로 판단. messageId={}", ids.assistantMessageId());
                }
            }
        }
```

- [ ] **Step 5: 실패하는 통합 테스트 작성 (1차 — 타이머 제외)**

```java
// test/java/com/ymc/chat/api/AiRelayIntegrationTest.java
package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.FakeAiSseServer;
import com.ymc.support.FakeAiSseServer.Script;
import com.ymc.support.IntegrationTest;

/**
 * 실제 WebClient 어댑터 + fake AI SSE 서버로 wire 레벨을 검증한다.
 * ai.fake-stream을 뒤집으므로 베이스와 다른 컨텍스트(컨테이너 한 벌 추가)가 뜬다 — 의도된 비용.
 */
@TestPropertySource(properties = {
        "chat.stream.idle-timeout=1s",
        "chat.stream.deadline=4s",
        "chat.stream.heartbeat-interval=300ms",
        "chat.stream.max-content-length=64",
        "chat.stream.emitter-timeout=6s",
})
class AiRelayIntegrationTest extends IntegrationTest {

    static final FakeAiSseServer aiServer = new FakeAiSseServer();

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        aiServer.start();
        registry.add("ai.base-url", aiServer::baseUrl);
        registry.add("ai.fake-stream", () -> "false"); // 베이스의 fake 활성화를 뒤집는다 (최우선순위)
    }

    @AfterAll
    static void stopAiServer() {
        aiServer.close();
    }

    @Autowired
    com.ymc.chat.service.ChatCommandService chatCommandService;

    @Autowired
    com.ymc.chat.service.ChatStreamService chatStreamService;

    private Paper givenCompletedPaper() {
        Paper paper = givenProcessingPaper("relay-" + UUID.randomUUID() + ".pdf");
        paperTransitions.markParsed(paper.getId(), PaperStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    private MvcResult startStream(Paper paper) throws Exception {
        return mockMvc.perform(post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clientMessageId", UUID.randomUUID().toString(),
                                "content", "질문"))))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private ChatMessage awaitAssistantTerminal() {
        await().atMost(Duration.ofSeconds(10)).until(() -> chatMessageRepository.findAll().stream()
                .anyMatch(m -> m.getRole() == ChatMessageRole.ASSISTANT
                        && m.getStatus() != ChatMessageStatus.GENERATING));
        return chatMessageRepository.findAll().stream()
                .filter(m -> m.getRole() == ChatMessageRole.ASSISTANT).findFirst().orElseThrow();
    }

    private String streamBody(MvcResult result) throws Exception {
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("성공 스트림 — 실제 wire로 delta가 중계되고 COMPLETED가 저장된다")
    void successOverWire() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "진짜 "),
                FakeAiSseServer.delta("t", "응답"),
                FakeAiSseServer.messageCompleted("t", "진짜 응답"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(assistant.getContent()).isEqualTo("진짜 응답");
        String stream = streamBody(result);
        assertThat(stream).contains("event:message.delta");
        assertThat(stream).contains("event:message.completed");
    }

    @Test
    @DisplayName("run.failed — FAILED 저장, raw error 미노출, AI_RUN_FAILED")
    void runFailedOverWire() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runFailed("t", "upstream raw detail")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        String stream = streamBody(result);
        assertThat(stream).contains("\"code\":\"AI_RUN_FAILED\"");
        assertThat(stream).doesNotContain("upstream raw detail");
    }

    @Test
    @DisplayName("message.completed 없이 run.completed — AI_PROTOCOL_ERROR")
    void runCompletedWithoutMessage() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        awaitAssistantTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"AI_PROTOCOL_ERROR\"");
    }

    @Test
    @DisplayName("message.completed 후 run.completed 없이 EOF — AI_PROTOCOL_ERROR")
    void eofAfterCompleted() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.messageCompleted("t", "완성")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(assistant.getContent()).isNull(); // partial 미저장
        assertThat(streamBody(result)).contains("\"code\":\"AI_PROTOCOL_ERROR\"");
    }

    @Test
    @DisplayName("terminal 없이 EOF — AI_STREAM_DISCONNECTED")
    void eofWithoutTerminal() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "일부")));

        MvcResult result = startStream(paper);
        awaitAssistantTerminal();
        assertThat(streamBody(result)).contains("\"code\":\"AI_STREAM_DISCONNECTED\"");
    }

    @Test
    @DisplayName("이벤트 사이 침묵이 idle timeout 초과 — AI_TIMEOUT")
    void idleSilence() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(FakeAiSseServer.runStarted("t")).thenHangMillis(30_000));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(streamBody(result)).contains("\"code\":\"AI_TIMEOUT\"");
    }

    @Test
    @DisplayName("누적 상한(64자) 초과 — AI_RESPONSE_TOO_LARGE, partial 미저장")
    void oversizedAccumulation() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "a".repeat(100))));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(assistant.getContent()).isNull();
        String stream = streamBody(result);
        assertThat(stream).contains("\"code\":\"AI_RESPONSE_TOO_LARGE\"");
        assertThat(stream).contains("\"retryable\":false");
    }

    @Test
    @DisplayName("누적 delta와 completed가 다르면 completed를 저장한다")
    void completedWinsOverAccumulated() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                FakeAiSseServer.delta("t", "가"),
                FakeAiSseServer.messageCompleted("t", "나"),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getContent()).isEqualTo("나");
        streamBody(result);
    }
}
```

- [ ] **Step 6: RED → 구현 → GREEN**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.api.AiRelayIntegrationTest"
```

Step 1~4를 적용하기 전에 실행하면 AI_RESPONSE_TOO_LARGE·AI_TIMEOUT·AI_PROTOCOL_ERROR(EOF after completed) 케이스가 실패해야 한다. 적용 후: 8 tests PASS.

- [ ] **Step 7: 전체 스위트 후 커밋**

```bash
./gradlew test
git add be/src/main/java/com/ymc/chat/service/ChatStreamService.java be/src/test/java/com/ymc/chat/api/AiRelayIntegrationTest.java
git commit -m "[YMC-257] feat(chat): relay 오류 코드 매핑과 누적 상한"
```

---

### Task 5: deadline 워치독 + heartbeat + emitter timeout 프로퍼티화

**Files:**
- Modify: `app/be/src/main/java/com/ymc/chat/service/ChatStreamService.java`
- Modify: `app/be/src/main/java/com/ymc/chat/api/dto/ChatSseEventData.java`
- Modify: `app/be/src/main/java/com/ymc/chat/api/ChatController.java`
- Test: `app/be/src/test/java/com/ymc/chat/api/AiRelayIntegrationTest.java` (Modify — 3개 추가)

**Interfaces:**
- Consumes: Task 4의 `finished`/`sendLock`/`lastOutboundNanos`/`cancelUpstream`, Task 2의 `chatTimerExecutor`
- Produces: `ChatSseEventData.Heartbeat(String type, UUID paperId, UUID sessionId, UUID messageId, Instant emittedAt)` — 계약 `ChatHeartbeatEventData`와 1:1

- [ ] **Step 1: Heartbeat payload record 추가**

`ChatSseEventData`에 (import `java.time.Instant` 추가):

```java
    /** event: heartbeat — 침묵 구간 연결 유지용. 상태를 바꾸지 않는다 (계약). */
    public record Heartbeat(String type, UUID paperId, UUID sessionId, UUID messageId, Instant emittedAt) {

        public static Heartbeat of(UUID paperId, UUID sessionId, UUID messageId) {
            return new Heartbeat("heartbeat", paperId, sessionId, messageId, Instant.now());
        }
    }
```

- [ ] **Step 2: ChatStreamService에 타이머 장착**

주입에 `ScheduledExecutorService chatTimerExecutor` 추가 (import `java.util.concurrent.ScheduledExecutorService`, `java.util.concurrent.ScheduledFuture`, `java.util.concurrent.TimeUnit`). `begin`의 `run.attach(handle)`을 `run.arm(handle)`로 교체하고, **Task 4의 `attach` 메서드는 삭제**한 뒤 (arm이 대체) Run에:

```java
        private volatile ScheduledFuture<?> deadlineTask;
        private volatile ScheduledFuture<?> heartbeatTask;

        /** upstream 손잡이를 받고 타이머를 건다. 콜백이 이미 종결시켰다면 걸지 않는다. */
        void arm(AiRunHandle handle) {
            this.handle = handle;
            if (finished.get()) {
                return;
            }
            deadlineTask = chatTimerExecutor.schedule(
                    this::onDeadlineExceeded,
                    chatStreamProperties.deadline().toMillis(), TimeUnit.MILLISECONDS);
            long interval = chatStreamProperties.heartbeatInterval().toMillis();
            heartbeatTask = chatTimerExecutor.scheduleAtFixedRate(
                    this::maybeSendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
            if (finished.get()) {
                cancelTimers(); // arm 도중 종결된 경합 — 방금 건 타이머를 되돌린다
            }
        }

        /** 총 시한 안전망 (설계 §4). 정상 완료가 먼저면 finished 가드가 무시한다. */
        private void onDeadlineExceeded() {
            log.warn("application deadline 초과. messageId={}", ids.assistantMessageId());
            cancelUpstream();
            failWith("AI_TIMEOUT", "답변 생성 시간이 초과되었습니다.", true);
        }

        /** 마지막 outbound 이후 침묵이 interval을 넘으면 heartbeat를 보낸다 (계약 15s 규칙). */
        private void maybeSendHeartbeat() {
            if (finished.get() || !feConnected.get()) {
                return;
            }
            long silenceNanos = System.nanoTime() - lastOutboundNanos;
            if (silenceNanos >= chatStreamProperties.heartbeatInterval().toNanos()) {
                send("heartbeat", ChatSseEventData.Heartbeat.of(
                        ids.paperId(), ids.sessionId(), ids.assistantMessageId()));
            }
        }

        private void cancelTimers() {
            ScheduledFuture<?> d = deadlineTask;
            if (d != null) {
                d.cancel(false);
            }
            ScheduledFuture<?> h = heartbeatTask;
            if (h != null) {
                h.cancel(false);
            }
        }
```

`failLocked`의 시작과 `onRunCompleted` 가드 통과 직후에 `cancelTimers();` 한 줄씩 추가 (종결되면 타이머 정리).

- [ ] **Step 3: 컨트롤러 emitter timeout 프로퍼티화**

`ChatController`에서 `EMITTER_TIMEOUT_MS` 상수를 제거하고 `ChatStreamProperties`를 주입 (`private final ChatStreamProperties chatStreamProperties;` + import `com.ymc.chat.infra.ai.ChatStreamProperties`):

```java
        SseEmitter emitter = new SseEmitter(chatStreamProperties.emitterTimeout().toMillis());
```

(상수에 달려 있던 "deadline보다 커야" 주석은 `ChatStreamProperties`의 compact constructor 검증이 대신하므로 삭제.)

- [ ] **Step 4: 테스트 3개 추가 (AiRelayIntegrationTest)**

```java
    @Test
    @DisplayName("delta가 계속 흘러도 총 시한(deadline)을 넘기면 AI_TIMEOUT이다")
    void deadlineExceededWhileStreaming() throws Exception {
        Paper paper = givenCompletedPaper();
        // idle(1s)에는 안 걸리는 400ms 간격 delta 12개 = 4.8s > deadline(4s)
        FakeAiSseServer.Frame[] frames = new FakeAiSseServer.Frame[13];
        frames[0] = FakeAiSseServer.runStarted("t");
        for (int i = 1; i <= 12; i++) {
            frames[i] = new FakeAiSseServer.Frame("message.delta",
                    "{\"type\":\"message.delta\",\"thread_id\":\"t\",\"delta\":\"x\"}", 400);
        }
        aiServer.enqueue(Script.of(frames));

        MvcResult result = startStream(paper);
        ChatMessage assistant = awaitAssistantTerminal();

        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.FAILED);
        assertThat(streamBody(result)).contains("\"code\":\"AI_TIMEOUT\"");
    }

    @Test
    @DisplayName("outbound 침묵이 heartbeat-interval을 넘으면 heartbeat event가 나간다")
    void heartbeatDuringSilence() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                // 800ms 침묵(heartbeat 300ms의 2배 이상, idle 1s 미만) 뒤 완료
                new FakeAiSseServer.Frame("message.completed",
                        "{\"type\":\"message.completed\",\"thread_id\":\"t\",\"message\":\"끝\"}", 800),
                FakeAiSseServer.runCompleted("t")));

        MvcResult result = startStream(paper);
        awaitAssistantTerminal();
        String stream = streamBody(result);

        assertThat(stream).contains("event:heartbeat");
        assertThat(stream).contains("\"emittedAt\"");
    }

    @Test
    @DisplayName("FE가 끊겨도 upstream 소비와 최종 저장은 계속된다")
    void feDisconnectStillPersists() throws Exception {
        Paper paper = givenCompletedPaper();
        aiServer.enqueue(Script.of(
                FakeAiSseServer.runStarted("t"),
                new FakeAiSseServer.Frame("message.delta",
                        "{\"type\":\"message.delta\",\"thread_id\":\"t\",\"delta\":\"느린\"}", 300),
                FakeAiSseServer.messageCompleted("t", "느린 완성"),
                FakeAiSseServer.runCompleted("t")));

        // 컨트롤러 우회 — emitter를 직접 만들어 relay에 넘기고 즉시 FE 종료를 시뮬레이션한다
        var started = chatCommandService.start(
                TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "질문");
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(6_000L);
        chatStreamService.begin(emitter, started, "질문");
        emitter.complete(); // onCompletion → feConnected=false

        ChatMessage assistant = awaitAssistantTerminal();
        assertThat(assistant.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(assistant.getContent()).isEqualTo("느린 완성");
    }
```

- [ ] **Step 5: RED → 구현 → GREEN → 전체 스위트**

```bash
cd be && ./gradlew test --tests "com.ymc.chat.api.AiRelayIntegrationTest"   # 11 tests PASS
./gradlew test
```

- [ ] **Step 6: 커밋**

```bash
git add be/src/main/java/com/ymc/chat/service/ChatStreamService.java be/src/main/java/com/ymc/chat/api/dto/ChatSseEventData.java be/src/main/java/com/ymc/chat/api/ChatController.java be/src/test/java/com/ymc/chat/api/AiRelayIntegrationTest.java
git commit -m "[YMC-257] feat(chat): deadline 워치독·heartbeat·emitter timeout 설정화"
```

---

### Task 6: 전체 검증 + 수동 E2E + PR

- [ ] **Step 1: 전체 테스트 (강제 재실행)**

```bash
cd be && ./gradlew cleanTest test
```

Expected: BUILD SUCCESSFUL, 실패 0.

- [ ] **Step 2: 수동 E2E — 진짜 AI 서버 (DoD, 로컬 환경 필요)**

AI 서버가 로컬에서 기동 가능해야 한다 (LLM API key는 ai 레포의 환경 설정 — 사용자 보유). 안 되면 이 스텝은 "미실행"으로 보고하고 PR Notes에 남긴다.

```bash
# 1) AI 서버 (별도 터미널, ai 레포)
cd ../ai && uv run uvicorn main:app --port 8000
# 2) BE (별도 터미널)
cd ../app/be && ./gradlew bootRun
# 3) 로그인 후 access token 확보, COMPLETED 논문 확보, 스트림 확인
curl -N -X POST "http://localhost:8080/api/papers/{paperId}/chat/messages" \
  -H "Authorization: Bearer {accessToken}" -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"clientMessageId":"'"$(uuidgen | tr A-Z a-z)"'","content":"이 논문 핵심이 뭐야?"}'
```

확인 항목: 실제 LLM delta 스트림 → `message.completed` → DB COMPLETED. (deadline 강제 발동·AI 취소 로그 확인은 `chat.stream.deadline`을 짧게 낮춘 임시 기동으로 시도 — 선택.)

- [ ] **Step 3: 푸시 + PR**

```bash
git push -u origin YMC-257-ai-sse-relay
gh pr create --title "[YMC-257] feat(chat): AI SSE WebClient relay — 타이머·상한·오류 매핑" --body "..."
```

PR 본문은 팀 템플릿. Review Focus에: finished 가드의 경합(워치독 vs 정상 완료), responseTimeout 미설정 판단, 조건부 빈 전환. **Generated with 푸터·Co-Authored-By 금지.**

- [ ] **Step 4: Jira YMC-257 진행 중 전환**

---

## 범위 밖

- upstream heartbeat 계약 확장 — 설계 §7 follow-up (긴 침묵이 정상인 에이전트 도입 시)
- 오래된 GENERATING 정리 배치 — 설계 §7 follow-up
- FE(YMC-258), 히스토리 조회(YMC-260), 사용량 제한
