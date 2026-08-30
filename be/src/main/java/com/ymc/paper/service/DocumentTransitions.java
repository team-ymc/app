package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;

import lombok.RequiredArgsConstructor;

/**
 * Document 상태 전이의 트랜잭션 단위. 조율자와 별도 빈인 이유는 전이 커밋과 큐 발행(외부 I/O)의
 * 경계를 실제로 쪼개기 위해서다 — 발행을 트랜잭션 안에 두면 row 락이 I/O에 물리고
 * "발행 성공 후 커밋 실패"의 유령 발행이 생긴다.
 */
@Service
@RequiredArgsConstructor
public class DocumentTransitions {

    private final DocumentRepository documentRepository;
    private final PaperRepository paperRepository;
    private final UsageService usageService;

    /** 파싱 시작 권한 선점을 즉시 커밋한다. true면 이 호출만 발행 권한을 갖는다. */
    @Transactional
    public boolean markProcessing(UUID documentId) {
        return documentRepository.markProcessing(documentId, Instant.now()) == 1;
    }

    /** 발행 실패 시 선점 반납. */
    @Transactional
    public boolean revertToUploaded(UUID documentId) {
        return documentRepository.revertToUploaded(documentId, Instant.now()) == 1;
    }

    /**
     * 결과 수신 전이 + 연결된 모든 Paper의 사용량 정산을 한 트랜잭션으로. 전이 주인일 때만
     * 정산한다 — 쪼개면 전이만 커밋되고 정산이 유실될 수 있다.
     *
     * @throws IllegalArgumentException COMPLETED·FAILED가 아닌 상태를 전달한 경우
     */
    @Transactional
    public boolean markParsedAndSettle(UUID documentId, DocumentStatus terminal, String errorCode) {
        if (terminal == null || !terminal.isTerminal()) {
            throw new IllegalArgumentException("Document 종결 상태만 허용됩니다: " + terminal);
        }
        boolean owner = documentRepository.markParsed(documentId, terminal, errorCode,
                Instant.now()) == 1;
        if (!owner) {
            return false;
        }
        List<UUID> paperIds = paperRepository.findIdsByDocumentId(documentId);
        if (terminal == DocumentStatus.COMPLETED) {
            usageService.confirmAll(UsageType.PAPER_REGISTRATION, paperIds);
        } else {
            usageService.releaseAll(UsageType.PAPER_REGISTRATION, paperIds);
        }
        return true;
    }
}
