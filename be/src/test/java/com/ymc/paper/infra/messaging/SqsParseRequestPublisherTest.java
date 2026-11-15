package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ymc.common.config.AwsProperties;
import com.ymc.paper.service.port.ParseRequestPublisher.AcceptanceUnknownException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

class SqsParseRequestPublisherTest {

    @Test
    void sdkDoesNotResendParseRequestAfterServiceError() throws Exception {
        var sends = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                switch (exchange.getRequestHeaders().getFirst("X-Amz-Target")) {
                    case "AmazonSQS.GetQueueUrl" ->
                            respond(exchange, 200, "{\"QueueUrl\":\"" + endpoint + "/parse-requests\"}");
                    case "AmazonSQS.SendMessage" -> {
                        sends.incrementAndGet();
                        respond(exchange, 503, "{\"__type\":\"ServiceUnavailable\",\"message\":\"injected error\"}");
                    }
                    default -> respond(exchange, 404, "{}");
                }
            }
        });
        server.start();
        try (SqsClient client = SqsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .overrideConfiguration(o -> o.retryStrategy(r -> r.maxAttempts(3)))
                .build()) {
            var props = new AwsProperties(null, null, null, null,
                    new AwsProperties.Sqs("parse-requests", null, null, null));
            var publisher = new SqsParseRequestPublisher(client, new ObjectMapper(), props);
            assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), "original.pdf"))
                    .isInstanceOf(AcceptanceUnknownException.class);
            assertThat(sends.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/x-amz-json-1.0");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
