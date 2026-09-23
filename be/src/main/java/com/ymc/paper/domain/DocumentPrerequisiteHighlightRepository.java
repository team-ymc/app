package com.ymc.paper.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DocumentPrerequisiteHighlightRepository extends JpaRepository<DocumentPrerequisiteHighlight, Long> {

    /** 적재 순서가 곧 문서 순서다 — 사이드카가 문서 순서로 정렬돼 온다. */
    List<DocumentPrerequisiteHighlight> findAllByDocumentIdOrderByIdAsc(UUID documentId);

    Optional<DocumentPrerequisiteHighlight> findByDocumentIdAndHighlightId(UUID documentId, String highlightId);

    @Modifying
    @Query("delete from DocumentPrerequisiteHighlight h where h.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);
}
