package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/** 업로드·파싱의 정체 레코드를 회수한다. 항목별 CAS라 중복 실행이 무해하다. */
@Component
@RequiredArgsConstructor
public class StalePaperCleanup {

    private static final Logger log = LoggerFactory.getLogger(StalePaperCleanup.class);

    private final PaperRepository paperRepository;
    private final DocumentRepository documentRepository;
    private final DocumentTransitions documentTransitions;
    private final UsageService usageService;
    private final PlanProperties properties;
    private final TransactionTemplate tx;

    @Scheduled(fixedDelayString = "${plan.cleanup.interval}")
    public void run() {
        Instant now = Instant.now();
        expireStalePending(now.minus(properties.cleanup().uploadPendingDeadline()));
        failStaleDocuments(now.minus(properties.cleanup().processingDeadline()));
    }

    private void expireStalePending(Instant cutoff) {
        for (UUID paperId : paperRepository.findStaleUploadPendingIds(cutoff)) {
            tx.executeWithoutResult(s -> {
                if (paperRepository.markExpired(paperId, Instant.now()) == 1) {
                    usageService.release(UsageType.PAPER_REGISTRATION, paperId);
                    log.info("정체 UPLOAD_PENDING 만료: paperId={}", paperId);
                }
            });
        }
    }

    private void failStaleDocuments(Instant cutoff) {
        List<UUID> staleIds = documentRepository.findStaleIds(
                List.of(DocumentStatus.UPLOADED, DocumentStatus.PROCESSING), cutoff);
        for (UUID documentId : staleIds) {
            if (documentTransitions.markParsedAndSettle(
                    documentId, DocumentStatus.FAILED, "STALE_CLEANUP")) {
                log.info("정체 document 실패 처리: documentId={}", documentId);
            }
        }
    }
}
