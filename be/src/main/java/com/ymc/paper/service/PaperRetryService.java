package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.service.RetryStart.Kind;

import lombok.RequiredArgsConstructor;

/**
 * 실패한 논문 재시도. 이 클래스에 {@code @Transactional}을 걸지 말 것 — DB 변경의 커밋과
 * 큐 발행은 서로 다른 경계여야 한다. 경계는 {@link PaperRetryTransitions}가 갖는다.
 */
@Service
@RequiredArgsConstructor
public class PaperRetryService {

    private static final Logger log = LoggerFactory.getLogger(PaperRetryService.class);

    private final PaperRetryTransitions transitions;
    private final DocumentParsingStarter parsingStarter;
    private final KnowledgeCompileStarter compileStarter;
    private final PaperRepository paperRepository;
    private final PaperDocumentViews views;

    public PaperStatusView retry(UUID paperId, UUID ownerId) {
        RetryStart start = transitions.begin(paperId, ownerId);
        log.info("논문 재시도: paperId={}, documentId={}, kind={}",
                paperId, start.documentId(), start.kind());
        if (start.kind() == Kind.PUBLISH_PARSE) {
            parsingStarter.publishRetry(
                    paperId, start.documentId(), start.requestPaperId(), start.fileKey());
        } else if (start.kind() == Kind.PUBLISH_COMPILE) {
            compileStarter.publishRetry(paperId, start.documentId(), start.requestPaperId());
        }
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(() ->
                new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다: " + paperId));
        return views.statusView(paper);
    }
}
