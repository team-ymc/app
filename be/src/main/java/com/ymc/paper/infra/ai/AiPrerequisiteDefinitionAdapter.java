package com.ymc.paper.infra.ai;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.paper.service.PrerequisiteDefinition;
import com.ymc.paper.service.PrerequisiteDefinitionMarkdown;
import com.ymc.paper.service.port.PrerequisiteDefinitionGenerator;

import lombok.RequiredArgsConstructor;

/**
 * BE↔AI 계약 prerequisite-knowledge-agent-run(non-streaming)의 WebClient 구현. 블록 호출이라
 * 타임아웃이 곧 전체 상한이다. 비용은 사용량에 반영하지 않고 로그로만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class AiPrerequisiteDefinitionAdapter implements PrerequisiteDefinitionGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiPrerequisiteDefinitionAdapter.class);
    static final String RUN_PATH = "/api/v1/agents/prerequisite-knowledge-agent/runs";

    private final WebClient aiWebClient;
    private final PrerequisiteDefinitionProperties properties;
    private final ObjectMapper objectMapper;

    record AnchorBody(@JsonProperty("block_id") String blockId, int offset) {
    }

    record SelectionBody(AnchorBody start, AnchorBody end) {
    }

    record RunRequestBody(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("paper_id") String paperId,
            SelectionBody selection) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunResponseBody(String definition, @JsonProperty("estimated_cost_usd") BigDecimal estimatedCostUsd) {
    }

    @Override
    public PrerequisiteDefinition generate(String requestPaperId, String blockId, int startOffset, int endOffset) {
        RunRequestBody body = new RunRequestBody(UUID.randomUUID().toString(), requestPaperId,
                new SelectionBody(new AnchorBody(blockId, startOffset), new AnchorBody(blockId, endOffset)));
        RunResponseBody response;
        try {
            response = aiWebClient.post()
                    .uri(RUN_PATH)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(RunResponseBody.class)
                    .block(properties.timeout());
        } catch (WebClientResponseException e) {
            String code = errorCode(e.getResponseBodyAsString());
            throw new GenerationFailedException("AI 설명 생성 실패: status=" + e.getStatusCode().value()
                    + ", code=" + code, e);
        } catch (RuntimeException e) {
            // WebClient.block(Duration)은 타임아웃을 IllegalStateException(cause TimeoutException)으로 올린다.
            boolean timeout = e.getCause() instanceof TimeoutException;
            throw new GenerationFailedException(timeout ? "AI 설명 생성 타임아웃" : "AI 설명 생성 전송 실패", e);
        }
        if (response == null) {
            throw new GenerationFailedException("AI 설명 응답이 비어 있음", null);
        }
        log.info("선행지식 설명 생성: paperId={}, blockId={}, estimatedCostUsd={}",
                requestPaperId, blockId, response.estimatedCostUsd());
        return PrerequisiteDefinitionMarkdown.parse(response.definition())
                .orElseThrow(() -> new GenerationFailedException("AI 설명 형식 불일치", null));
    }

    private String errorCode(String responseBody) {
        try {
            JsonNode detail = objectMapper.readTree(responseBody).path("detail");
            return detail.path("code").asText("UNKNOWN");
        } catch (Exception e) {
            return "UNPARSEABLE";
        }
    }
}
