package com.ymc.paper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaperContentAssetRepository extends JpaRepository<PaperContentAsset, Long> {

    List<PaperContentAsset> findAllByPaperId(UUID paperId);

    @Modifying
    @Query("delete from PaperContentAsset a where a.paperId = :paperId")
    void deleteByPaperId(UUID paperId);
}
