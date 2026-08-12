package com.ymc.paper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DocumentContentBlockRepository extends JpaRepository<DocumentContentBlock, Long> {

    /** 계약: blocks는 globalOrder 오름차순. 인덱스 (document_id, global_order)를 탄다. */
    List<DocumentContentBlock> findAllByDocumentIdOrderByGlobalOrderAsc(UUID documentId);

    @Modifying
    @Query("delete from DocumentContentBlock b where b.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);
}
