package com.ymc.plan.domain;

/** 원장 행이 가리키는 실행의 종류. usage_type이 같아도(AI_QUERY) 채팅·번역을 구분해 집계한다. */
public enum UsageSourceType {
    CHAT_MESSAGE, INLINE_TRANSLATION, PAPER
}
