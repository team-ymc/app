package com.ymc.paper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DocumentContentAssetRepository extends JpaRepository<DocumentContentAsset, Long> {

    List<DocumentContentAsset> findAllByDocumentId(UUID documentId);

    @Modifying
    @Query("delete from DocumentContentAsset a where a.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);
}
