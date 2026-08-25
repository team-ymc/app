package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.chat.service.ChatCommandService;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

/** YMC-308 — 서재 목록의 lastAccessedAt 노출·정렬과 접근 시각 갱신. */
class PaperLastAccessedIntegrationTest extends IntegrationTest {

    @Autowired
    PaperListService paperListService;

    @Autowired
    PaperContentQueryService contentQueryService;

    @Autowired
    ChatCommandService chatCommandService;

    @Autowired
    DocumentContentIngestService ingestService;

    @Test
    @DisplayName("목록은 COALESCE(lastAccessedAt, createdAt) 내림차순으로 정렬된다")
    void listOrdersByRecentAccess() {
        UUID ownerId = UUID.randomUUID();

        Paper accessedEarlier = Paper.register(ownerId, "accessed-earlier.pdf",
                Instant.parse("2026-01-01T00:00:00Z"));
        accessedEarlier.markAccessed(Instant.parse("2026-01-10T00:00:00Z"));

        Paper neverAccessed = Paper.register(ownerId, "never-accessed.pdf",
                Instant.parse("2026-01-05T00:00:00Z"));

        Paper accessedLately = Paper.register(ownerId, "accessed-lately.pdf",
                Instant.parse("2026-01-02T00:00:00Z"));
        accessedLately.markAccessed(Instant.parse("2026-01-20T00:00:00Z"));

        paperRepository.saveAll(List.of(accessedEarlier, neverAccessed, accessedLately));

        List<PaperListView> views = paperListService.list(ownerId);

        assertThat(views).extracting(PaperListView::filename).containsExactly(
                "accessed-lately.pdf", "accessed-earlier.pdf", "never-accessed.pdf");
    }

    @Test
    @DisplayName("목록 뷰는 lastAccessedAt을 담고, 접근 이력이 없으면 null이다")
    void listViewCarriesLastAccessedAt() {
        UUID ownerId = UUID.randomUUID();
        Instant accessedAt = Instant.parse("2026-01-10T00:00:00Z");

        Paper accessed = Paper.register(ownerId, "accessed.pdf", Instant.parse("2026-01-01T00:00:00Z"));
        accessed.markAccessed(accessedAt);
        Paper untouched = Paper.register(ownerId, "untouched.pdf", Instant.parse("2026-01-05T00:00:00Z"));
        paperRepository.saveAll(List.of(accessed, untouched));

        List<PaperListView> views = paperListService.list(ownerId);

        assertThat(views).extracting(PaperListView::filename, PaperListView::lastAccessedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("accessed.pdf", accessedAt),
                        org.assertj.core.groups.Tuple.tuple("untouched.pdf", null));
    }

    @Test
    @DisplayName("content 조회가 lastAccessedAt을 갱신한다")
    void contentQueryTouchesLastAccessed() {
        Paper paper = givenProcessingPaper("content-touch.pdf");
        ingestService.ingest(paper.getDocumentId(), givenPackageOnS3(paper.getId()));
        documentTransitions.markParsed(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        assertThat(reload(paper.getId()).getLastAccessedAt()).isNull();

        contentQueryService.getContent(paper.getId(), TEST_USER_ID);

        assertThat(reload(paper.getId()).getLastAccessedAt()).isNotNull();
    }

    @Test
    @DisplayName("채팅 시작이 lastAccessedAt을 갱신한다")
    void chatStartTouchesLastAccessed() {
        Paper paper = givenProcessingPaper("chat-touch.pdf");
        documentTransitions.markParsed(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        assertThat(reload(paper.getId()).getLastAccessedAt()).isNull();

        chatCommandService.start(TEST_USER_ID, paper.getId(), null, UUID.randomUUID(), "질문");

        assertThat(reload(paper.getId()).getLastAccessedAt()).isNotNull();
    }
}
