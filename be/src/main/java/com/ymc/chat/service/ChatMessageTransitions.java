package com.ymc.chat.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatMessageRepository;

import lombok.RequiredArgsConstructor;

/**
 * assistant 메시지의 종결 전이만 담는 트랜잭션 단위. relay 스레드가 호출한다.
 * 조건부 UPDATE라 완료·실패가 경쟁해도 한쪽만 1 row를 얻는다 (설계 §3 D6).
 */
@Service
@RequiredArgsConstructor
public class ChatMessageTransitions {

    private final ChatMessageRepository chatMessageRepository;

    /** @return 이 호출이 COMPLETED 전이의 주인이면 true */
    @Transactional
    public boolean complete(UUID messageId, String content) {
        return chatMessageRepository.markCompleted(messageId, content, Instant.now()) == 1;
    }

    /** @return 이 호출이 FAILED 전이의 주인이면 true */
    @Transactional
    public boolean fail(UUID messageId) {
        return chatMessageRepository.markFailed(messageId, Instant.now()) == 1;
    }
}
