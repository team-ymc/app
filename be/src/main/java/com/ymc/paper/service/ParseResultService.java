package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 결과 반영 — 상태 전이(UPLOADED|PROCESSING → COMPLETED|FAILED) + 본문 적재.
 *
 * <p>상태의 단일 writer는 BE다. 전이는 조건부 UPDATE라 중복 수신·이미 terminal인 레코드는 0 row가 되고,
 * 그때는 경고만 남기고 정상 소비한다.
 *
 * <p>적재는 전이 성공 여부와 독립적으로 판정한다: "COMPLETED 결과 + manifestKey 있음 +
 * 아직 미적재"면 적재한다. 전이가 커밋된 뒤 적재만 실패해 재전달된 메시지도 이 조건으로 복구된다.
 * 전이가 안 됐고 논문이 COMPLETED도 아니면(알 수 없는 paperId 등) 적재하지 않는다.
 *
 * <p>DB 연결 실패·적재 실패 등은 여기서 삼키지 않는다. 예외가 리스너까지 올라가야 SQS가 재전달한다.
 * 비복구 패키지(파일 누락 등)는 재전달 5회 후 DLQ로 빠진다.
 */
@Service
@RequiredArgsConstructor
public class ParseResultService {

    private static final Logger log = LoggerFactory.getLogger(ParseResultService.class);

    private final PaperTransitions transitions;
    private final PaperRepository paperRepository;
    private final PaperContentIngestService ingestService;

    /**
     * @param terminal    {@code COMPLETED} 또는 {@code FAILED}
     * @param errorCode   실패 코드. {@code COMPLETED}면 null
     * @param manifestKey 파서 패키지 manifest S3 key. completed면 필수(계약 0.2.0), failed면 null
     */
    public void apply(UUID paperId, PaperStatus terminal, String errorCode, String manifestKey) {
        boolean transitioned = transitions.markParsed(paperId, terminal, errorCode);
        if (transitioned) {
            log.info("파싱 결과 반영: paperId={}, status={}", paperId, terminal);
        } else {
            log.warn("파싱 결과 미반영, 이미 terminal이거나 진행 전: paperId={}, status={}", paperId, terminal);
        }

        if (terminal != PaperStatus.COMPLETED || manifestKey == null) {
            return;
        }
        boolean completed = transitioned || paperRepository.findById(paperId)
                .map(p -> p.getStatus() == PaperStatus.COMPLETED)
                .orElse(false);
        if (completed && !ingestService.isIngested(paperId)) {
            ingestService.ingest(paperId, manifestKey);
            log.info("본문 적재 완료: paperId={}, manifestKey={}", paperId, manifestKey);
        }
    }
}
