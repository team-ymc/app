package com.ymc.paper.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.paper.api.dto.CreatePaperRequest;
import com.ymc.paper.api.dto.PaperCreated;
import com.ymc.paper.api.dto.PaperStatusResponse;
import com.ymc.paper.service.PaperRegistrationService;
import com.ymc.paper.service.PaperStatusView;
import com.ymc.paper.service.PaperUploadCompletionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 계약(openapi.yaml)의 /api/papers. HTTP ↔ DTO 변환만 한다 (be/CLAUDE.md). */
@RestController
@RequestMapping("/api/papers")
@RequiredArgsConstructor
public class PaperController {

    private final PaperRegistrationService registrationService;
    private final PaperUploadCompletionService uploadCompletionService;

    /** 논문 레코드 생성 및 presigned 업로드 URL 발급. */
    @PostMapping
    public ResponseEntity<PaperCreated> create(@Valid @RequestBody CreatePaperRequest request) {
        PaperCreated body = PaperCreated.from(
                registrationService.register(request.filename(), request.contentType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** S3 업로드 완료 통보 (파싱 트리거). 멱등 — 중복 호출은 재발행 없이 현재 상태를 돌려준다. */
    @PostMapping("/{paperId}/complete")
    public PaperStatusResponse complete(@PathVariable UUID paperId) {
        return toResponse(uploadCompletionService.complete(paperId));
    }


    private static PaperStatusResponse toResponse(PaperStatusView view) {
        return new PaperStatusResponse(view.paperId(), view.status(), view.updatedAt());
    }
}
