package com.ymc.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 통합 테스트용 PostgreSQL 컨테이너. 로컬/운영과 같은 DB에서 검증한다.
 *
 * <p>컨테이너를 빈으로 등록하면 Spring 애플리케이션 컨텍스트 캐싱에 올라타므로,
 * 같은 설정을 쓰는 테스트 클래스들이 컨텍스트와 함께 컨테이너도 재사용한다 (전체 실행에서 1회 기동).
 * 흔히 쓰는 {@code @Testcontainers} + {@code static @Container} 조합은 테스트 클래스마다
 * start/stop 하므로 쓰지 않는다.
 *
 * <p>{@code @ServiceConnection}이 컨테이너의 랜덤 포트를 읽어 spring.datasource.*를 자동 주입한다.
 * 이 주입은 application.yml의 값보다 우선하므로 local 프로파일 설정과 충돌하지 않는다.
 *
 * <p>Spring 컨텍스트를 띄우는 테스트에서만 필요하다. 엔티티의 상태 전이·불변식을 검증하는
 * 순수 단위 테스트는 이 클래스를 import 하지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** ⚠ 이미지 태그는 infra/local/docker-compose.yml의 postgres 서비스와 일치해야 한다. */
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
