package com.ymc.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 스케줄러 스레드는 기본 1개 — 정리 작업들이 순차 실행된다. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
