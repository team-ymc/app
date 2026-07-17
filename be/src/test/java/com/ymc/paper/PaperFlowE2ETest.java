package com.ymc.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.sqs.model.Message;

/**
 * FT-003 Done Criteria의 BE 구간 (tasks 7.1).
 *
 * <p>create → (presigned URL로 S3 직접 PUT) → complete → parse-requests 발행 확인 →
 * (AI 워커인 척 parse-result 발행) → status가 terminal로 바뀌는 것까지 한 번에 흐른다.
 * 파일 바이트는 BE를 거치지 않는다 — PUT은 LocalStack S3로 직접 나간다 (ADR-001).
 */
class PaperFlowE2ETest extends IntegrationTest {

    @Test
    @DisplayName("성공 흐름: 등록 → S3 업로드 → complete → 파싱 요청 → 결과 수신 → COMPLETED")
    void completesEndToEnd() throws Exception {
        JsonNode created = createPaper("e2e-success.pdf");
        UUID paperId = UUID.fromString(created.get("paperId").asText());
        String fileKey = created.get("fileKey").asText();

        assertStatus(paperId, PaperStatus.UPLOAD_PENDING);

        uploadTo(created.get("uploadUrl").asText());

        mockMvc.perform(post("/api/papers/{paperId}/complete", paperId).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        // AI 워커가 받을 파싱 요청이 실제로 큐에 있다
        List<Message> requests = receive(parseRequestQueueUrl(), 5);
        assertThat(requests).hasSize(1);
        JsonNode request = objectMapper.readTree(requests.get(0).body());
        assertThat(request.get("paperId").asText()).isEqualTo(paperId.toString());
        assertThat(request.get("fileKey").asText()).isEqualTo(fileKey);

        // 워커가 파싱을 마치고 결과를 돌려준다
        publishParseResult("""
                {"paperId": "%s", "status": "COMPLETED", "result": {"markdownKey": "papers/%s/parsed.md"}}
                """.formatted(paperId, paperId));

        awaitStatus(paperId, PaperStatus.COMPLETED);
    }

    @Test
    @DisplayName("실패 흐름: 파싱 실패 결과를 받으면 FAILED가 되고 error.code가 기록된다")
    void reflectsParseFailureEndToEnd() throws Exception {
        JsonNode created = createPaper("e2e-failure.pdf");
        UUID paperId = UUID.fromString(created.get("paperId").asText());

        uploadTo(created.get("uploadUrl").asText());
        mockMvc.perform(post("/api/papers/{paperId}/complete", paperId).with(userJwt()))
                .andExpect(status().isOk());

        publishParseResult("""
                {"paperId": "%s", "status": "FAILED", "error": {"code": "PDF_UNREADABLE"}}
                """.formatted(paperId));

        awaitStatus(paperId, PaperStatus.FAILED);
        // 실패 코드는 저장하되 사용자에게는 노출하지 않는다 (MVP)
        assertThat(reload(paperId).getErrorCode()).isEqualTo("PDF_UNREADABLE");
    }

    private JsonNode createPaper(String filename) throws Exception {
        String response = mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("filename", filename, "contentType", "application/pdf"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** presigned URL로 S3에 직접 PUT — BE를 거치지 않는다. */
    private void uploadTo(String uploadUrl) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        .header("Content-Type", "application/pdf")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "%PDF-1.4\n%e2e fake pdf\n", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    private void assertStatus(UUID paperId, PaperStatus expected) throws Exception {
        mockMvc.perform(get("/api/papers/{paperId}/status", paperId).with(userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected.name()));
    }

    private void awaitStatus(UUID paperId, PaperStatus expected) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertStatus(paperId, expected));
    }
}
