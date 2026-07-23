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
