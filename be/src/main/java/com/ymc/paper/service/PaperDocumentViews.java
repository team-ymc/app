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
import com.ymc.paper.domain.DocumentContent;
import com.ymc.paper.domain.DocumentContentRepository;
import com.ymc.paper.domain.DocumentRepository;
import com.ymc.paper.domain.KnowledgeGraphStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.domain.TranslationStatus;

import lombok.RequiredArgsConstructor;

/**
 * Paper 응답 값의 파생 규칙. 처리 상태는 연결된 Document에서 오지만, 환불된 Paper는
 * Document가 완료돼도 실패로 본다. 본문·채팅·선행지식·지식 그래프가 모두 이 규칙을 거친다.
 */
@Component
@RequiredArgsConstructor
public class PaperDocumentViews {

    private final DocumentRepository documentRepository;
    private final DocumentContentRepository contentRepository;

    public Optional<Document> documentOf(Paper paper) {
        if (paper.getDocumentId() == null) {
            return Optional.empty();
        }
        return documentRepository.findById(paper.getDocumentId());
    }

    public PaperStatusView statusView(Paper paper) {
        Document document = documentOf(paper).orElse(null);
        TranslationStatus translationStatus = document == null
                ? TranslationStatus.NOT_APPLICABLE : document.translationStatus();
        // 연결 전에는 컴파일 상태를 판단할 근거가 없다 — PENDING으로 뭉개지 않고 null로 드러낸다.
        KnowledgeGraphStatus knowledgeGraphStatus = document == null ? null : document.knowledgeGraphStatus();
        return new PaperStatusView(paper.getId(), derivedStatus(paper, document), translationStatus,
                knowledgeGraphStatus, derivedUpdatedAt(paper, document));
    }

    public List<PaperListView> listViews(List<Paper> papers) {
        List<UUID> documentIds = papers.stream()
                .map(Paper::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, Document> documents = documentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<UUID, DocumentContent> contents = contentRepository.findAllById(documentIds).stream()
                .collect(Collectors.toMap(DocumentContent::getDocumentId, Function.identity()));
        return papers.stream()
                .map(p -> {
                    Document document = p.getDocumentId() == null
                            ? null : documents.get(p.getDocumentId());
                    DocumentContent content = p.getDocumentId() == null
                            ? null : contents.get(p.getDocumentId());
                    return new PaperListView(p.getId(), resolvedTitle(p, content), p.getFilename(),
                            derivedStatus(p, document), p.getCreatedAt(),
                            derivedUpdatedAt(p, document), p.getLastAccessedAt());
                })
                .toList();
    }

    private static String resolvedTitle(Paper paper, DocumentContent content) {
        if (paper.getTitleOverride() != null && !paper.getTitleOverride().isBlank()) {
            return paper.getTitleOverride();
        }
        if (content != null && content.getTitle() != null && !content.getTitle().isBlank()) {
            return content.getTitle();
        }
        return paper.getFilename();
    }

    public PaperListView listView(Paper paper) {
        return listViews(List.of(paper)).get(0);
    }

    /** 만료, 환불 표시, 미연결 순으로 보고 그 뒤에야 Document 상태를 따른다. */
    static PaperStatus derivedStatus(Paper paper, Document document) {
        if (paper.getExpiredAt() != null) {
            return PaperStatus.EXPIRED;
        }
        if (paper.getFailedAt() != null) {
            return PaperStatus.FAILED;
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
