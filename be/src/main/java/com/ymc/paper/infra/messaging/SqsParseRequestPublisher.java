package com.ymc.paper.infra.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.common.config.AwsProperties;
import com.ymc.paper.infra.messaging.message.ParseRequestMessage;
import com.ymc.paper.service.port.ParseRequestPublisher;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * {@link ParseRequestPublisher}의 SQS 구현 — `parse-requests` 큐에 발행한다.
 * 수신은 spring-cloud-aws의 {@code @SqsListener}가 맡고, 발행은 SDK v2 동기 클라이언트로 충분하다 (design D1).
 */
@Component
@RequiredArgsConstructor
public class SqsParseRequestPublisher implements ParseRequestPublisher {

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final AwsProperties props;

    /**
     * 큐 URL은 바뀌지 않으니 처음 발행할 때 한 번만 조회해 재사용한다.
     * final이 아니므로 {@code @RequiredArgsConstructor}가 만드는 생성자에는 들어가지 않는다 — 주입 대상이 아니다.
     * (queryUrl은 주입받는 게 아니라, 런타임에 캐싱함)
     */
    // volatile : 한 스레드가 캐시한 값을 다른 스레드가 즉시 확인 가능.
    private volatile String queueUrl;

    @Override
    public void publish(UUID paperId, String fileKey) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl())
                .messageBody(serialize(new ParseRequestMessage(paperId, fileKey)))
                .build());
    }

    private String queueUrl() {
        String cached = queueUrl;
        if (cached == null) {
            cached = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(props.sqs().parseRequestQueue())
                    .build())
                    .queueUrl();
            queueUrl = cached;
        }
        return cached;
    }

    private String serialize(ParseRequestMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            // 필드 두 개짜리 record라 실제로는 발생하지 않는다. 발행 실패로 다뤄 5xx가 되게 한다.
            throw new IllegalStateException("parse-request 직렬화에 실패했습니다: paperId=" + message.paperId(), e);
        }
    }
}
