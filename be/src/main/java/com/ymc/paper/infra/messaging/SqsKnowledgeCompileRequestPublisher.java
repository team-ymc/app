package com.ymc.paper.infra.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.common.config.AwsProperties;
import com.ymc.paper.infra.messaging.message.KnowledgeCompileRequestMessage;
import com.ymc.paper.service.port.KnowledgeCompileRequestPublisher;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** `knowledge-compile-requests` 큐 발행. 구조는 {@link SqsParseRequestPublisher}와 같다. */
@Component
@RequiredArgsConstructor
public class SqsKnowledgeCompileRequestPublisher implements KnowledgeCompileRequestPublisher {

    private final SqsClient sqs;
    private final ObjectMapper objectMapper;
    private final AwsProperties props;

    private volatile String queueUrl;

    @Override
    public void publish(UUID paperId, String manifestKey) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl())
                .messageBody(serialize(new KnowledgeCompileRequestMessage(paperId, manifestKey)))
                .build());
    }

    private String queueUrl() {
        String cached = queueUrl;
        if (cached == null) {
            cached = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(props.sqs().knowledgeCompileRequestQueue())
                    .build())
                    .queueUrl();
            queueUrl = cached;
        }
        return cached;
    }

    private String serialize(KnowledgeCompileRequestMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "knowledge-compile-request 직렬화에 실패했습니다: paperId=" + message.paperId(), e);
        }
    }
}
