package com.ymc.paper.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;

import lombok.RequiredArgsConstructor;

/**
 * Paper 응답 값의 파생 규칙 — 파싱 상태의 진실 원천은 연결된 Document 하나다.
 * 연결 전(document 없음)은 paper 자신의 상태를 쓴다.
 */
@Component
@RequiredArgsConstructor
public class PaperDocumentViews {

    private final DocumentRepository documentRepository;

    public Optional<Document> documentOf(Paper paper) {
        if (paper.getDocumentId() == null) {
            return Optional.empty();
        }
        return documentRepository.findById(paper.getDocumentId());
    }

    public PaperStatusView statusView(Paper paper) {
        Document document = documentOf(paper).orElse(null);
        return new PaperStatusView(paper.getId(), derivedStatus(paper, document),
                derivedUpdatedAt(paper, document));
    }

    public List<PaperListView> listViews(List<Paper> papers) {
        List<UUID> documentIds = papers.stream()
                .map(Paper::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, Document> documents = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        return papers.stream()
                .map(p -> {
                    Document document = p.getDocumentId() == null
                            ? null : documents.get(p.getDocumentId());
                    return new PaperListView(p.getId(), p.getFilename(),
                            derivedStatus(p, document), p.getCreatedAt(),
                            derivedUpdatedAt(p, document), p.getLastAccessedAt());
                })
                .toList();
    }

    public PaperListView listView(Paper paper) {
        return listViews(List.of(paper)).get(0);
    }

    /** Document 상태는 API PaperStatus와 이름 1:1이다. 연결 전 = 업로드 대기. */
    static PaperStatus derivedStatus(Paper paper, Document document) {
        if (paper.getExpiredAt() != null) {
            return PaperStatus.EXPIRED;
        }
        if (document == null) {
            return PaperStatus.UPLOAD_PENDING;
        }
        return PaperStatus.valueOf(document.getStatus().name());
    }

    /** "이 Paper의 표시 상태가 마지막으로 바뀐 시각" = 연결 시각과 Document 전이 시각 중 최신. */
    static Instant derivedUpdatedAt(Paper paper, Document document) {
        if (document == null || paper.getUpdatedAt().isAfter(document.getUpdatedAt())) {
            return paper.getUpdatedAt();
        }
        return document.getUpdatedAt();
    }
}
