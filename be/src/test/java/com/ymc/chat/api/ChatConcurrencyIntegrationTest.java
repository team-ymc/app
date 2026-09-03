package com.ymc.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.ymc.chat.domain.ChatMessageRole;
import com.ymc.chat.domain.ChatMessageStatus;
import com.ymc.chat.domain.ChatSession;
import com.ymc.chat.service.ChatCommandService;
import com.ymc.chat.service.ChatMessageTransitions;
import com.ymc.chat.service.ChatStartResult;
import com.ymc.chat.service.DuplicateChatMessageException;
import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class ChatConcurrencyIntegrationTest extends IntegrationTest {

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    ChatMessageTransitions chatMessageTransitions;

    Paper givenCompletedPaper(UUID owner, String filename) {
        Paper paper = paperRepository.save(Paper.register(owner, filename, Instant.now()));
        Document document = givenLinkedDocument(paper);
        documentTransitions.markProcessing(document.getId());
        documentTransitions.markParsedAndSettle(document.getId(), DocumentStatus.COMPLETED, null);
        return reload(paper.getId());
    }

    ChatStartResult startNewSession(UUID owner, Paper paper) {
        return chatCommandService.start(owner, paper.getId(), null, UUID.randomUUID(), "질문");
    }

    long activeCount(UUID owner) {
        return chatMessageRepository.countBySessionOwnerIdAndRoleAndStatus(
                owner, ChatMessageRole.ASSISTANT, ChatMessageStatus.GENERATING);
    }

    @Test
    @DisplayName("활성 수는 소유자의 GENERATING만 센다 — 완료·실패·다른 사용자는 제외, 삭제된 세션은 포함")
    void countsOnlyOwnersGeneratingAssistants() {
        Paper mine = givenCompletedPaper(TEST_USER_ID, "mine.pdf");
        Paper theirs = givenCompletedPaper(OTHER_USER_ID, "theirs.pdf");

        ChatStartResult generating = startNewSession(TEST_USER_ID, mine);
        ChatStartResult completed = startNewSession(TEST_USER_ID, mine);
        ChatStartResult failed = startNewSession(TEST_USER_ID, mine);
        startNewSession(OTHER_USER_ID, theirs);

        chatMessageTransitions.complete(completed.assistantMessageId(), "답변", completed.clientMessageId(), null);
        chatMessageTransitions.fail(failed.assistantMessageId(), failed.clientMessageId());
        tx.executeWithoutResult(s -> {
            ChatSession session = chatSessionRepository.findById(generating.sessionId()).orElseThrow();
            session.markDeleted(Instant.now());
        });

        assertThat(activeCount(TEST_USER_ID)).isEqualTo(1);
        assertThat(activeCount(OTHER_USER_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("활성 세션이 상한(3)이면 4번째 시작은 429이고 세션·메시지·예약이 추가되지 않는다")
    void fourthStartRejectedAtLimit() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "limit.pdf");
        for (int i = 0; i < 3; i++) {
            startNewSession(TEST_USER_ID, paper);
        }
        long sessions = chatSessionRepository.count();
        long messages = chatMessageRepository.count();
        long records = usageRecordRepository.count();
        Instant accessedBefore = reload(paper.getId()).getLastAccessedAt();

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> startNewSession(TEST_USER_ID, paper));

        assertThat(rejected.code()).isEqualTo(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED);
        assertThat(chatSessionRepository.count()).isEqualTo(sessions);
        assertThat(chatMessageRepository.count()).isEqualTo(messages);
        assertThat(usageRecordRepository.count()).isEqualTo(records);
        assertThat(reload(paper.getId()).getLastAccessedAt()).isEqualTo(accessedBefore);
    }

    @Test
    @DisplayName("기존 세션으로 온 4번째 요청도 429이고 세션 활동 시각이 갱신되지 않는다")
    void fourthStartOnExistingSessionRejected() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "existing.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        chatMessageTransitions.complete(first.assistantMessageId(), "답변", first.clientMessageId(), null);
        startNewSession(TEST_USER_ID, paper); // 다시 3개 활성, first 세션은 유휴
        Instant activityBefore = chatSessionRepository.findById(first.sessionId()).orElseThrow().getLastMessageAt();

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), first.sessionId(),
                        UUID.randomUUID(), "후속 질문"));

        assertThat(rejected.code()).isEqualTo(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED);
        assertThat(chatSessionRepository.findById(first.sessionId()).orElseThrow().getLastMessageAt())
                .isEqualTo(activityBefore);
    }

    @Test
    @DisplayName("활성 세션 하나가 완료되면 다시 시작할 수 있다")
    void startAllowedAfterOneCompletes() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "free-slot.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);

        chatMessageTransitions.complete(first.assistantMessageId(), "답변", first.clientMessageId(), null);

        assertThat(startNewSession(TEST_USER_ID, paper)).isNotNull();
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("논리 삭제된 세션의 GENERATING도 상한에 센다")
    void deletedGeneratingSessionStillCounts() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "deleted.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        tx.executeWithoutResult(s -> chatSessionRepository.findById(first.sessionId())
                .orElseThrow().markDeleted(Instant.now()));

        assertThatThrownBy(() -> startNewSession(TEST_USER_ID, paper))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("같은 세션 재요청은 사용자 전체 상한보다 먼저 CHAT_RUN_IN_PROGRESS로 거절된다")
    void sameSessionRejectedBeforeUserLimit() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "same-session.pdf");
        ChatStartResult first = startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), first.sessionId(),
                        UUID.randomUUID(), "후속 질문"));

        assertThat(rejected.code()).isEqualTo(ErrorCode.CHAT_RUN_IN_PROGRESS);
    }

    @Test
    @DisplayName("start 뒤 트랜잭션이 롤백되면 예약과 활성 슬롯이 함께 풀린다")
    void rollbackFreesReservationAndSlot() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "rollback.pdf");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            startNewSession(TEST_USER_ID, paper);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(activeCount(TEST_USER_ID)).isZero();
        assertThat(usageRecordRepository.count()).isZero();
    }

    @Test
    @DisplayName("사용자 행이 없으면 IllegalStateException")
    void missingUserRowFails() {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "no-user.pdf");
        jdbcTemplate.update("delete from users where id = ?", TEST_USER_ID);

        assertThatThrownBy(() -> startNewSession(TEST_USER_ID, paper))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("HTTP: 상한에서 429와 CHAT_CONCURRENCY_LIMIT_EXCEEDED를 반환한다")
    void httpReturns429WithCode() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "http.pdf");
        for (int i = 0; i < 3; i++) {
            startNewSession(TEST_USER_ID, paper);
        }

        mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/papers/{paperId}/chat/messages", paper.getId())
                        .with(userJwt())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"%s","content":"질문"}""".formatted(UUID.randomUUID())))
                .andExpect(MockMvcResultMatchers.status().isTooManyRequests())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("CHAT_CONCURRENCY_LIMIT_EXCEEDED"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("동시에 진행 중인 답변이 3개입니다. 하나가 끝나면 다시 시도하세요."));
    }

    @Test
    @DisplayName("활성 2개에서 새 세션 시작 2건이 사용자 잠금에서 직렬화되어 하나만 성공한다")
    void concurrentNewSessionStartsAdmitExactlyOne() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "race-new.pdf");
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);

        List<Throwable> outcomes = runWhileUserLockHeld(TEST_USER_ID,
                () -> startNewSession(TEST_USER_ID, paper),
                () -> startNewSession(TEST_USER_ID, paper));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(t -> t instanceof ApiException e
                && e.code() == ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED).hasSize(1);
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("활성 2개에서 서로 다른 기존 세션의 시작 2건도 하나만 성공한다")
    void concurrentExistingSessionStartsAdmitExactlyOne() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "race-existing.pdf");
        ChatStartResult a = startNewSession(TEST_USER_ID, paper);
        ChatStartResult b = startNewSession(TEST_USER_ID, paper);
        chatMessageTransitions.complete(a.assistantMessageId(), "답변", a.clientMessageId(), null);
        chatMessageTransitions.complete(b.assistantMessageId(), "답변", b.clientMessageId(), null);
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper); // 활성 2개, a·b는 유휴

        List<Throwable> outcomes = runWhileUserLockHeld(TEST_USER_ID,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), a.sessionId(), UUID.randomUUID(), "a 후속"),
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), b.sessionId(), UUID.randomUUID(), "b 후속"));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(t -> t instanceof ApiException e
                && e.code() == ErrorCode.CHAT_CONCURRENCY_LIMIT_EXCEEDED).hasSize(1);
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 clientMessageId의 새 세션 시작 2건은 잠금 후 재판정으로 진 쪽이 DUPLICATE_MESSAGE를 받는다")
    void concurrentSameClientMessageIdYieldsDuplicate() throws Exception {
        Paper paper = givenCompletedPaper(TEST_USER_ID, "race-dup.pdf");
        startNewSession(TEST_USER_ID, paper);
        startNewSession(TEST_USER_ID, paper);
        UUID clientMessageId = UUID.randomUUID();

        List<Throwable> outcomes = runWhileUserLockHeld(TEST_USER_ID,
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문"),
                () -> chatCommandService.start(TEST_USER_ID, paper.getId(), null, clientMessageId, "같은 질문"));

        assertThat(outcomes).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(outcomes).filteredOn(t -> t instanceof DuplicateChatMessageException).hasSize(1);
        assertThat(activeCount(TEST_USER_ID)).isEqualTo(3);
        assertThat(chatSessionRepository.count()).isEqualTo(3);
    }

    /**
     * 테스트 스레드가 users 행을 잠근 채 두 작업을 출발시키고, 둘 다 잠금 대기에 들어간 뒤 풀어준다.
     * 각 작업의 예외(성공이면 null)를 돌려준다.
     */
    private List<Throwable> runWhileUserLockHeld(UUID userId, Runnable first, Runnable second) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                userRepository.findWithLockById(userId).orElseThrow();
                held.countDown();
                awaitOrFail(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).as("holder가 잠금을 잡음").isTrue();

            Future<Throwable> f1 = pool.submit(() -> attempt(first));
            Future<Throwable> f2 = pool.submit(() -> attempt(second));
            awaitLockWaiters(2);
            release.countDown();

            holder.get(10, TimeUnit.SECONDS);
            return Arrays.asList(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    /** 잠금 대기 중인 DB 세션이 n개가 될 때까지 기다린다 (최대 5초). 잠금 구현이 없으면 여기서 실패한다. */
    private void awaitLockWaiters(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= n) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("잠금 대기 세션 " + n + "개가 생기지 않음 — 사용자 잠금이 동작하지 않는다");
    }

    private static Throwable attempt(Runnable task) {
        try {
            task.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void awaitOrFail(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
