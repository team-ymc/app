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
import com.ymc.paper.service.PaperAccessRecorder;
import com.ymc.paper.service.PaperChatAccessValidator;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.infra.PlanProperties;
import com.ymc.plan.service.UsageService;
import com.ymc.user.domain.UserRepository;

/**
 * 채팅 시작 트랜잭션 — 검증과 저장까지만. 스트리밍은 이 메서드가 commit된 뒤
 * {@link ChatStreamService}가 시작한다 (계약: commit 뒤 message.started).
 *
 * <p>세션당 동시 실행 1개는 기존 세션 행의 PESSIMISTIC_WRITE 잠금으로, 사용자 전체 활성 세션 상한은
 * users 행 잠금 아래에서 GENERATING assistant를 세어 보장한다. 새 세션은 방금 만든 UUID라
 * 세션 잠금 경쟁 상대가 없다.
 */
@Service
public class ChatCommandService {

    private final PaperChatAccessValidator paperChatAccessValidator;
    private final PaperAccessRecorder paperAccessRecorder;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final UsageService usageService;
    private final UserRepository userRepository;
    private final PlanProperties planProperties;
    private final TransactionTemplate requiresNewTx;

    public ChatCommandService(
            PaperChatAccessValidator paperChatAccessValidator,
            PaperAccessRecorder paperAccessRecorder,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            UsageService usageService,
            UserRepository userRepository,
            PlanProperties planProperties,
            PlatformTransactionManager transactionManager) {
        this.paperChatAccessValidator = paperChatAccessValidator;
        this.paperAccessRecorder = paperAccessRecorder;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.usageService = usageService;
        this.userRepository = userRepository;
        this.planProperties = planProperties;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN / PAPER_NOT_READY — 논문 검증 실패
     * @throws ApiException CHAT_SESSION_NOT_FOUND — 세션 없음·소유/논문 불일치
     * @throws ApiException CHAT_RUN_IN_PROGRESS — 세션에 GENERATING assistant 존재
     * @throws ApiException CHAT_CONCURRENCY_LIMIT_EXCEEDED — 사용자 전체 활성 세션이 상한
     * @throws ApiException CHAT_USAGE_LIMIT_EXCEEDED — 이번 달 AI 질의 한도 초과
     * @throws ApiException CLIENT_MESSAGE_ID_CONFLICT — 같은 id, 다른 content
     * @throws DuplicateChatMessageException — 같은 id, 같은 content (멱등 재전송)
     */
    @Transactional
    public ChatStartResult start(
            UUID ownerId, UUID paperId, UUID sessionIdOrNull, UUID clientMessageId, String content) {

        paperChatAccessValidator.validateChatReady(paperId, ownerId);
        rejectDuplicate(ownerId, paperId, clientMessageId, content);

        ChatSession session = resolveSession(ownerId, paperId, sessionIdOrNull, content);

        if (sessionIdOrNull != null) {
            // 세션 잠금을 기다리는 동안 선행 요청이 같은 clientMessageId를 저장했을 수 있다.
            // 실행 중 여부보다 구체적인 멱등 계약(DUPLICATE/CONFLICT)을 먼저 판정한다.
            rejectDuplicate(ownerId, paperId, clientMessageId, content);
        }

        if (chatMessageRepository.existsBySessionIdAndStatus(
                session.getId(), ChatMessageStatus.GENERATING)) {
            throw new ApiException(ErrorCode.CHAT_RUN_IN_PROGRESS, "이미 답변을 생성하고 있습니다.");
        }

        // 락 획득 순서: chat_session → users → paper → usage_bucket.
        userRepository.findWithLockById(ownerId)
                .orElseThrow(() -> new IllegalStateException("사용자 행 없음: " + ownerId));

        // 사용자 잠금을 기다리는 동안 같은 clientMessageId가 커밋됐을 수 있다.
        // 새 세션 경로는 세션 잠금이 없어 이 재판정이 유일한 멱등 방어선
        rejectDuplicate(ownerId, paperId, clientMessageId, content);

        int maxActive = planProperties.chat().maxActiveSessions();
        long active = chatMessageRepository.countBySessionOwnerIdAndRoleAndStatus(
                ownerId, ChatMessageRole.ASSISTANT, ChatMessageStatus.GENERATING);
        if (active >= maxActive) {
            throw new ApiException(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED,
                    "동시에 진행 중인 답변이 " + maxActive + "개입니다. 하나가 끝나면 다시 시도하세요.");
        }

        Instant now = Instant.now();
        int userSeq = chatMessageRepository.findMaxSeqBySessionId(session.getId()).orElse(0) + 1;
        session.recordActivity(now);
        paperAccessRecorder.recordAccess(paperId, now);
        ChatMessage assistant;
        try {
            // reserve도 같은 유니크 제약(usage_type+source_id) 경쟁에 걸릴 수 있어 메시지 저장과
            // 같은 재시도 경로를 타도록 여기 둔다 — 별도 catch를 두지 않는다.
            usageService.reserve(ownerId, UsageType.AI_QUERY, clientMessageId, UsageSourceType.CHAT_MESSAGE);
            chatMessageRepository.save(
                    ChatMessage.userMessage(session, clientMessageId, content, userSeq, now));
            assistant = chatMessageRepository.saveAndFlush(
                    ChatMessage.assistantGenerating(session, clientMessageId, userSeq + 1, now));
        } catch (DataIntegrityViolationException e) {
            // 사전 조회를 나란히 통과한 동시 재전송 — 유니크 제약이 최후 방어선 (paper design D4 준용).
            // PG는 제약 위반 후 같은 트랜잭션의 추가 쿼리를 거부하므로(aborted, 25P02)
            // 재조회는 REQUIRES_NEW 새 트랜잭션에서 한다. 승자 커밋은 이미 끝났으므로 조회 가능하다.
            requiresNewTx.executeWithoutResult(
                    tx -> rejectDuplicate(ownerId, paperId, clientMessageId, content));
            throw e; // rejectDuplicate가 못 잡는 위반이면 예상 밖 — 그대로 5xx
        }

        return new ChatStartResult(paperId, session.getId(), assistant.getId(), clientMessageId);
    }

    /**
     * 재전송 판정. 요청자 소유·같은 논문의 재전송만 멱등으로 인정한다.
     * 같은 content면 멱등(기존 상태 반환), 다르면 CONFLICT.
     */
    private void rejectDuplicate(UUID ownerId, UUID paperId, UUID clientMessageId, String content) {
        Optional<ChatMessage> existingUser =
                chatMessageRepository.findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.USER);
        if (existingUser.isEmpty()) {
            return;
        }
        ChatSession existingSession = existingUser.get().getSession();
        if (!existingSession.belongsTo(ownerId, paperId)) {
            // 타인·다른 논문의 재사용 — 기존 실행의 식별자를 노출하지 않고 거부한다
            throw new ApiException(ErrorCode.CLIENT_MESSAGE_ID_CONFLICT,
                    "clientMessageId가 다른 요청에 이미 사용되었습니다.");
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

    private ChatSession resolveSession(
            UUID ownerId, UUID paperId, UUID sessionIdOrNull, String content) {
        if (sessionIdOrNull == null) {
            return chatSessionRepository.save(
                    ChatSession.open(ownerId, paperId, content, Instant.now()));
        }
        ChatSession session = chatSessionRepository.findWithLockById(sessionIdOrNull)
                .orElseThrow(this::sessionNotFound);
        if (!session.belongsTo(ownerId, paperId) || session.isDeleted()) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션·삭제된 세션도 404 (계약)
        }
        return session;
    }

    /**
     * 세션을 논리 삭제한다. 메시지 row는 남긴다 — GENERATING 중이어도 삭제할 수 있고,
     * 진행 중이던 relay의 종결 전이(markCompleted/markFailed)는 그대로 이어진다.
     *
     * <p>{@code findWithLockById}로 start와 직렬화한다 — 삭제 커밋 후 start는
     * 삭제된 세션을 보고 404를 반환한다.
     *
     * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN — 논문 검증 실패
     * @throws ApiException CHAT_SESSION_NOT_FOUND — 세션 없음·소유/논문 불일치·이미 삭제됨
     */
    @Transactional
    public void deleteSession(UUID ownerId, UUID paperId, UUID sessionId) {
        paperChatAccessValidator.validateOwned(paperId, ownerId);
        ChatSession session = chatSessionRepository.findWithLockById(sessionId)
                .orElseThrow(this::sessionNotFound);
        if (!session.belongsTo(ownerId, paperId) || session.isDeleted()) {
            throw sessionNotFound(); // 존재 여부를 숨긴다 — 남의 세션·삭제된 세션도 404 (계약)
        }
        session.markDeleted(Instant.now());
    }

    private ApiException sessionNotFound() {
        return new ApiException(ErrorCode.CHAT_SESSION_NOT_FOUND,
                "세션이 없거나 이 논문의 세션이 아닙니다.");
    }
}
