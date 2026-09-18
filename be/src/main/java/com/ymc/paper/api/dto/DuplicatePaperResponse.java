package com.ymc.paper.api.dto;

import java.util.UUID;

/** 계약의 `DuplicatePaperError` 스키마 (409, code = DUPLICATE_PAPER). */
public record DuplicatePaperResponse(String code, String message, UUID existingPaperId) {

    public static DuplicatePaperResponse of(String message, UUID existingPaperId) {
        return new DuplicatePaperResponse("DUPLICATE_PAPER", message, existingPaperId);
    }
}
