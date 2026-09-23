package com.ymc.paper.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ymc.common.error.ApiException;
import com.ymc.common.error.ErrorCode;
import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.Document;
import com.ymc.paper.domain.DocumentContentRepository;
import com.ymc.paper.domain.DocumentPrerequisiteHighlight;
import com.ymc.paper.domain.DocumentPrerequisiteHighlightRepository;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.paper.domain.PaperRepository;
import com.ymc.paper.infra.ai.PrerequisiteDefinitionProperties;
import com.ymc.paper.service.port.PrerequisiteDefinitionCache;
import com.ymc.paper.service.port.PrerequisiteDefinitionGenerator;
import com.ymc.paper.service.port.PrerequisiteGenerationLock;

import lombok.RequiredArgsConstructor;

/**
 * 선행지식 설명 조회 또는 생성. 검증은 짧은 트랜잭션에서 끝내고, 캐시·잠금·AI 호출은 트랜잭션 밖에서 한다.
 * 사용량은 차감하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PrerequisiteDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(PrerequisiteDefinitionService.class);

    private final PaperRepository paperRepository;
    private final PaperDocumentViews views;
    private final DocumentContentRepository contentRepository;
    private final DocumentPrerequisiteHighlightRepository highlightRepository;
    private final PrerequisiteDefinitionCache cache;
    private final PrerequisiteGenerationLock lock;
    private final PrerequisiteDefinitionGenerator generator;
    private final PrerequisiteDefinitionProperties properties;
    private final PrerequisiteDefinitionMetrics metrics;

    public record PrerequisiteDefinitionView(String term, String definitionEn, String definitionKo) {
    }

    /** 검증 결과. AI 요청과 캐시 키에 필요한 값만 담는다. */
    record Resolved(UUID documentId, UUID requestPaperId, DocumentPrerequisiteHighlight highlight) {
    }

    public PrerequisiteDefinitionView define(UUID paperId, UUID ownerId, String highlightId) {
        Resolved resolved = resolve(paperId, ownerId, highlightId);
        DocumentPrerequisiteHighlight h = resolved.highlight();
        String key = cacheKey(resolved.documentId(), h.getText());

        Optional<PrerequisiteDefinition> cached = cache.get(key);
        if (cached.isPresent()) {
            metrics.hit();
            return view(h, cached.get());
        }

        String token;
        try {
            token = lock.tryAcquire(ownerId).orElse(null);
        } catch (PrerequisiteGenerationLock.LockUnavailableException e) {
            metrics.failed();
            log.warn("선행지식 잠금 저장소 장애, 생성 거절: ownerId={}", ownerId, e);
            throw new ApiException(ErrorCode.PREREQUISITE_DEFINITION_FAILED, "설명을 생성할 수 없습니다.");
        }
        if (token == null) {
            metrics.rejectedConcurrent();
            throw new ApiException(ErrorCode.PREREQUISITE_CONCURRENCY_LIMIT_EXCEEDED,
                    "다른 선행지식 설명을 생성 중입니다.");
        }
        try {
            PrerequisiteDefinition generated = generator.generate(
                    resolved.requestPaperId().toString(), h.getBlockId(), h.getStartOffset(), h.getEndOffset());
            cache.put(key, generated);
            metrics.generated();
            return view(h, generated);
        } catch (PrerequisiteDefinitionGenerator.GenerationFailedException e) {
            metrics.failed();
            log.warn("선행지식 설명 생성 실패: documentId={}, highlightId={}", resolved.documentId(), highlightId, e);
            throw new ApiException(ErrorCode.PREREQUISITE_DEFINITION_FAILED, "설명 생성에 실패했습니다.");
        } finally {
            lock.release(ownerId, token);
        }
    }

    /**
     * @throws ApiException PAPER_NOT_FOUND(404), FORBIDDEN(403), PREREQUISITE_NOT_READY(409),
     *         PREREQUISITE_HIGHLIGHT_NOT_FOUND(404)
     */
    // 자기 호출이라 readOnly 트랜잭션은 걸리지 않는다. 조회 4개가 각각 auto-commit이어도 결과는 같다.
    Resolved resolve(UUID paperId, UUID ownerId, String highlightId) {
        Paper paper = paperRepository.findActiveById(paperId).orElseThrow(
                () -> new ApiException(ErrorCode.PAPER_NOT_FOUND, "존재하지 않는 논문입니다."));
        if (!paper.getOwnerId().equals(ownerId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "이 논문에 접근할 권한이 없습니다.");
        }
        Document document = views.documentOf(paper).orElseThrow(
                () -> new ApiException(ErrorCode.PREREQUISITE_NOT_READY, "논문이 아직 준비되지 않았습니다."));
        if (document.getStatus() != DocumentStatus.COMPLETED
                || document.getCompileStatus() != CompileStatus.COMPLETED
                || contentRepository.findById(document.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_READY, "선행지식이 아직 준비되지 않았습니다.");
        }
        DocumentPrerequisiteHighlight highlight = highlightRepository
                .findByDocumentIdAndHighlightId(document.getId(), highlightId).orElseThrow(
                        () -> new ApiException(ErrorCode.PREREQUISITE_HIGHLIGHT_NOT_FOUND, "없는 선행지식입니다."));
        return new Resolved(document.getId(), document.getRequestPaperId(), highlight);
    }

    private PrerequisiteDefinitionView view(DocumentPrerequisiteHighlight h, PrerequisiteDefinition d) {
        return new PrerequisiteDefinitionView(h.getText(), d.definitionEn(), d.definitionKo());
    }

    String cacheKey(UUID documentId, String term) {
        return "prerequisite-definition:v1:" + documentId + ":" + properties.generatorVersion()
                + ":" + sha256Hex(normalize(term));
    }

    static String normalize(String term) {
        return Normalizer.normalize(term, Normalizer.Form.NFC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
