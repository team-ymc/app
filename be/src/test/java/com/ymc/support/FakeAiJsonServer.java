package com.ymc.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/** non-streaming AI 응답을 스크립트로 돌려주는 가짜 서버. 요청 본문·경로·호출 수를 기록한다. */
public final class FakeAiJsonServer implements AutoCloseable {

    public record Reply(int status, String json, long delayMillis) {
        public static Reply ok(String json) {
            return new Reply(200, json, 0);
        }

        public static Reply error(int status, String json) {
            return new Reply(status, json, 0);
        }

        public Reply delayed(long millis) {
            return new Reply(status, json, millis);
        }
    }

    private HttpServer server;
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("fake AI 서버 기동 실패", e);
        }
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastRequestPath.set(exchange.getRequestURI().getPath());
            Reply reply = replies.poll();
            if (reply == null) {
                reply = Reply.error(500, "{\"detail\":{\"code\":\"NO_SCRIPT\"}}");
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.json().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public void enqueue(Reply reply) {
        replies.add(reply);
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String lastRequestBody() {
        return lastRequestBody.get();
    }

    public String lastRequestPath() {
        return lastRequestPath.get();
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        replies.clear();
        calls.set(0);
        lastRequestBody.set(null);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 고정 형식 definition 응답 JSON. */
    public static String definitionJson(String term, String en, String ko, String cost) {
        String md = "### " + term + "\\n\\n**Definition (정의)**\\n\\n" + en + "  \\n" + ko;
        return "{\"thread_id\":\"t\",\"paper_id\":\"p\",\"definition\":\"" + md
                + "\",\"estimated_cost_usd\":" + (cost == null ? "null" : "\"" + cost + "\"") + "}";
    }
}
