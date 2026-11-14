package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PaperUploadCompletionRaceIntegrationTest extends IntegrationTest {

    @Test
    void 지연된_complete는_중복_객체가_삭제됐어도_이미_연결된_상태를_반환한다() throws Exception {
        Paper original = givenPendingPaper("original.pdf");
        givenUploadedObject(original);
        mockMvc.perform(post("/api/papers/{id}/complete", original.getId()).with(userJwt()))
                .andExpect(status().isOk());

        Paper duplicate = paperRepository.save(Paper.register(OTHER_USER_ID, "duplicate.pdf", Instant.now()));
        givenUploadedObject(duplicate);
        CountDownLatch firstAtHead = new CountDownLatch(1);
        CountDownLatch resumeHead = new CountDownLatch(1);
        AtomicBoolean firstHead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstHead.getAndSet(false)) {
                firstAtHead.countDown();
                assertThat(resumeHead.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(fileStorage).head(duplicate.getFileKey());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var delayed = executor.submit(() ->
                    mockMvc.perform(post("/api/papers/{id}/complete", duplicate.getId()).with(otherUserJwt()))
                            .andReturn().getResponse());
            try {
                assertThat(firstAtHead.await(15, TimeUnit.SECONDS)).isTrue();
                mockMvc.perform(post("/api/papers/{id}/complete", duplicate.getId()).with(otherUserJwt()))
                        .andExpect(status().isOk());
                assertThat(fileStorage.head(duplicate.getFileKey())).isEmpty();
            } finally {
                resumeHead.countDown();
            }
            var response = delayed.get(15, TimeUnit.SECONDS);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        }

        assertThat(reload(duplicate.getId()).getDocumentId()).isEqualTo(reload(original.getId()).getDocumentId());
        assertThat(documentRepository.count()).isEqualTo(1);
        verify(parseRequestPublisher, times(1)).publish(any(), any());
    }
}
