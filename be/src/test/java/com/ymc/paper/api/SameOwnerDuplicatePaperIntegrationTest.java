package com.ymc.paper.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.support.IntegrationTest;

/** 같은 사용자가 동일 파일을 다시 등록하면 complete가 409 DUPLICATE_PAPER로 거절한다. */
class SameOwnerDuplicatePaperIntegrationTest extends IntegrationTest {

    @Autowired
    private UsageService usageService;

    private Paper givenCompletedPaper(String filename) throws Exception {
        Paper paper = givenPendingPaper(filename);
        givenUploadedObject(paper);
        mockMvc.perform(post("/api/papers/{id}/complete", paper.getId()).with(userJwt()))
                .andExpect(status().isOk());
        return reload(paper.getId());
    }

    private Paper givenReservedUploadedPaper(String filename) {
        Paper paper = givenPendingPaper(filename);
        tx.executeWithoutResult(s -> usageService.reserve(
                TEST_USER_ID, UsageType.PAPER_REGISTRATION, paper.getId(), UsageSourceType.PAPER));
        givenUploadedObject(paper);
        return paper;
    }

    @Test
    void 같은_사용자의_동일_파일_재등록은_409와_기존_paperId를_반환한다() throws Exception {
        Paper existing = givenCompletedPaper("origin.pdf");
        Paper duplicate = givenReservedUploadedPaper("renamed.pdf");

        mockMvc.perform(post("/api/papers/{id}/complete", duplicate.getId()).with(userJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PAPER"))
                .andExpect(jsonPath("$.existingPaperId").value(existing.getId().toString()));
    }

    @Test
    void 거절된_paper는_서재에서_사라지고_예약은_해제되고_올린_객체는_삭제된다() throws Exception {
        givenCompletedPaper("origin.pdf");
        Paper duplicate = givenReservedUploadedPaper("again.pdf");

        mockMvc.perform(post("/api/papers/{id}/complete", duplicate.getId()).with(userJwt()))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/papers/{id}/status", duplicate.getId()).with(userJwt()))
                .andExpect(status().isNotFound());
        assertThat(paperRepository.findAllByOwnerIdOrderByRecentAccess(TEST_USER_ID)).hasSize(1);
        assertThat(usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, duplicate.getId())
                .orElseThrow().getStatus()).isEqualTo(UsageRecordStatus.RELEASED);
        verify(fileStorage).delete(duplicate.getFileKey());
    }

    @Test
    void 삭제한_논문과_같은_파일은_다시_등록할_수_있다() throws Exception {
        Paper deleted = givenCompletedPaper("deleted.pdf");
        tx.executeWithoutResult(s -> paperRepository.markDeleted(deleted.getId(), Instant.now()));

        Paper again = givenReservedUploadedPaper("again.pdf");
        mockMvc.perform(post("/api/papers/{id}/complete", again.getId()).with(userJwt()))
                .andExpect(status().isOk());
    }

    @Test
    void 같은_사용자의_동일_파일_동시_complete는_하나만_등록된다() throws Exception {
        Paper first = givenReservedUploadedPaper("race-1.pdf");
        Paper second = givenReservedUploadedPaper("race-2.pdf");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = List.of(first.getId(), second.getId()).stream()
                .map(id -> pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/api/papers/{id}/complete", id).with(userJwt()))
                            .andReturn().getResponse().getStatus();
                }))
                .toList();
        start.countDown();
        List<Integer> statuses = List.of(
                results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        List<UUID> remaining = paperRepository.findAllByOwnerIdOrderByRecentAccess(TEST_USER_ID)
                .stream().map(Paper::getId).toList();
        assertThat(remaining).hasSize(1);
    }
}
