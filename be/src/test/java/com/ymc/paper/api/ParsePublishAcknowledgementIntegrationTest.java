package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.service.port.ParseRequestPublisher.AcceptanceUnknownException;
import com.ymc.support.IntegrationTest;

import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.RequestThrottledException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class ParsePublishAcknowledgementIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    private SqsClient sqsClient;

    @Test
    void acceptedSendWithLostResponseDoesNotSendAgainOnComplete() throws Exception {
        Paper paper = givenPendingPaper("lost-response.pdf");
        givenUploadedObject(paper);
        doAnswer(call -> {
            call.callRealMethod();
            throw ApiCallAttemptTimeoutException.create(1000);
        }).when(sqsClient).sendMessage(any(SendMessageRequest.class));

        assertThatThrownBy(() -> complete(paper))
                .hasCauseInstanceOf(AcceptanceUnknownException.class)
                .hasRootCauseInstanceOf(ApiCallAttemptTimeoutException.class);
        var accepted = receive(parseRequestQueueUrl(), 1);
        assertThat(accepted).hasSize(1);
        sqsClient.deleteMessage(request -> request.queueUrl(parseRequestQueueUrl())
                .receiptHandle(accepted.getFirst().receiptHandle()));

        complete(paper).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(receive(parseRequestQueueUrl(), 1)).isEmpty();
        assertThat(documentRepository.count()).isEqualTo(1);
        assertThat(documentRepository.findById(reload(paper.getId()).getDocumentId()).orElseThrow().getAttempt())
                .isEqualTo(1);
    }

    @Test
    void explicitSqsRejectionStillAllowsCompleteToRescueThePublish() throws Exception {
        Paper paper = givenPendingPaper("rejected-send.pdf");
        givenUploadedObject(paper);
        doThrow(RequestThrottledException.builder().statusCode(400).build())
                .doCallRealMethod()
                .when(sqsClient).sendMessage(any(SendMessageRequest.class));

        assertThatThrownBy(() -> complete(paper)).hasRootCauseInstanceOf(RequestThrottledException.class);
        assertThat(receive(parseRequestQueueUrl(), 0)).isEmpty();
        complete(paper).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(receive(parseRequestQueueUrl(), 1)).hasSize(1);
    }

    @Test
    void retryPublishWithLostResponseKeepsProcessing() throws Exception {
        var registration = mockMvc.perform(post("/api/papers").with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPaperJson("retry-lost-response.pdf")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        Paper paper = reload(UUID.fromString(
                objectMapper.readTree(registration.getContentAsString()).get("paperId").asText()));
        givenUploadedObject(paper);
        complete(paper).andExpect(status().isOk());
        var initial = receive(parseRequestQueueUrl(), 1);
        assertThat(initial).hasSize(1);
        sqsClient.deleteMessage(request -> request.queueUrl(parseRequestQueueUrl())
                .receiptHandle(initial.getFirst().receiptHandle()));
        assertThat(documentTransitions.markParsedAndSettle(
                reload(paper.getId()).getDocumentId(), DocumentStatus.FAILED, "PARSE_FAILED"))
                .isTrue();

        doAnswer(call -> {
            call.callRealMethod();
            throw ApiCallAttemptTimeoutException.create(1000);
        }).when(sqsClient).sendMessage(any(SendMessageRequest.class));

        assertThatThrownBy(() -> mockMvc.perform(post("/api/papers/{id}/retry", paper.getId()).with(userJwt())))
                .hasCauseInstanceOf(AcceptanceUnknownException.class);
        mockMvc.perform(post("/api/papers/{id}/retry", paper.getId()).with(userJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(receive(parseRequestQueueUrl(), 1)).hasSize(1);
        assertThat(reload(paper.getId()).getFailedAt()).isNull();
    }

    private ResultActions complete(Paper paper) throws Exception {
        return mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()));
    }
}
