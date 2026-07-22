package com.ymc.chat.domain;

/**
 * 계약(openapi.yaml `ChatMessageStatus`)과 1:1. GENERATING에서 COMPLETED 또는 FAILED로만 전이한다.
 * user 메시지는 생성 즉시 COMPLETED다.
 */
public enum ChatMessageStatus { GENERATING, COMPLETED, FAILED }
