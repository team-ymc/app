package com.ymc.chat.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ymc.chat.api.dto.ChatSelectionDto;
import com.ymc.chat.domain.TranslationRun;
import com.ymc.chat.domain.TranslationRunRepository;
import com.ymc.chat.domain.TranslationRunStatus;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.service.PaperAccessRecorder;
import com.ymc.paper.service.PaperChatAccessValidator;
import com.ymc.plan.domain.UsageSourceType;
import com.ymc.plan.domain.UsageType;
import com.ymc.plan.service.UsageService;
import com.ymc.user.domain.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 인라인 번역 시작 트랜잭션 — 검증·row 생성·사용량 예약까지. 스트리밍은 commit 뒤
 * {@link TranslationStreamService}가 시작한다.
 *
 * <p>잠금 순서는 채팅과 같다: users → paper → usage_bucket. 사용자당 GENERATING 1개는
 * users 행 잠금 아래의 존재 검사로 보장한다.
 */
@Service
@RequiredArgsConstructor
public class TranslationCommandService {

    private final PaperChatAccessValidator paperChatAccessValidator;
    private final PaperAccessRecorder paperAccessRecorder;
    private final TranslationRunRepository translationRunRepository;
    private final UsageService usageService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * @throws ApiException PAPER_NOT_FOUND / FORBIDDEN / PAPER_NOT_READY / TRANSLATION_IN_PROGRESS /
     *         CHAT_USAGE_LIMIT_EXCEEDED
     */
    @Transactional
    public TranslationStartResult start(UUID ownerId, UUID paperId, ChatSelectionDto selection) {
        UUID aiPaperId = paperChatAccessValidator.requireReadyRequestPaperId(paperId, ownerId);

        userRepository.findWithLockById(ownerId)
                .orElseThrow(() -> new IllegalStateException("사용자 행 없음: " + ownerId));
        if (translationRunRepository.existsByOwnerIdAndStatus(ownerId, TranslationRunStatus.GENERATING)) {
            throw new ApiException(ErrorCode.TRANSLATION_IN_PROGRESS,
                    "이전 번역이 아직 진행 중입니다. 끝난 뒤 다시 시도하세요.");
        }

        Instant now = Instant.now();
        TranslationRun run = translationRunRepository.saveAndFlush(
                TranslationRun.start(ownerId, paperId, objectMapper.valueToTree(selection), now));
        paperAccessRecorder.recordAccess(paperId, now);
        usageService.reserve(ownerId, UsageType.AI_QUERY, run.getId(), UsageSourceType.INLINE_TRANSLATION);
        return new TranslationStartResult(paperId, aiPaperId.toString(), run.getId());
    }
}
