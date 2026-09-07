package com.ymc.chat.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ymc.chat.domain.TranslationRun;
import com.ymc.chat.domain.TranslationRunRepository;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/** 서버가 죽어 종결 전이가 남지 않은 번역 GENERATING 정체를 회수한다. 전이가 CAS라 살아있는 relay와 경쟁해도 한쪽만 이긴다. */
@Component
@RequiredArgsConstructor
public class StaleTranslationCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleTranslationCleanup.class);

    private final TranslationRunRepository translationRunRepository;
    private final TranslationRunTransitions transitions;
    private final PlanProperties properties;

    @Scheduled(fixedDelayString = "${plan.cleanup.interval}")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.cleanup().chatGeneratingDeadline());
        List<TranslationRun> stale = translationRunRepository
                .findAllByStatusAndCreatedAtBefore(TranslationRunStatus.GENERATING, cutoff);
        for (TranslationRun run : stale) {
            if (transitions.fail(run.getId())) {
                log.info("정체 번역 GENERATING 정리: translationId={}", run.getId());
            }
        }
    }
}
