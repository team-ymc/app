package com.ymc.paper.service;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 선행지식 설명 결과별 누적 건수. */
@Component
public class PrerequisiteDefinitionMetrics {

    private final Counter hit;
    private final Counter generated;
    private final Counter failed;
    private final Counter rejectedConcurrent;
    private final Counter cacheError;

    public PrerequisiteDefinitionMetrics(MeterRegistry registry) {
        this.hit = outcome(registry, "hit");
        this.generated = outcome(registry, "generated");
        this.failed = outcome(registry, "failed");
        this.rejectedConcurrent = outcome(registry, "rejected_concurrent");
        this.cacheError = Counter.builder("prerequisite.definition.cache.errors").register(registry);
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("prerequisite.definitions").tag("outcome", outcome).register(registry);
    }

    public void hit() { hit.increment(); }
    public void generated() { generated.increment(); }
    public void failed() { failed.increment(); }
    public void rejectedConcurrent() { rejectedConcurrent.increment(); }
    public void cacheError() { cacheError.increment(); }
}
