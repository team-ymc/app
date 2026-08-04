package com.ymc.paper.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaperContentRepository extends JpaRepository<PaperContent, UUID> {

    @Modifying
    @Query("delete from PaperContent c where c.paperId = :paperId")
    void deleteByPaperId(UUID paperId);
}
