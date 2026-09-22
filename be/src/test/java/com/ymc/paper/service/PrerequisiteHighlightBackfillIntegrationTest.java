package com.ymc.paper.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ymc.paper.domain.CompileStatus;
import com.ymc.paper.domain.DocumentStatus;
import com.ymc.paper.domain.Paper;
import com.ymc.support.IntegrationTest;

class PrerequisiteHighlightBackfillIntegrationTest extends IntegrationTest {

    @Autowired
    PrerequisiteHighlightBackfill backfill;

    private Paper givenCompiledWithoutHighlights(String filename, boolean sidecarOnS3) {
        Paper paper = givenProcessingPaper(filename);
        documentTransitions.markParsedAndSettle(paper.getDocumentId(), DocumentStatus.COMPLETED, null);
        String manifestKey = sidecarOnS3 ? givenTranslatedPackageOnS3(paper.getId()) : givenPackageOnS3(paper.getId());
        documentContentIngestService.ingest(paper.getDocumentId(), manifestKey);
        documentTransitions.markCompiled(paper.getDocumentId(), CompileStatus.COMPLETED, null, null);
        return reload(paper.getId());
    }

    @Test
    void 컴파일_완료인데_하이라이트가_없는_Document만_채운다() {
        Paper withSidecar = givenCompiledWithoutHighlights("bf-with.pdf", true);
        Paper withoutSidecar = givenCompiledWithoutHighlights("bf-without.pdf", false);

        int filled = backfill.run();

        assertThat(filled).isEqualTo(1);
        assertThat(documentPrerequisiteHighlightRepository
                .findAllByDocumentIdOrderByIdAsc(withSidecar.getDocumentId())).hasSize(2);
        assertThat(documentPrerequisiteHighlightRepository
                .findAllByDocumentIdOrderByIdAsc(withoutSidecar.getDocumentId())).isEmpty();
    }

    @Test
    void 두_번_돌려도_이미_채운_Document는_건드리지_않는다() {
        givenCompiledWithoutHighlights("bf-twice.pdf", true);
        backfill.run();

        assertThat(backfill.run()).isZero();
    }
}
