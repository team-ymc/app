package com.ymc.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.ymc.paper.service.PaperUploadPolicy;

/** 애플리케이션 공통 설정 바인딩. */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, PaperUploadPolicy.class})
public class AppConfig {
}
