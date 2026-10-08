package com.ymc.paper.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ymc.paper.api.dto.KnowledgeGraphViewResponse;
import com.ymc.paper.api.dto.PaperContentResponse;
import com.ymc.paper.api.dto.PrerequisiteDefinitionResponse;
import com.ymc.paper.service.KnowledgeGraphViewService;
import com.ymc.paper.service.PaperContentQueryService;
import com.ymc.paper.service.PrerequisiteDefinitionService;

import lombok.RequiredArgsConstructor;

/**
 * 계약(openapi.yaml)의 /api/trial/papers. 인증 없이 체험 논문(paper.trial)만 읽는다.
 * 응답은 /api/papers와 같고, 소유권 대신 trial 플래그를 검사한다.
 */
@RestController
@RequestMapping("/api/trial/papers")
@RequiredArgsConstructor
public class TrialPaperController {

    private final PaperContentQueryService contentQueryService;
    private final KnowledgeGraphViewService knowledgeGraphViewService;
    private final PrerequisiteDefinitionService prerequisiteDefinitionService;

    @GetMapping("/{paperId}/content")
    public PaperContentResponse content(@PathVariable UUID paperId) {
        return PaperContentResponse.from(contentQueryService.getTrialContent(paperId));
    }

    @GetMapping("/{paperId}/knowledge-graph")
    public KnowledgeGraphViewResponse knowledgeGraph(@PathVariable UUID paperId) {
        return KnowledgeGraphViewResponse.from(knowledgeGraphViewService.viewTrial(paperId));
    }

    @PostMapping("/{paperId}/prerequisite-highlights/{highlightId}/definition")
    public PrerequisiteDefinitionResponse prerequisiteDefinition(@PathVariable UUID paperId,
            @PathVariable String highlightId) {
        return PrerequisiteDefinitionResponse.from(prerequisiteDefinitionService.defineTrial(paperId, highlightId));
    }
}
