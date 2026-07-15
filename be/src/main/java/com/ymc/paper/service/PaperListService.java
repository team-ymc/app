package com.ymc.paper.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ymc.common.config.AppProperties;
import com.ymc.paper.domain.PaperRepository;

import lombok.RequiredArgsConstructor;

/** 서재 목록 조회 (FT-002). 인증 없음 — 고정 owner 전체. 정렬·페이지네이션 없음(계약). */
@Service
@RequiredArgsConstructor
public class PaperListService {

    private final PaperRepository paperRepository;
    private final AppProperties appProperties;

    @Transactional(readOnly = true)
    public List<PaperListView> list() {
        return paperRepository.findAllByOwnerId(appProperties.fixedOwnerId())
                .stream()
                .map(PaperListView::from)
                .toList();
    }
}
