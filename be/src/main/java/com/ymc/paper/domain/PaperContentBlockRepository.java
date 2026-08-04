package com.ymc.paper.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PaperContentBlockRepository extends JpaRepository<PaperContentBlock, Long> {

    /** 계약: blocks는 globalOrder 오름차순. 인덱스 (paper_id, global_order)를 탄다. */
    List<PaperContentBlock> findAllByPaperIdOrderByGlobalOrderAsc(UUID paperId);

    @Modifying
    @Query("delete from PaperContentBlock b where b.paperId = :paperId")
    void deleteByPaperId(UUID paperId);
}
