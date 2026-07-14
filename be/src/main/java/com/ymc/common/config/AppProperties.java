package com.ymc.common.config;

import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml의 app.* 바인딩.
 *
 * <p>fixedOwnerId: Sprint 01은 인증이 없어 모든 논문을 이 사용자 소유로 만든다.
 * 파일명 중복 판정이 "사용자의 기존 논문" 기준이므로 소유자 개념 자체는 지금부터 필요하다.
 * FT-001 도입 시 이 값 대신 인증 주체를 넣는다 (design D3).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(UUID fixedOwnerId) {
}
