package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * complete 흐름의 상태 전이만 담는 트랜잭션 단위. 조율자({@link PaperUploadCompletionService})와
 * 별도 빈인 이유는 트랜잭션 경계를 실제로 쪼개기 위해서다 (design D6, tasks 4.3).
 *
 * <p>조율 메서드 하나에 {@code @Transactional}을 걸면 큐 발행 실패가 이미 커밋됐어야 할
 * {@code UPLOADED}까지 롤백시킨다 — 그러면 complete를 재호출해도 조건부 전이가 다시 1 row가 되어
 * 파싱 요청이 중복 발행될 수 있다. 같은 클래스 안의 {@code @Transactional} 메서드를 self-invocation 하면
 * 프록시를 타지 않아 경계가 생기지도 않는다. 그래서 빈을 나눈다.
 */
@Service
@RequiredArgsConstructor
public class PaperTransitions {

    private final PaperRepository paperRepository;

    /**
     * {@code UPLOAD_PENDING → UPLOADED}를 원자적으로 판정하고 즉시 커밋한다.
     *
     * @return 이 호출이 전이의 주인이면 true (동시 호출 중 한 건만 true). false면 이미 누가 전이시켰다.
     */
    @Transactional
    public boolean markUploaded(UUID paperId) {
        return paperRepository.markUploaded(paperId, Instant.now()) == 1;
    }

    /**
     * 파싱 요청 발행에 성공한 뒤의 {@code UPLOADED → PROCESSING}. 별도 트랜잭션으로 커밋한다.
     *
     * <p>CAS가 0 row면 재조회해 분기한다 — terminal이면 빠른 결과가 먼저 온 정상 경합이라
     * 그 상태를 그대로 돌려주고(5xx 아님), 그 외는 예상하지 못한 상태라 예외를 던진다 (spec §3).
     *
     * <p>이 커밋이 실패하면 레코드는 {@code UPLOADED}에 남고 파싱은 진행된다 — ADR-001 §5가 문서화한
     * MVP 갭이라 복구하지 않는다.
     */
    @Transactional
    public PaperStatusView markProcessing(UUID paperId) {
        if (paperRepository.markProcessing(paperId, Instant.now()) == 1) {
            return PaperStatusView.from(reload(paperId));
        }
        Paper paper = reload(paperId);
        if (paper.getStatus().isTerminal()) {
            return PaperStatusView.from(paper);   // 빠른 결과 선도착 — 정상 경합
        }
        throw new IllegalStateException(
                "UPLOADED가 아닌 상태에서 PROCESSING 전이를 시도했습니다: paperId=%s, status=%s"
                        .formatted(paperId, paper.getStatus()));
    }

    private Paper reload(UUID paperId) {
        return paperRepository.findById(paperId).orElseThrow(
                () -> new IllegalStateException("전이 직전에 사라진 논문입니다: " + paperId));
    }

    /**
     * 결과 수신 시의 {@code UPLOADED | PROCESSING → COMPLETED | FAILED}.
     *
     * @return 전이했으면 true. false면 이미 terminal이거나 UPLOAD_PENDING이거나 알 수 없는 paperId다 (중복 수신 등)
     */
    @Transactional
    public boolean markParsed(UUID paperId, PaperStatus terminal, String errorCode) {
        return paperRepository.markParsed(paperId, terminal, errorCode, Instant.now()) == 1;
    }
}
