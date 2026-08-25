package com.ymc.paper.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/**
 * lastAccessedAt 기록 모듈
 */
@Service
@RequiredArgsConstructor
public class PaperAccessRecorder {

    private final PaperRepository paperRepository;

    /** 존재·소유 검증을 통과한 뒤에만 호출 */
    @Transactional
    public void recordAccess(UUID paperId, Instant at) {
        paperRepository.findById(paperId).ifPresent(p -> p.markAccessed(at));
    }
}
