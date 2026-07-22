package com.ymc.paper.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.paper.api.dto.CreatePaperRequest;
import com.ymc.paper.api.dto.PaperCreated;
import com.ymc.paper.api.dto.PaperDownloadResponse;
import com.ymc.paper.api.dto.PaperListResponse;
import com.ymc.paper.api.dto.PaperStatusResponse;
import com.ymc.paper.service.PaperDownloadService;
import com.ymc.paper.service.PaperListService;
import com.ymc.paper.service.PaperRegistrationService;
import com.ymc.paper.service.PaperStatusService;
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
    private final PaperStatusService statusService;
    private final PaperDownloadService downloadService;
    private final PaperListService listService;

    /** 논문 레코드 생성 및 presigned 업로드 URL 발급. 소유자는 인증 주체다 (YMC-215). */
    @PostMapping
    public ResponseEntity<PaperCreated> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePaperRequest request) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        PaperCreated body = PaperCreated.from(
                registrationService.register(ownerId, request.filename(), request.contentType()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** S3 업로드 완료 통보 (파싱 트리거). 멱등 — 중복 호출은 재발행 없이 현재 상태를 돌려준다. */
    @PostMapping("/{paperId}/complete")
    public PaperStatusResponse complete(@PathVariable UUID paperId) {
        return toResponse(uploadCompletionService.complete(paperId));
    }

    /** 처리 상태 조회 (폴링용). */
    @GetMapping("/{paperId}/status")
    public PaperStatusResponse status(@PathVariable UUID paperId) {
        return toResponse(statusService.getStatus(paperId));
    }

    /** 원본 PDF 다운로드 URL 발급. */
    @GetMapping("/{paperId}/download")
    public PaperDownloadResponse download(@PathVariable UUID paperId) {
        return PaperDownloadResponse.from(downloadService.download(paperId));
    }

    /** 서재 목록 조회. 인증 주체 소유 논문만 반환한다 (YMC-215). */
    @GetMapping
    public PaperListResponse list(@AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = UUID.fromString(jwt.getSubject());
        return PaperListResponse.from(listService.list(ownerId));
    }

    private static PaperStatusResponse toResponse(PaperStatusView view) {
        return new PaperStatusResponse(view.paperId(), view.status(), view.updatedAt());
    }
}
