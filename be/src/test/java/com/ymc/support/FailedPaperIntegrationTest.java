package com.ymc.support;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.plan.domain.UsageRecordStatus;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

/** 실패·재시도 시나리오가 공유하는 준비 단계. 설정은 IntegrationTest 그대로라 컨텍스트를 나눠 쓴다. */
public abstract class FailedPaperIntegrationTest extends IntegrationTest {

    @Autowired
    protected UsageService usageService;

    /** 등록 직후와 같은 상태 — Paper와 RESERVED 사용량 기록. */
    protected Paper givenReservedPaper(UUID ownerId, String filename) {
        Paper paper = paperRepository.save(Paper.register(ownerId, filename, Instant.now()));
        tx.executeWithoutResult(s -> usageService.reserve(
                ownerId, UsageType.PAPER_REGISTRATION, paper.getId(), UsageSourceType.PAPER));
        return paper;
    }

    /** 두 Paper가 Document 하나를 공유하고 파싱이 진행 중인 상태. 요청 식별자는 first다. */
    protected Document givenSharedProcessingDocument(Paper first, Paper second) {
        Document document = givenLinkedDocument(first);
        tx.executeWithoutResult(s ->
                paperRepository.linkDocument(second.getId(), document.getId(), Instant.now()));
        documentTransitions.markProcessing(document.getId());
        return documentOf(document.getId());
    }

    /** 위 상태에서 파싱이 PARSE_FAILED로 끝난 상태. 두 Paper 모두 환불됐다. */
    protected Document givenSharedFailedDocument(Paper first, Paper second) {
        Document document = givenSharedProcessingDocument(first, second);
        documentTransitions.markParsedAndSettle(
                document.getId(), DocumentStatus.FAILED, "PARSE_FAILED");
        return documentOf(document.getId());
    }

    protected Document documentOf(UUID documentId) {
        return documentRepository.findById(documentId).orElseThrow();
    }

    protected UsageRecordStatus recordStatusOf(UUID paperId) {
        return usageRecordRepository
                .findByUsageTypeAndSourceId(UsageType.PAPER_REGISTRATION, paperId)
                .orElseThrow().getStatus();
    }
}
