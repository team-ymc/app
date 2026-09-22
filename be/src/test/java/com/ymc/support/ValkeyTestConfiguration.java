package com.ymc.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** 통합 테스트용 Valkey. 컨테이너를 빈으로 등록해 컨텍스트와 함께 재사용한다. */
@TestConfiguration(proxyBeanMethods = false)
public class ValkeyTestConfiguration {

    /** ⚠ 이미지 태그는 infra/local의 valkey 서비스와 일치해야 한다. */
    private static final String VALKEY_IMAGE = "valkey/valkey:7.2.14-alpine";

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkeyContainer() {
        return new GenericContainer<>(VALKEY_IMAGE).withExposedPorts(6379);
    }
}
