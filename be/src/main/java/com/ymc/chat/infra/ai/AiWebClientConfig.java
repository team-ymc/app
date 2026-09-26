package com.ymc.chat.infra.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/** AI 서버용 WebClient (FT-007). */
@Configuration
public class AiWebClientConfig {

    /**
     * responseTimeout은 걸지 않는다 — 스트리밍 응답에서 발화 시점(헤더 수신 vs 전체 바디)이
     * 문서상 보장되지 않아 정상 장기 스트림을 끊을 위험이 있다. 침묵은 어댑터의
     * Flux.timeout(idle), 총 시한은 relay 워치독이 담당한다 (설계 §4).
     *
     * <p>빌더는 Boot가 주입하는 것을 쓴다 — 관측 customizer가 붙어 호출이 http_client_requests로 집계된다.
     */
    @Bean
    WebClient aiWebClient(WebClient.Builder builder, AiProperties aiProperties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);
        return builder
                .baseUrl(aiProperties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
