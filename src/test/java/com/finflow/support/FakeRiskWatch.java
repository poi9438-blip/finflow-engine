package com.finflow.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

// 테스트용 가짜 RiskWatch (POST /api/evaluations). 받은 요청을 모아 두고, 정해 둔 응답을 차례로 돌려줌
// 정해 둔 응답이 없으면 판정 응답(REVIEW)
public final class FakeRiskWatch {

    // 응답 하나: 상태 코드·본문, 보내기 전 지연, 응답 없이 연결 끊기
    public record Reply(int status, String body, long delayMs, boolean drop) {
        public static Reply error(int status) {
            return new Reply(status, "{\"status\":\"ERROR\",\"message\":\"x\",\"data\":null}", 0, false);
        }

        public static Reply ok(String body) {
            return new Reply(201, body, 0, false);
        }

        public static Reply slow(long delayMs) {
            return new Reply(201, evaluated(UUID.randomUUID().toString()), delayMs, false);
        }

        public static Reply disconnect() {
            return new Reply(0, null, 0, true);
        }
    }

    // 받은 요청 (헤더 이름은 소문자)
    public record Received(Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Runnable onRequest = () -> {
    };

    private FakeRiskWatch(HttpServer server) {
        this.server = server;
    }

    // 빈 포트에서 시작 (지연 응답이 다음 요청을 막지 않게 스레드 여러 개)
    public static FakeRiskWatch start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeRiskWatch fake = new FakeRiskWatch(server);
            server.createContext("/api/evaluations", fake::handle);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 판정 응답 본문 (RiskWatch 평가 API와 같은 모양)
    public static String evaluated(String evaluationId) {
        return "{\"status\":\"SUCCESS\",\"message\":\"OK\",\"data\":{\"evaluationId\":\"" + evaluationId
                + "\",\"sourceSystem\":\"FINFLOW\",\"verdict\":\"REVIEW\",\"riskLevel\":\"MEDIUM\",\"riskScore\":7,"
                + "\"criteriaVersion\":\"2026-10-26\",\"reasons\":[],\"notEvaluated\":[],"
                + "\"createdAt\":\"2026-10-27T01:02:03.1234567Z\"},\"traceId\":\"x\"}";
    }

    public synchronized void reset() {
        replies.clear();
        received.clear();
        onRequest = () -> {
        };
    }

    public synchronized void reply(Reply... next) {
        replies.addAll(List.of(next));
    }

    // 요청을 받은 직후 (응답 전) 실행할 일
    public void onRequest(Runnable action) {
        this.onRequest = action;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    private synchronized Reply next() {
        Reply r = replies.poll();
        return r != null ? r : Reply.ok(evaluated(UUID.randomUUID().toString()));
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(headers, body));
        onRequest.run();

        Reply reply = next();
        if (reply.drop()) {
            exchange.close();
            return;
        }
        if (reply.delayMs() > 0) {
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException e) {
            // 클라이언트가 시간 초과로 먼저 끊음
        }
    }
}
