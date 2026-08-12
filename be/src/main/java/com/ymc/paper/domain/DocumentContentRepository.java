package com.ymc.paper.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DocumentContentRepository extends JpaRepository<DocumentContent, UUID> {

    @Modifying
    @Query("delete from DocumentContent c where c.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);
}
