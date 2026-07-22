package com.ymc.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 애플리케이션 공통 설정 바인딩. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AppConfig {
}
