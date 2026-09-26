package com.ymc.paper.service;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.ymc.paper.domain.DocumentStatus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 파싱 요청 발행부터 결과 반영까지의 lead time. SQS 대기와 워커 처리 시간을 합친 값이다. */
@Component
public class ParseMetrics {

    private final Timer completed;
    private final Timer failed;

    public ParseMetrics(MeterRegistry registry) {
        this.completed = leadTime(registry, "completed");
        this.failed = leadTime(registry, "failed");
    }

    private static Timer leadTime(MeterRegistry registry, String outcome) {
        // 파싱은 분 단위라 bucket도 분 단위. 워커 deadline(3600s)까지 덮는다
        return Timer.builder("parse.lead.time").tag("outcome", outcome)
                .serviceLevelObjectives(
                        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
                        Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(20),
                        Duration.ofMinutes(30), Duration.ofMinutes(60))
                .register(registry);
    }

    public void leadTime(DocumentStatus terminal, Duration elapsed) {
        (terminal == DocumentStatus.COMPLETED ? completed : failed).record(elapsed);
    }
}
