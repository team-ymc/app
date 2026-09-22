package com.ymc.paper.service.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * S3의 파서 산출물 패키지를 읽어 계약형으로 변환한다.
 * 구현: {@code infra/parsing/S3PaperPackageReader}.
 */
public interface PaperPackageReader {

    /**
     * @param manifestKey 패키지 manifest.json의 S3 key. 마지막 '/'까지가 패키지 prefix다.
     * @throws IllegalStateException 패키지가 계약과 어긋남(파일 누락·asset 참조 불일치 등).
     *         재시도해도 같은 결과인 비복구 오류지만, 부분 적재를 남기지 않는 게 우선이라 예외로 올린다
     *         — SQS 재전달 5회 후 DLQ로 빠진다.
     */
    ParsedPaperPackage read(String manifestKey);

    /**
     * 컴파일 산출물 사이드카(frontend/translation-ko.json)에서 번역된 블록만 읽는다.
     *
     * @return blockId → text_kor. manifest에 사이드카가 없거나 형식이 어긋나면 WARN 후 빈 Map.
     *         manifest 자체를 못 읽으면 예외 — 파싱 때 읽은 같은 파일이라 일시 장애로 본다.
     */
    Map<String, String> readTranslations(String manifestKey);

    /**
     * 컴파일 산출물 지식 그래프(knowledge-bundle/viz.html)의 S3 키. manifest의 knowledge_bundle_viz.path를
     * 다른 artifact와 같은 규칙으로 패키지 prefix에 붙인다.
     *
     * @return artifact가 없거나 path가 비어 있으면 WARN 후 empty. manifest 자체를 못 읽으면 예외.
     */
    Optional<String> readKnowledgeGraphKey(String manifestKey);

    /**
     * 컴파일 산출물 사이드카(frontend/prerequisite-highlights.json)의 선행지식 범위를 문서 순서대로 읽는다.
     *
     * @return manifest에 사이드카가 없거나 형식이 어긋나면 WARN 후 빈 목록. 불량 항목은 그 항목만 건너뛴다.
     *         manifest 자체를 못 읽으면 예외.
     */
    List<ParsedPrerequisiteHighlight> readPrerequisiteHighlights(String manifestKey);
}
