package com.ymc.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import com.ymc.chat.domain.ChatMessage;
import com.ymc.chat.domain.ChatMessageRepository;
import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.domain.ChatSessionRepository;
import com.ymc.paper.service.PaperAccessRecorder;
import com.ymc.paper.service.PaperChatAccessValidator;
import com.ymc.plan.service.UsageService;

@ExtendWith(MockitoExtension.class)
class ChatCommandServiceRaceTest {

    @Mock
    PaperChatAccessValidator paperChatAccessValidator;

    @Mock
    PaperAccessRecorder paperAccessRecorder;

    @Mock
    ChatSessionRepository chatSessionRepository;

    @Mock
    ChatMessageRepository chatMessageRepository;

    @Mock
    UsageService usageService;

    @Mock
    PlatformTransactionManager transactionManager;

    @InjectMocks
    ChatCommandService chatCommandService;

    @Test
    @DisplayName("세션 잠금 대기 중 생긴 동일 요청은 CHAT_RUN_IN_PROGRESS보다 DUPLICATE_MESSAGE를 우선한다")
    void duplicateCommittedWhileWaitingForSessionLockWinsOverRunInProgress() {
        UUID ownerId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();
        String content = "같은 질문";
        Instant now = Instant.now();
        ChatSession session = ChatSession.open(ownerId, paperId, "첫 질문", now);
        ChatMessage existingUser = ChatMessage.userMessage(session, clientMessageId, content, 3, now);
        ChatMessage existingAssistant = ChatMessage.assistantGenerating(session, clientMessageId, 4, now);

        when(chatMessageRepository.findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.USER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingUser));
        when(chatSessionRepository.findWithLockById(session.getId())).thenReturn(Optional.of(session));
        when(chatMessageRepository.findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.ASSISTANT))
                .thenReturn(Optional.of(existingAssistant));

        DuplicateChatMessageException duplicate = catchThrowableOfType(
                DuplicateChatMessageException.class,
                () -> chatCommandService.start(
                        ownerId, paperId, session.getId(), clientMessageId, content));

        assertThat(duplicate.getSessionId()).isEqualTo(session.getId());
        assertThat(duplicate.getMessageId()).isEqualTo(existingAssistant.getId());
        assertThat(duplicate.getStatus()).isEqualTo(ChatMessageStatus.GENERATING);

        var order = inOrder(chatMessageRepository, chatSessionRepository);
        order.verify(chatMessageRepository)
                .findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.USER);
        order.verify(chatSessionRepository).findWithLockById(session.getId());
        order.verify(chatMessageRepository)
                .findByClientMessageIdAndRole(clientMessageId, ChatMessageRole.USER);
        verify(chatMessageRepository, never())
                .existsBySessionIdAndStatus(session.getId(), ChatMessageStatus.GENERATING);
    }
}
