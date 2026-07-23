package com.ymc.chat.infra;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ymc.chat.infra.ai.AiProperties;
import com.ymc.chat.infra.ai.ChatStreamProperties;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 채팅 스트림의 실행 자원 (설계 §4 스레딩 모델).
 *
 * <p>relay 작업(emitter.send·DB)은 event loop이 아니라 이 scheduler에서 실행된다.
 * virtual thread라 스트림 수만큼 점유해도 비용이 거의 없다.
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, ChatStreamProperties.class})
public class ChatStreamConfig {

    /** publishOn 핸드오프 대상. 종료 시 진행 중 작업까지 정리한다. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService chatRelayExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    Scheduler chatRelayScheduler(ExecutorService chatRelayExecutor) {
        return Schedulers.fromExecutorService(chatRelayExecutor);
    }

    /** deadline 워치독·heartbeat 타이머 전용. 작업이 즉시 반환되는 것만 올린다. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService chatTimerExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-stream-timer");
            t.setDaemon(true);
            return t;
        });
    }
}
