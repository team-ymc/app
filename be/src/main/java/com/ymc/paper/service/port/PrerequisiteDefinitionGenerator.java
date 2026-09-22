package com.ymc.paper.service.port;

import com.ymc.paper.service.PrerequisiteDefinition;

/** BE↔AI prerequisite-knowledge-agent-run(non-streaming) 호출. 구현: infra/ai. */
public interface PrerequisiteDefinitionGenerator {

    /**
     * @param requestPaperId AI가 아는 논문 id (document.requestPaperId)
     * @throws GenerationFailedException AI 4xx·5xx, 타임아웃, 응답 형식 오류
     */
    PrerequisiteDefinition generate(String requestPaperId, String blockId, int startOffset, int endOffset);

    class GenerationFailedException extends RuntimeException {
        public GenerationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
