package com.ymc.paper.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.port.PrerequisiteDefinitionGenerator.GenerationFailedException;
import com.ymc.support.FakeAiJsonServer;
import com.ymc.support.FakeAiJsonServer.Reply;

@ExtendWith(OutputCaptureExtension.class)
class AiPrerequisiteDefinitionAdapterTest {

    static FakeAiJsonServer aiServer;

    @BeforeAll
    static void start() {
        aiServer = new FakeAiJsonServer();
        aiServer.start();
    }

    @AfterAll
    static void stop() {
        aiServer.close();
    }

    @BeforeEach
    void reset() {
        aiServer.reset();
    }

    private AiPrerequisiteDefinitionAdapter adapter(Duration timeout) {
        return new AiPrerequisiteDefinitionAdapter(
                WebClient.builder().baseUrl(aiServer.baseUrl()).build(),
                new PrerequisiteDefinitionProperties(timeout, Duration.ofDays(30), "v1"),
                new ObjectMapper());
    }

    @Test
    void 계약_형식의_요청을_보내고_영문_국문을_돌려준다() throws Exception {
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("softmax", "An en line", "국문 한 줄", "0.00001")));

        PrerequisiteDefinition def = adapter(Duration.ofSeconds(5)).generate("paper-1", "p0000-b0002", 18, 25);

        assertThat(def).isEqualTo(new PrerequisiteDefinition("An en line", "국문 한 줄"));
        assertThat(aiServer.lastRequestPath()).isEqualTo("/api/v1/agents/prerequisite-knowledge-agent/runs");
        var body = new ObjectMapper().readTree(aiServer.lastRequestBody());
        assertThat(body.get("paper_id").asText()).isEqualTo("paper-1");
        assertThat(body.get("thread_id").asText()).isNotBlank();
        assertThat(body.at("/selection/start/block_id").asText()).isEqualTo("p0000-b0002");
        assertThat(body.at("/selection/start/offset").asInt()).isEqualTo(18);
        assertThat(body.at("/selection/end/block_id").asText()).isEqualTo("p0000-b0002");
        assertThat(body.at("/selection/end/offset").asInt()).isEqualTo(25);
    }

    @Test
    void AI_4xx는_GenerationFailedException이다() {
        aiServer.enqueue(Reply.error(400, "{\"detail\":{\"code\":\"PREREQUISITE_SELECTION_NOT_FOUND\","
                + "\"message\":\"no\",\"estimated_cost_usd\":\"0.00000000\"}}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class)
                .hasMessageContaining("PREREQUISITE_SELECTION_NOT_FOUND");
    }

    @Test
    void AI_4xx는_estimated_cost_usd를_실패_로그에_남긴다(CapturedOutput output) {
        aiServer.enqueue(Reply.error(400, "{\"detail\":{\"code\":\"PREREQUISITE_SELECTION_NOT_FOUND\","
                + "\"message\":\"no\",\"estimated_cost_usd\":\"0.00042\"}}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class);

        assertThat(output).contains("estimatedCostUsd=0.00042");
    }

    @Test
    void 형식이_어긋난_definition은_GenerationFailedException이다() {
        aiServer.enqueue(Reply.ok("{\"thread_id\":\"t\",\"paper_id\":\"p\",\"definition\":\"free text\","
                + "\"estimated_cost_usd\":\"0.1\"}"));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class)
                .hasMessageContaining("형식");
    }

    @Test
    void 타임아웃은_GenerationFailedException이다() {
        aiServer.enqueue(Reply.ok(FakeAiJsonServer.definitionJson("t", "en", "ko", null)).delayed(1500));

        assertThatThrownBy(() -> adapter(Duration.ofMillis(300)).generate("paper-1", "b", 0, 1))
                .isInstanceOf(GenerationFailedException.class);
    }
}
