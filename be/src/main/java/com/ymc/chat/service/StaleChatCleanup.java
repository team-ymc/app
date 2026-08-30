package com.ymc.chat.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.plan.infra.PlanProperties;

import lombok.RequiredArgsConstructor;

/**
 * 서버가 죽어 종결 전이가 남지 않은 GENERATING 정체를 회수한다. 전이가 CAS라 살아있는
 * relay와 경쟁해도 한쪽만 이기고, 중복 실행도 무해하다.
 */
@Component
@RequiredArgsConstructor
public class StaleChatCleanup {

    private static final Logger log = LoggerFactory.getLogger(StaleChatCleanup.class);

    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageTransitions transitions;
    private final PlanProperties properties;

    @Scheduled(fixedDelayString = "${plan.cleanup.interval}")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.cleanup().chatGeneratingDeadline());
        List<ChatMessage> stale = chatMessageRepository
                .findAllByRoleAndStatusAndCreatedAtBefore(
                        ChatMessageRole.ASSISTANT, ChatMessageStatus.GENERATING, cutoff);
        for (ChatMessage message : stale) {
            if (transitions.fail(message.getId(), message.getClientMessageId())) {
                log.info("정체 GENERATING 정리: messageId={}", message.getId());
            }
        }
    }
}
