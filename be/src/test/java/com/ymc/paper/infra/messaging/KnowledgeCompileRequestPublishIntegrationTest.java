package com.ymc.paper.infra.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.service.port.KnowledgeCompileRequestPublisher;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.sqs.model.Message;

/** messaging.yml 0.3.0 KnowledgeCompileRequest wire 형식. */
class KnowledgeCompileRequestPublishIntegrationTest extends IntegrationTest {

    @Autowired
    KnowledgeCompileRequestPublisher publisher;

    @Test
    void paper_id와_manifest_key만_snake_case로_발행한다() throws Exception {
        UUID paperId = UUID.randomUUID();

        publisher.publish(paperId, "papers/" + paperId + "/manifest.json");

        List<Message> messages = receive(compileRequestQueueUrl(), 5);
        assertThat(messages).hasSize(1);
        JsonNode body = objectMapper.readTree(messages.get(0).body());
        assertThat(body.get("paper_id").asText()).isEqualTo(paperId.toString());
        assertThat(body.get("manifest_key").asText()).isEqualTo("papers/" + paperId + "/manifest.json");
        assertThat(body.size()).isEqualTo(2);
    }
}
