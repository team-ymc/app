package com.ymc.paper.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ymc.paper.domain.PaperStatus;
import com.ymc.paper.service.PaperListView;

/** 계약 `PaperList`. */
public record PaperListResponse(List<Item> papers) {

    public static PaperListResponse from(List<PaperListView> views) {
        return new PaperListResponse(views.stream().map(Item::from).toList());
    }

    /** 계약 `PaperListItem`. */
    public record Item(UUID paperId, String filename, PaperStatus status,
                       Instant createdAt, Instant updatedAt) {
        static Item from(PaperListView v) {
            return new Item(v.paperId(), v.filename(), v.status(), v.createdAt(), v.updatedAt());
        }
    }
}
