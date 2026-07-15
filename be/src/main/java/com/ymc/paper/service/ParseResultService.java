package com.ymc.paper.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * 파싱 결과 반영 — {@code PROCESSING → COMPLETED | FAILED} (design D2·D7).
 *
 * <p>상태의 단일 writer는 BE다. 전이는 조건부 UPDATE라 중복 수신·이미 terminal인 레코드는 0 row가 되고,
 * 그때는 경고만 남기고 정상 소비한다 — 재시도해도 결과가 달라지지 않는다.
 *
 * <p>DB 연결 실패·타임아웃 등 일시적 장애는 여기서 삼키지 않는다. 예외가 리스너까지 올라가야 SQS가
 * 메시지를 재전달한다.
 */
@Service
@RequiredArgsConstructor
public class ParseResultService {

    private static final Logger log = LoggerFactory.getLogger(ParseResultService.class);

    private final PaperTransitions transitions;

    /**
     * @param terminal  {@code COMPLETED} 또는 {@code FAILED}
     * @param errorCode 실패 코드. {@code COMPLETED}면 null
     */
    public void apply(UUID paperId, PaperStatus terminal, String errorCode) {
        if (transitions.markParsed(paperId, terminal, errorCode)) {
            log.info("파싱 결과 반영: paperId={}, status={}", paperId, terminal);
            return;
        }

        // 알 수 없는 paperId이거나 이미 전이된(=PROCESSING이 아닌) 레코드. 중복 수신이거나,
        // complete의 PROCESSING 커밋이 실패해 UPLOADED에 머문 경우다 (design D6의 의도된 갭).
        // 어느 쪽이든 재전달해도 달라지지 않으므로 관측만 하고 정상 소비한다.
        log.warn("파싱 결과 미반영, PROCESSING 아님: paperId={}, status={}", paperId, terminal);
    }
}
