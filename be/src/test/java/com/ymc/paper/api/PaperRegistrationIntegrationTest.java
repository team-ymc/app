package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperRegistrationService;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

/**
 * spec: paper-registration — 정상 201 / 중복 409 / 검증 400 (tasks 3.4).
 */
class PaperRegistrationIntegrationTest extends IntegrationTest {

    private static final String FILENAME = "attention-is-all-you-need.pdf";

    @Autowired
    private PaperRegistrationService registrationService;

    @Test
    @DisplayName("정상 등록: UPLOAD_PENDING 레코드 생성 + presigned URL 발급 (201)")
    void createsRecordAndIssuesPresignedUrl() throws Exception {
        MvcResult result = mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOAD_PENDING"))
                .andExpect(jsonPath("$.paperId").isNotEmpty())
                .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
                .andExpect(jsonPath("$.uploadExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn();

        JsonNode body = readBody(result);
        UUID paperId = UUID.fromString(body.get("paperId").asText());

        // fileKey는 계약 형식 그대로
        assertThat(body.get("fileKey").asText()).isEqualTo("uploads/" + TEST_USER_ID + "/" + paperId + ".pdf");

        // 레코드가 실제로 UPLOAD_PENDING으로 저장됐다
        Paper saved = paperRepository.findById(paperId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaperStatus.UPLOAD_PENDING);
        assertThat(saved.getFilename()).isEqualTo(FILENAME);
        assertThat(saved.getOwnerId()).isEqualTo(TEST_USER_ID);

        // 만료 시각은 미래
        assertThat(Instant.parse(body.get("uploadExpiresAt").asText())).isAfter(Instant.now());
    }

    @Test
    @DisplayName("발급된 presigned URL로 S3에 직접 PUT 하면 fileKey 위치에 객체가 저장된다")
    void presignedUrlAcceptsDirectUpload() throws Exception {
        JsonNode body = readBody(mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt()))
                .andExpect(status().isCreated())
                .andReturn());

        String uploadUrl = body.get("uploadUrl").asText();
        String fileKey = body.get("fileKey").asText();

        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(uploadUrl))
                        // 서명에 contentType이 들어가므로 같은 헤더를 보내야 한다
                        .header("Content-Type", "application/pdf")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(fakePdf()))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(objectExists(fileKey)).isTrue();
    }

    @Test
    @DisplayName("중복 파일명: 레코드를 만들지 않고 409 DUPLICATE_FILENAME")
    void rejectsDuplicateFilename() throws Exception {
        mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt())).andExpect(status().isCreated());

        mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILENAME"));

        assertThat(paperRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("업로드에 실패해 UPLOAD_PENDING으로 남은 레코드도 중복으로 걸린다 (MVP 재업로드 미지원)")
    void pendingRecordAlsoCountsAsDuplicate() throws Exception {
        // 업로드하지 않아 UPLOAD_PENDING에 머무는 레코드
        mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt())).andExpect(status().isCreated());
        assertThat(paperRepository.findAll()).singleElement()
                .extracting(Paper::getStatus)
                .isEqualTo(PaperStatus.UPLOAD_PENDING);

        mockMvc.perform(createRequest(FILENAME, "application/pdf").with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FILENAME"));
    }

    @Test
    @DisplayName("등록된 Paper의 ownerId는 JWT subject다 (YMC-215)")
    void 소유자는_인증_주체다() throws Exception {
        mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"owner.pdf\",\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isCreated());

        Paper saved = paperRepository.findAll().get(0);
        assertThat(saved.getOwnerId()).isEqualTo(TEST_USER_ID);
        assertThat(saved.getFileKey())
                .isEqualTo("uploads/%s/%s.pdf".formatted(TEST_USER_ID, saved.getId()));
    }

    @Test
    @DisplayName("파일명 중복 판정은 사용자 단위 — 다른 사용자는 같은 파일명 등록 가능 (YMC-215)")
    void 중복_판정은_사용자_스코프다() throws Exception {
        String body = "{\"filename\":\"same.pdf\",\"contentType\":\"application/pdf\"}";
        mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        UUID otherUser = UUID.randomUUID();
        mockMvc.perform(post("/api/papers")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .jwt().jwt(j -> j.subject(otherUser.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    /**
     * 사전 조회를 나란히 통과한 동시 요청은 DB 유니크 제약이 잡아 409로 변환돼야 한다 (design D4).
     * MockMvc는 동시 호출을 보장하지 않으므로 서비스를 직접 경쟁시킨다.
     */
    @Test
    @DisplayName("동시 등록 경쟁: 한 건만 성공하고 나머지는 유니크 제약에 의해 409 DUPLICATE_FILENAME으로 변환된다")
    void concurrentRegistrationLetsOnlyOneWin() throws Exception {
        int attempts = 4;
        CountDownLatch startLine = new CountDownLatch(1);

        List<Outcome> outcomes;
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<Outcome>> calls = Collections.nCopies(attempts, () -> {
                startLine.await();
                try {
                    registrationService.register(TEST_USER_ID, "race.pdf", "application/pdf");
                    return Outcome.CREATED;
                } catch (ApiException e) {
                    return e.code() == ErrorCode.DUPLICATE_FILENAME
                            ? Outcome.DUPLICATE
                            : Outcome.OTHER_ERROR;
                }
            });

            List<Future<Outcome>> futures = calls.stream().map(pool::submit).toList();
            startLine.countDown();
            outcomes = futures.stream().map(PaperRegistrationIntegrationTest::get).toList();
        }

        assertThat(outcomes).filteredOn(Outcome.CREATED::equals).hasSize(1);
        assertThat(outcomes).filteredOn(Outcome.DUPLICATE::equals).hasSize(attempts - 1);
        assertThat(paperRepository.count()).isEqualTo(1);
    }

    private enum Outcome {
        CREATED, DUPLICATE, OTHER_ERROR
    }

    private static Outcome get(Future<Outcome> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("동시 등록 시도가 예상치 못하게 실패했습니다.", e);
        }
    }

    @Test
    @DisplayName("contentType 불허: 400 UNSUPPORTED_FILE_TYPE, 레코드 생성 안 함")
    void rejectsNonPdfContentType() throws Exception {
        mockMvc.perform(createRequest(FILENAME, "image/png").with(userJwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));

        assertThat(paperRepository.count()).isZero();
    }

    @Test
    @DisplayName("filename 누락: 400 VALIDATION_ERROR")
    void rejectsMissingFilename() throws Exception {
        mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contentType": "application/pdf"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(paperRepository.count()).isZero();
    }

    @Test
    @DisplayName("malformed JSON: 400 VALIDATION_ERROR")
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private MockHttpServletRequestBuilder createRequest(String filename, String contentType)
            throws Exception {
        return post("/api/papers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("filename", filename, "contentType", contentType)));
    }

    private JsonNode readBody(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private boolean objectExists(String fileKey) {
        try {
            s3.headObject(HeadObjectRequest.builder()
                    .bucket(awsProperties.s3().bucket())
                    .key(fileKey)
                    .build());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] fakePdf() {
        return "%PDF-1.4\n%fake pdf for test\n".getBytes();
    }
}
