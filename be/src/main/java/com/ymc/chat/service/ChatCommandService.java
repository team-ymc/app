// chat/service/ChatCommandService.java
package com.ymc.chat.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.service.PaperChatAccessValidator;

/**
 * 채팅 시작 트랜잭션 — 검증과 저장까지만. 스트리밍은 이 메서드가 commit된 뒤
 * {@link ChatStreamService}가 시작한다 (계약: commit 뒤 message.started).
 *
 * <p>세션당 동시 실행 1개는 기존 세션 행의 PESSIMISTIC_WRITE 잠금으로 보장한다.
 * 새 세션은 방금 만든 UUID라 경쟁 상대가 존재할 수 없다 (설계 §3).
 */
@Service
public class ChatCommandService {

    private final PaperChatAccessValidator paperChatAccessValidator;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final TransactionTemplate requiresNewTx;

    public ChatCommandService(
            PaperChatAccessValidator paperChatAccessValidator,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            PlatformTransactionManager transactionManager) {
        this.paperChatAccessValidator = paperChatAccessValidator;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN / PAPER_NOT_READY — 논문 검증 실패
     * @throws ApiException CHAT_SESSION_NOT_FOUND — 세션 없음·소유/논문 불일치
     * @throws ApiException CHAT_RUN_IN_PROGRESS — 세션에 GENERATING assistant 존재
     * @throws ApiException CLIENT_MESSAGE_ID_CONFLICT — 같은 id, 다른 content
     * @throws DuplicateChatMessageException — 같은 id, 같은 content (멱등 재전송)
     */
    @Transactional
    public ChatStartResult start(
            UUID ownerId, UUID paperId, UUID sessionIdOrNull, UUID clientMessageId, String content) {

        paperChatAccessValidator.validateChatReady(paperId, ownerId);
        rejectDuplicate(clientMessageId, content);

        ChatSession session = resolveSession(ownerId, paperId, sessionIdOrNull);

        if (chatMessageRepository.existsBySessionIdAndStatus(
                session.getId(), ChatMessageStatus.GENERATING)) {
            throw new ApiException(ErrorCode.CHAT_RUN_IN_PROGRESS, "이미 답변을 생성하고 있습니다.");
        }

        Instant now = Instant.now();
        ChatMessage assistant;
        try {
            chatMessageRepository.save(
                    ChatMessage.userMessage(session, clientMessageId, content, now));
            assistant = chatMessageRepository.saveAndFlush(
                    ChatMessage.assistantGenerating(session, clientMessageId, now));
        } catch (DataIntegrityViolationException e) {
            // 사전 조회를 나란히 통과한 동시 재전송 — 유니크 제약이 최후 방어선 (paper design D4 준용).
            // PG는 제약 위반 후 같은 트랜잭션의 추가 쿼리를 거부하므로(aborted, 25P02)
            // 재조회는 REQUIRES_NEW 새 트랜잭션에서 한다. 승자 커밋은 이미 끝났으므로 조회 가능하다.
            requiresNewTx.executeWithoutResult(tx -> rejectDuplicate(clientMessageId, content));
            throw e; // rejectDuplicate가 못 잡는 위반이면 예상 밖 — 그대로 5xx
        }

        return new ChatStartResult(paperId, session.getId(), assistant.getId(), clientMessageId);
    }

    /** 재전송 판정. 같은 content면 멱등(기존 상태 반환), 다르면 CONFLICT. */
    private void rejectDuplicate(UUID clientMessageId, String content) {
        Optional<ChatMessage> existingUser =
                chatMessageRepository.findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.USER);
        if (existingUser.isEmpty()) {
            return;
        }
        if (!existingUser.get().getContent().equals(content)) {
            throw new ApiException(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT,
                    "clientMessageId가 다른 요청에 이미 사용되었습니다.");
        }
        ChatMessage assistant = chatMessageRepository
                .findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.ASSISTANT)
                .orElseThrow(() -> new IllegalStateException(
                        "user 행만 있고 assistant 행이 없습니다: " + clientMessageId));
        throw new DuplicateChatMessageException(
                assistant.getSession().getId(), assistant.getId(), assistant.getStatus());
    }

    private ChatSession resolveSession(UUID ownerId, UUID paperId, UUID sessionIdOrNull) {
        if (sessionIdOrNull == null) {
            return chatSessionRepository.save(ChatSession.open(ownerId, paperId, Instant.now()));
        }
        ChatSession session = chatSessionRepository.findWithLockById(sessionIdOrNull)
                .orElseThrow(this::sessionNotFound);
        if (!session.getOwnerId().equals(ownerId) || !session.getPaperId().equals(paperId)) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션도 404 (계약)
        }
        return session;
    }

    private ApiException sessionNotFound() {
        return new ApiException(ErrorCode.CHAT_SESSION_NOT_FOUND,
                "세션이 없거나 이 논문의 세션이 아닙니다.");
    }
}
