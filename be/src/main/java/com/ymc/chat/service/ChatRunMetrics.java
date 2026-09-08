package com.ymc.chat.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 채팅 실행 메트릭. 기본 JVM 메트릭과 같은 MeterRegistry에 보관되고 /actuator/prometheus → Collector 경로로 수집된다.
 * 서버 기동 시 등록되므로 첫 요청 전에도 0값이 노출된다.
 * 사용자·논문·요청 ID는 시계열 수가 요청마다 늘어나므로 tag에 넣지 않는다.
 */
@Component
public class ChatRunMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger active = new AtomicInteger(); // 여러 스레드가 동시에 +- 1 : Atomic
    private final Timer ttft;

    public ChatRunMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Gauge: 현재 실행 중인 수. 수집 시 active의 값
        Gauge.builder("chat.active.runs", active, AtomicInteger::get).register(registry);
        // Timer: 관측 횟수·시간 합계·시간 구간별 분포를 기록
        ttft = timer("chat.ttft").register(registry);
        // Counter: 결과별 누적 종료 건수
        for (String outcome : new String[] {"success", "error", "timeout"}) {
            Counter.builder("chat.runs").tag("outcome", outcome).register(registry);
            timer("chat.duration").tag("outcome", outcome).register(registry);
        }
    }

    private Timer.Builder timer(String name) {
        // histogram bucket 경계. Grafana에서 p95 등을 계산하기 위한 구간이며,
        // 이 값 자체가 테스트 합격 기준이나 사용자에게 약속한 SLO는 아니다.
        return Timer.builder(name).serviceLevelObjectives(
                Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
                Duration.ofSeconds(300));
    }

    /**
     * 시작 트랜잭션 커밋 후 실행을 수락한 시점에 +1한다.
     * 시간의 기준점은 Controller 진입 시각이라 시작 트랜잭션 시간도 포함한다.
     */
    public Measurement start(long requestedAtNanos) {
        active.incrementAndGet();
        return new Measurement(requestedAtNanos);
    }

    // 요청별 측정 상태. Registry의 메트릭은 공유하지만 첫 delta·종료 여부는 실행마다 분리한다.
    public class Measurement {
        private final long requestedAtNanos;
        private final AtomicBoolean first = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        private Measurement(long requestedAtNanos) {
            this.requestedAtNanos = requestedAtNanos;
        }

        /** 빈 문자열을 제외한 첫 delta만 기록한다. delta 없이 실패하면 TTFT 표본을 만들지 않는다. */
        public void firstDelta() {
            // CAS로 첫 관측을 한 번만 허용한다. nanoTime 차이는 벽시계 변경과 무관한 경과 시간이다.
            if (!finished.get() && first.compareAndSet(false, true)) {
                ttft.record(System.nanoTime() - requestedAtNanos, TimeUnit.NANOSECONDS);
            }
        }

        /** 최종 저장·실패 처리가 끝난 뒤 호출한다. FE 연결 종료만으로 호출하지 않는다. */
        public void finish(String outcome) {
            // 타임아웃·오류·완료 콜백이 중복되더라도 감소와 결과 집계는 실행당 한 번만 한다.
            if (finished.compareAndSet(false, true)) {
                active.decrementAndGet();
                registry.counter("chat.runs", "outcome", outcome).increment();
                registry.timer("chat.duration", "outcome", outcome)
                        .record(System.nanoTime() - requestedAtNanos, TimeUnit.NANOSECONDS);
            }
        }
    }
}
