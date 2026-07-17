package com.ymc.paper.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/** 서재 목록 조회 (FT-002). 소유자는 인증 주체다 (YMC-215). 정렬·페이지네이션 없음(계약). */
@Service
@RequiredArgsConstructor
public class PaperListService {

    private final PaperRepository paperRepository;

    @Transactional(readOnly = true)
    public List<PaperListView> list(UUID ownerId) {
        return paperRepository.findAllByOwnerId(ownerId)
                .stream()
                .map(PaperListView::from)
                .toList();
    }
}
