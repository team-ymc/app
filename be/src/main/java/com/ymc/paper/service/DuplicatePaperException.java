package com.ymc.paper.service;

import java.util.UUID;

import lombok.Getter;

/** 같은 사용자의 서재에 동일 파일의 논문이 이미 있다. 계약의 DuplicatePaperError(409)로 기존 paperId를 돌려준다. */
@Getter
public class DuplicatePaperException extends RuntimeException {

    private final UUID existingPaperId;

    public DuplicatePaperException(UUID existingPaperId) {
        super("이미 서재에 있는 논문입니다.");
        this.existingPaperId = existingPaperId;
    }
}
