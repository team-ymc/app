// chat/service/ChatQueryService.java
package com.ymc.chat.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.service.PaperChatAccessValidator;

import lombok.RequiredArgsConstructor;

/**
 * 세션 히스토리 읽기 경로 (YMC-260). 쓰기 경로(ChatCommandService)와 분리 — 잠금 없이
 * 읽기 전용 트랜잭션으로 처리한다.
 *
 * <p>세션 검증 규칙은 ChatCommandService.resolveSession과 동일: 없거나 소유·논문이
 * 다르면 존재를 숨기고 CHAT_SESSION_NOT_FOUND(404).
 */
@Service
@RequiredArgsConstructor
public class ChatQueryService {

    private final PaperChatAccessValidator paperChatAccessValidator;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    /** 논문 파싱 상태와 무관하게 조회한다 — validateChatReady가 아니라 validateOwned. */
    @Transactional(readOnly = true)
    public List<ChatSession> listSessions(UUID ownerId, UUID paperId) {
        paperChatAccessValidator.validateOwned(paperId, ownerId);
        return chatSessionRepository
                .findAllByOwnerIdAndPaperIdOrderByLastMessageAtDesc(ownerId, paperId);
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> listMessages(UUID ownerId, UUID paperId, UUID sessionId) {
        paperChatAccessValidator.validateOwned(paperId, ownerId);
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(ChatQueryService::sessionNotFound);
        if (!session.belongsTo(ownerId, paperId)) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션도 404 (계약)
        }
        return chatMessageRepository.findAllBySessionIdOrderBySeqAsc(sessionId);
    }

    private static ApiException sessionNotFound() {
        return new ApiException(ErrorCode.CHAT_SESSION_NOT_FOUND,
                "세션이 없거나 이 논문의 세션이 아닙니다.");
    }
}
